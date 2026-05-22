package com.adminlogs.adminlogs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * Manages all chest GUI screens for AdminLogs.
 *
 * <p>Screen 1 — Player List: shows all tracked op players as player heads.
 * Each head is clickable → opens that player's command log.
 * Paginated with Prev / Next buttons at the bottom.
 *
 * <p>Screen 2 — Command Log: shows one paper item per command entry with
 * timestamp in lore. Back button returns to player list. Paginated.
 *
 * <p>Uses the Paper Adventure API (Component / NamedTextColor) for all text
 * so that colour handling is future-proof on 1.21+ Paper servers.
 *
 * <p>openGuis is an <em>instance</em> field (not static) so stale state
 * cannot survive a plugin reload. AdminLogs.onDisable() calls clearAllGuis()
 * before the instance is discarded.
 */
public class GuiManager implements Listener {

    // GUI sizes
    private static final int PLAYER_LIST_SIZE = 54; // 6 rows
    private static final int COMMAND_LOG_SIZE  = 54; // 6 rows

    // Slot reservations in a 54-slot chest
    // Rows 0–4 (45 slots) = content area
    // Row 5 (slots 45–53) = navigation bar
    private static final int SLOT_BACK  = 45;
    private static final int SLOT_PREV  = 47;
    private static final int SLOT_INFO  = 49;
    private static final int SLOT_NEXT  = 51;
    private static final int SLOT_CLOSE = 53;

    // Track open GUI state per player: String[]{ "LIST"|"LOG", targetUUID|null, page }
    // Instance field — cleared on onDisable to prevent stale state across reloads.
    private final Map<UUID, String[]> openGuis = new HashMap<>();

    private final AdminLogs plugin;

    public GuiManager(AdminLogs plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────
    //  Content slots — read from config, default 45
    // ─────────────────────────────────────────────────────────

    /**
     * Returns the number of content slots per page as configured.
     * Clamped to [1, 45] since the GUI has 45 content slots available.
     */
    private int contentSlots() {
        int configured = plugin.getConfig().getInt("commands-per-page", 45);
        return Math.max(1, Math.min(45, configured));
    }

    // ─────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────

    /**
     * Called from AdminLogs.onDisable() to ensure no open GUI state persists
     * after the plugin is unloaded or reloaded.
     */
    public void clearAllGuis() {
        openGuis.clear();
    }

    // ─────────────────────────────────────────────────────────
    //  GUI builders
    // ─────────────────────────────────────────────────────────

    public void openPlayerList(Player player, int page) {
        DataManager dm = plugin.getDataManager();
        Map<String, List<LogEntry>> allLogs = dm.getAllLogs();

        List<String> uuids = new ArrayList<>(allLogs.keySet());
        uuids.removeIf(u -> allLogs.get(u).isEmpty());

        int slots = contentSlots();
        int totalPages = Math.max(1, (int) Math.ceil((double) uuids.size() / slots));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = Component.text("AdminLogs ", NamedTextColor.DARK_RED)
                .append(Component.text("— Admins", NamedTextColor.GRAY));
        Inventory inv = Bukkit.createInventory(null, PLAYER_LIST_SIZE, title);

        int start = page * slots;
        int end   = Math.min(start + slots, uuids.size());
        int slot  = 0;

        for (int i = start; i < end; i++) {
            String uuid  = uuids.get(i);
            String name  = dm.getPlayerName(uuid);
            int    count = allLogs.get(uuid).size();
            inv.setItem(slot++, createPlayerHead(name, uuid, count));
        }

        for (int s = slot; s < slots; s++) {
            inv.setItem(s, makeGlass(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")));
        }

        buildNavBar(inv, page, totalPages, false, null);

        player.openInventory(inv);
        openGuis.put(player.getUniqueId(), new String[]{"LIST", null, String.valueOf(page)});
    }

    public void openCommandLog(Player player, String targetUUID, int page) {
        DataManager dm = plugin.getDataManager();
        List<LogEntry> entries = dm.getLogs(targetUUID);
        String playerName = dm.getPlayerName(targetUUID);

        // Reverse so newest commands are first
        List<LogEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);

        int slots = contentSlots();
        // Use reversed.size() — the list that is actually paginated
        int totalPages = Math.max(1, (int) Math.ceil((double) reversed.size() / slots));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = Component.text(playerName, NamedTextColor.DARK_RED)
                .append(Component.text("'s Commands", NamedTextColor.GRAY));
        Inventory inv = Bukkit.createInventory(null, COMMAND_LOG_SIZE, title);

        int start = page * slots;
        int end   = Math.min(start + slots, reversed.size());
        int slot  = 0;

        for (int i = start; i < end; i++) {
            LogEntry entry = reversed.get(i);
            // globalIndex (original list position) used for the #N label
            int globalIndex = entries.size() - 1 - i;

            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta meta = paper.getItemMeta();

            String displayCmd = entry.getCommand().length() > 40
                    ? entry.getCommand().substring(0, 37) + "..."
                    : entry.getCommand();
            meta.displayName(Component.text("#" + globalIndex + " " + displayCmd, NamedTextColor.GREEN));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Time: ", NamedTextColor.GRAY)
                    .append(Component.text(entry.getTimestamp(), NamedTextColor.WHITE)));
            lore.add(Component.text("Full: ", NamedTextColor.GRAY)
                    .append(Component.text(entry.getCommand(), NamedTextColor.YELLOW)));
            meta.lore(lore);
            paper.setItemMeta(meta);
            inv.setItem(slot++, paper);
        }

        for (int s = slot; s < slots; s++) {
            inv.setItem(s, makeGlass(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")));
        }

        buildNavBar(inv, page, totalPages, true, playerName);

        player.openInventory(inv);
        openGuis.put(player.getUniqueId(), new String[]{"LOG", targetUUID, String.valueOf(page)});
    }

    // ─────────────────────────────────────────────────────────
    //  Inventory click / close handlers
    // ─────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID pUUID = player.getUniqueId();

        if (!openGuis.containsKey(pUUID)) return;

        event.setCancelled(true);

        String[] state      = openGuis.get(pUUID);
        String   guiType    = state[0];
        String   targetUUID = state[1];
        int      page       = Integer.parseInt(state[2]);
        int      rawSlot    = event.getRawSlot();

        // Use the actual inventory size rather than the hardcoded constant so that
        // clicks in the player's own bottom inventory (rawSlot >= top-inv size) are
        // ignored correctly regardless of which GUI screen is open.
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) return;

        if ("LIST".equals(guiType)) {
            handlePlayerListClick(player, rawSlot, page);
        } else if ("LOG".equals(guiType)) {
            handleCommandLogClick(player, rawSlot, page, targetUUID);
        }
    }

    private void handlePlayerListClick(Player player, int slot, int page) {
        DataManager dm = plugin.getDataManager();
        List<String> uuids = new ArrayList<>(dm.getAllLogs().keySet());
        uuids.removeIf(u -> dm.getAllLogs().get(u).isEmpty());

        int slots = contentSlots();
        int totalPages = Math.max(1, (int) Math.ceil((double) uuids.size() / slots));

        if (slot == SLOT_NEXT && page < totalPages - 1) {
            openPlayerList(player, page + 1);
        } else if (slot == SLOT_PREV && page > 0) {
            openPlayerList(player, page - 1);
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        } else if (slot < slots) {
            int index = page * slots + slot;
            if (index < uuids.size()) {
                openCommandLog(player, uuids.get(index), 0);
            }
        }
    }

    private void handleCommandLogClick(Player player, int slot, int page, String targetUUID) {
        DataManager dm = plugin.getDataManager();
        // Use the reversed size for page calculation (same as openCommandLog)
        int size = dm.getLogs(targetUUID).size();
        int slots = contentSlots();
        int totalPages = Math.max(1, (int) Math.ceil((double) size / slots));

        if (slot == SLOT_BACK) {
            openPlayerList(player, 0);
        } else if (slot == SLOT_NEXT && page < totalPages - 1) {
            openCommandLog(player, targetUUID, page + 1);
        } else if (slot == SLOT_PREV && page > 0) {
            openCommandLog(player, targetUUID, page - 1);
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            openGuis.remove(event.getPlayer().getUniqueId());
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Helper builders
    // ─────────────────────────────────────────────────────────

    private void buildNavBar(Inventory inv, int page, int totalPages,
                             boolean showBack, String playerName) {
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, makeGlass(Material.BLACK_STAINED_GLASS_PANE, Component.text(" ")));
        }

        if (showBack) {
            ItemStack back = new ItemStack(Material.ARROW);
            ItemMeta m = back.getItemMeta();
            m.displayName(Component.text("← Back to Player List", NamedTextColor.YELLOW));
            back.setItemMeta(m);
            inv.setItem(SLOT_BACK, back);
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
            ItemMeta m = prev.getItemMeta();
            m.displayName(Component.text("◀ Previous Page", NamedTextColor.GREEN));
            m.lore(Collections.singletonList(
                    Component.text("Page " + page + " of " + totalPages, NamedTextColor.GRAY)));
            prev.setItemMeta(m);
            inv.setItem(SLOT_PREV, prev);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        im.displayName(Component.text("Page " + (page + 1) + " / " + totalPages, NamedTextColor.GOLD));
        if (showBack && playerName != null) {
            im.lore(Collections.singletonList(
                    Component.text("Viewing: ", NamedTextColor.GRAY)
                            .append(Component.text(playerName, NamedTextColor.WHITE))));
        }
        info.setItemMeta(im);
        inv.setItem(SLOT_INFO, info);

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
            ItemMeta m = next.getItemMeta();
            m.displayName(Component.text("Next Page ▶", NamedTextColor.GREEN));
            m.lore(Collections.singletonList(
                    Component.text("Page " + (page + 2) + " of " + totalPages, NamedTextColor.GRAY)));
            next.setItemMeta(m);
            inv.setItem(SLOT_NEXT, next);
        }

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.displayName(Component.text("✖ Close", NamedTextColor.RED));
        close.setItemMeta(cm);
        inv.setItem(SLOT_CLOSE, close);
    }

    // setOwnerProfile / createPlayerProfile is the modern API (added 1.18).
    private static ItemStack createPlayerHead(String name, String uuid, int commandCount) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwnerProfile(Bukkit.createPlayerProfile(UUID.fromString(uuid), name));
        meta.displayName(Component.text(name, NamedTextColor.AQUA));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("UUID: ", NamedTextColor.GRAY)
                .append(Component.text(uuid, NamedTextColor.DARK_GRAY)));
        lore.add(Component.text("Commands logged: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(commandCount), NamedTextColor.WHITE)));
        lore.add(Component.empty());
        lore.add(Component.text("Click to view command history", NamedTextColor.YELLOW));
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack makeGlass(Material mat, Component name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        item.setItemMeta(meta);
        return item;
    }
}
