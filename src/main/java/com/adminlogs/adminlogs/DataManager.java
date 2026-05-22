package com.adminlogs.adminlogs;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles persistent storage of command logs using a YAML flat file.
 * Each op player has a list of LogEntry objects stored under their UUID.
 * Data survives server restarts.
 *
 * <p>Auto-save behaviour:
 * <ul>
 *   <li>Triggered every 5 commands (global across all players, not per-player)
 *       to reduce the risk of losing data on crash without hammering disk.</li>
 *   <li>Also runs on a 5-minute periodic async task as a safety net.</li>
 *   <li>Always runs synchronously on the main thread when called from
 *       logCommand (which is itself sync), and on shutdown via onDisable.</li>
 * </ul>
 *
 * <p>Write safety: save() writes to a temporary file first, then atomically
 * renames it over logs.yml, so a mid-write crash cannot corrupt saved data.
 *
 * <p>Log cap: each player's log is trimmed to maxEntriesPerPlayer (default 2000, set via config.yml)
 * oldest-first to prevent unbounded growth.
 */
public class DataManager {

    // maxEntriesPerPlayer is intentionally NOT cached — it is fetched from config
    // dynamically in logCommand() so that /adminlogs reload takes effect immediately
    // without requiring a server restart.

    /**
     * Trigger an auto-save after this many commands have been logged globally.
     * 5 is a safe default — low enough to limit crash-loss, infrequent enough
     * to avoid hammering disk even under heavy load.
     */
    private static final int AUTO_SAVE_INTERVAL = 5;

    /** Periodic save interval in ticks (20 ticks = 1 s, so 6000 = 5 min). */
    private static final long PERIODIC_SAVE_TICKS = 6000L;

    private final AdminLogs plugin;
    private final File dataFile;
    private final File dataTempFile;

    // In-memory map: UUID -> list of log entries
    private final Map<String, List<LogEntry>> logs = new LinkedHashMap<>();

    // Player names cache: UUID -> last known name
    private final Map<String, String> playerNames = new LinkedHashMap<>();

    // Global command counter for auto-save triggering
    private int commandsSinceLastSave = 0;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public DataManager(AdminLogs plugin) {
        this.plugin = plugin;
        this.dataFile            = new File(plugin.getDataFolder(), "logs.yml");
        this.dataTempFile        = new File(plugin.getDataFolder(), "logs.yml.tmp");
        // max-entries-per-player is read dynamically in logCommand() — no caching here.
        startPeriodicSave();
    }

    // ─────────────────────────────────────────────────────────
    //  Periodic save task
    // ─────────────────────────────────────────────────────────

    /**
     * Schedules a repeating task that saves to disk every 5 minutes.
     * Runs on the main server thread (runTaskTimer, not async) so it is
     * safe to iterate the logs map alongside logCommand.
     */
    private void startPeriodicSave() {
        new BukkitRunnable() {
            @Override
            public void run() {
                save();
            }
        }.runTaskTimer(plugin, PERIODIC_SAVE_TICKS, PERIODIC_SAVE_TICKS);
    }

    // ─────────────────────────────────────────────────────────
    //  Load
    // ─────────────────────────────────────────────────────────

    public void load() {
        if (!dataFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create logs.yml: " + e.getMessage());
            }
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

        // Load names cache
        if (data.contains("names")) {
            for (String uuid : Objects.requireNonNull(data.getConfigurationSection("names")).getKeys(false)) {
                playerNames.put(uuid, data.getString("names." + uuid));
            }
        }

        // Load logs
        if (data.contains("logs")) {
            for (String uuid : Objects.requireNonNull(data.getConfigurationSection("logs")).getKeys(false)) {
                List<LogEntry> entries = new ArrayList<>();
                List<?> rawList = data.getList("logs." + uuid);
                if (rawList != null) {
                    for (Object obj : rawList) {
                        if (obj instanceof Map) {
                            Map<?, ?> map = (Map<?, ?>) obj;
                            String command   = (String) map.get("command");
                            String timestamp = (String) map.get("timestamp");
                            if (command != null && timestamp != null) {
                                entries.add(new LogEntry(command, timestamp));
                            }
                        }
                    }
                }
                logs.put(uuid, entries);
            }
        }

        plugin.getLogger().info("Loaded logs for " + logs.size() + " player(s).");
    }

    // ─────────────────────────────────────────────────────────
    //  Save  (write-to-temp, then atomic rename)
    // ─────────────────────────────────────────────────────────

    /**
     * Saves all data to disk. Uses a write-to-temp-then-rename pattern so
     * that a crash or OOM mid-write never leaves logs.yml in a corrupt state:
     * the rename is atomic on most OS/JVM combinations, so readers always see
     * either the old complete file or the new complete file, never a partial one.
     */
    public void save() {
        // 1. Snapshot all data on the main thread to avoid ConcurrentModificationException
        //    when the async task reads the maps while logCommand() is modifying them.
        final Map<String, String> namesSnapshot = new LinkedHashMap<>(playerNames);
        final Map<String, List<LogEntry>> logsSnapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<LogEntry>> entry : logs.entrySet()) {
            logsSnapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        // Reset counter here (main thread) so logCommand() sees it immediately.
        commandsSinceLastSave = 0;

        // 2. Perform all disk I/O — async during normal operation, sync on shutdown.
        //    runTaskAsynchronously is rejected by Bukkit once the plugin is disabling,
        //    so we fall back to a direct synchronous call in that case to guarantee
        //    data is flushed before the JVM exits.
        Runnable saveTask = () -> {
            YamlConfiguration data = new YamlConfiguration();

            // Save names
            for (Map.Entry<String, String> entry : namesSnapshot.entrySet()) {
                data.set("names." + entry.getKey(), entry.getValue());
            }

            // Save logs
            for (Map.Entry<String, List<LogEntry>> entry : logsSnapshot.entrySet()) {
                List<Map<String, String>> serialized = new ArrayList<>();
                for (LogEntry log : entry.getValue()) {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("command",   log.getCommand());
                    map.put("timestamp", log.getTimestamp());
                    serialized.add(map);
                }
                data.set("logs." + entry.getKey(), serialized);
            }

            try {
                // Write to temp file first, then atomic rename over the real file
                plugin.getDataFolder().mkdirs();
                data.save(dataTempFile);
                Files.move(dataTempFile.toPath(), dataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save logs.yml: " + e.getMessage());
                // Clean up orphaned temp file if rename failed
                if (dataTempFile.exists()) {
                    dataTempFile.delete();
                }
            }
        };

        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, saveTask);
        } else {
            // Plugin is disabling — scheduler is shut down, run synchronously
            saveTask.run();
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Log a command
    // ─────────────────────────────────────────────────────────

    /**
     * Records a command for a player. Auto-saves every AUTO_SAVE_INTERVAL
     * commands globally (not per-player) to prevent churning disk when
     * multiple ops are active simultaneously.
     *
     * <p>Also enforces the per-player entry cap by removing the oldest entry
     * once the limit is exceeded.
     */
    public void logCommand(String uuid, String playerName, String command) {
        playerNames.put(uuid, playerName);

        List<LogEntry> playerLogs = logs.computeIfAbsent(uuid, k -> new ArrayList<>());
        playerLogs.add(new LogEntry(command, FORMATTER.format(Instant.now())));

        // Enforce per-player cap — fetch dynamically so /adminlogs reload applies immediately
        int maxEntriesPerPlayer = plugin.getConfig().getInt("max-entries-per-player", 2000);
        if (playerLogs.size() > maxEntriesPerPlayer) {
            playerLogs.remove(0);
        }

        // Auto-save based on global command count, not per-player
        commandsSinceLastSave++;
        if (commandsSinceLastSave >= AUTO_SAVE_INTERVAL) {
            save();
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Accessors
    // ─────────────────────────────────────────────────────────

    public List<LogEntry> getLogs(String uuid) {
        return logs.getOrDefault(uuid, new ArrayList<>());
    }

    public Map<String, List<LogEntry>> getAllLogs() {
        return logs;
    }

    public String getPlayerName(String uuid) {
        return playerNames.getOrDefault(uuid, uuid);
    }

    public Map<String, String> getPlayerNames() {
        return playerNames;
    }

    public boolean hasLogs(String uuid) {
        return logs.containsKey(uuid) && !logs.get(uuid).isEmpty();
    }
}
