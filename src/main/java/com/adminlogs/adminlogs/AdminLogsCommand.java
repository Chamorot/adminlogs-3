package com.adminlogs.adminlogs;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class AdminLogsCommand implements CommandExecutor, TabCompleter {

    private final AdminLogs plugin;

    public AdminLogsCommand(AdminLogs plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // Must be a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        // Sub-command: /adminlogs reload — OP-only, not tied to viewer UUID
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!player.isOp()) {
                player.sendMessage("§cYou must be an operator to reload AdminLogs.");
                return true;
            }
            plugin.reloadConfig();
            plugin.loadViewerUUID();
            player.sendMessage("§a[AdminLogs] Config reloaded. Viewer UUID: §f" + plugin.getViewerUUID());
            return true;
        }

        // Access check: UUID match OR adminlogs.view permission node
        if (!isAuthorised(player)) {
            // Give a more informative message if the config is still at default
            String viewerUUID = plugin.getViewerUUID();
            if (viewerUUID == null || viewerUUID.equals("PASTE-UUID-HERE") || viewerUUID.isBlank()) {
                player.sendMessage("§c[AdminLogs] No viewer UUID has been set in config.yml!");
                player.sendMessage("§7Set 'viewer-uuid' in plugins/AdminLogs/config.yml, then run /adminlogs reload");
            } else {
                player.sendMessage("§cYou do not have permission to use this command.");
            }
            return true;
        }

        if (args.length == 0) {
            // /adminlogs — open the op player list GUI
            plugin.getGuiManager().openPlayerList(player, 0);
        } else {
            // /adminlogs <UUID> — open that player's command log directly
            String targetUUID = args[0].trim();
            if (!isValidUUID(targetUUID)) {
                player.sendMessage("§c[AdminLogs] Invalid UUID format.");
                return true;
            }
            if (!plugin.getDataManager().hasLogs(targetUUID)) {
                player.sendMessage("§c[AdminLogs] No logs found for UUID: " + targetUUID);
                return true;
            }
            plugin.getGuiManager().openCommandLog(player, targetUUID, 0);
        }

        return true;
    }

    /**
     * A player is authorised if their UUID matches the configured viewer UUID
     * OR if they have the {@code adminlogs.view} permission node.
     * This means the permission node in plugin.yml is actually honoured,
     * and server admins can grant access via a permissions plugin without
     * needing to hardcode a single UUID.
     */
    private boolean isAuthorised(Player player) {
        if (player.hasPermission("adminlogs.view")) return true;
        String viewerUUID = plugin.getViewerUUID();
        if (viewerUUID == null || viewerUUID.isBlank() || viewerUUID.equals("PASTE-UUID-HERE")) return false;
        return player.getUniqueId().toString().equals(viewerUUID);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player player)) return completions;
        if (!isAuthorised(player)) return completions;

        if (args.length == 1) {
            String lower = args[0].toLowerCase();
            if ("reload".startsWith(lower) && player.isOp()) {
                completions.add("reload");
            }
            for (String uuid : plugin.getDataManager().getAllLogs().keySet()) {
                if (uuid.toLowerCase().startsWith(lower)) {
                    completions.add(uuid);
                }
            }
        }
        return completions;
    }

    private boolean isValidUUID(String s) {
        try {
            java.util.UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
