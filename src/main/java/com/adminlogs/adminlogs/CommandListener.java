package com.adminlogs.adminlogs;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class CommandListener implements Listener {

    private final AdminLogs plugin;

    public CommandListener(AdminLogs plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        // Only log operators
        if (!player.isOp()) return;

        String uuid       = player.getUniqueId().toString();
        String viewerUUID = plugin.getViewerUUID();

        // Neither the designated viewer UUID nor anyone with the adminlogs.view
        // permission node is logged. Both groups are auditors, not audit subjects.
        // This keeps permission-plugin-granted viewers consistent with the UUID viewer.
        // If you want viewer commands captured, remove or comment out this check.
        if (uuid.equals(viewerUUID) || player.hasPermission("adminlogs.view")) return;

        // Do NOT log the /adminlogs command itself (avoids noise in the log).
        // Also guard the plugin-namespaced form /adminlogs:adminlogs which Bukkit
        // registers automatically and would bypass a plain startsWith check.
        String rawCommand = event.getMessage().trim();
        String lowerCmd = rawCommand.toLowerCase();
        if (lowerCmd.startsWith("/adminlogs") || lowerCmd.startsWith("/adminlogs:adminlogs")) return;

        plugin.getDataManager().logCommand(uuid, player.getName(), rawCommand);
    }
}
