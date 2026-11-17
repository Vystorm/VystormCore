package Vystorm.vystorm_core.playermenu.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

/**
 * One open player menu: a managed Core inventory ({@code api.gui().createInventory}) with the target's head in the
 * middle of the first row and the entries from the second row on. Clicks run one tick later after re-checking that
 * both players are online and the entry is still visible. Server thread only. Not public API.
 */
public final class PlayerMenuView {
    static final int HEADER_SLOT = 4;
    static final int FIRST_ENTRY_SLOT = 9;

    private final Plugin core;
    private final PlayerMenuRegistry registry;
    private final UUID viewerId;
    private final UUID targetId;
    private final String targetName;
    private final Map<Integer, String> slots = new HashMap<>();
    private Inventory inventory;
    private boolean busy;

    private PlayerMenuView(Plugin core, PlayerMenuRegistry registry, Player viewer, Player target) {
        this.core = core;
        this.registry = registry;
        this.viewerId = viewer.getUniqueId();
        this.targetId = target.getUniqueId();
        this.targetName = target.getName();
    }

    /** Rows needed for the header row plus {@code entries} entries (2..6). */
    static int size(int entries) {
        int rows = 1 + Math.max(1, (Math.min(entries, PlayerMenu.MAX_ENTRIES) + 8) / 9);
        return rows * 9;
    }

    /** Opens the menu with already rendered entries; {@code false} if Core's GUI is not available. */
    public static boolean open(Player viewer, Player target, List<PlayerMenuRegistry.Rendered> entries) {
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || main.getApi() == null) return false;
        return new PlayerMenuView(core, PlayerMenuRegistry.get(), viewer, target).show(viewer, target, entries);
    }

    private boolean show(Player viewer, Player target, List<PlayerMenuRegistry.Rendered> entries) {
        var lang = Localization.get().core();
        String title = lang.text(viewer, "player-menu.title", "player", target.getName());
        Inventory created;
        try {
            created = main.getApi().gui().createInventory(core, null, viewerId, size(entries.size()), title, new GuiCallbacks() {
                @Override public void click(InventoryClickEvent event) { onClick(event); }
            });
        } catch (RuntimeException failure) {
            DebugMessages.high("[PlayerMenu] Menu could not be created", failure);
            return false;
        }
        inventory = created;
        fill(viewer, target, entries);
        viewer.openInventory(inventory);
        return true;
    }

    private void fill(Player viewer, Player target, List<PlayerMenuRegistry.Rendered> entries) {
        inventory.clear();
        slots.clear();
        inventory.setItem(HEADER_SLOT, header(viewer, target));
        int slot = FIRST_ENTRY_SLOT;
        for (PlayerMenuRegistry.Rendered entry : entries) {
            if (slot >= inventory.getSize()) break;
            inventory.setItem(slot, entry.icon());
            slots.put(slot++, entry.key());
        }
    }

    private static ItemStack header(Player viewer, Player target) {
        var lang = Localization.get().core();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        try { Vystorm.vystorm_core.compat.PlatformCompat.editMeta(head, SkullMeta.class, meta -> meta.setOwningPlayer(target)); }
        catch (RuntimeException failure) { DebugMessages.low("[PlayerMenu] Head skin not set: " + failure); }
        lang.name(head, viewer, "player-menu.header", "player", target.getName());
        return lang.lore(head, viewer, "player-menu.header-lore", "player", target.getName());
    }

    private void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (busy || event.getClickedInventory() != inventory) return;
        String key = slots.get(event.getRawSlot());
        if (key == null) return;
        ClickType type = event.getClick();
        busy = true;
        // The handler may open another inventory or dialog: never from inside the click event.
        Bukkit.getScheduler().runTask(core, () -> {
            busy = false;
            run(key, type);
        });
    }

    private void run(String key, ClickType type) {
        var lang = Localization.get().core();
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null || !isOpen(viewer)) return;
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            lang.send(viewer, "player-menu.offline", "player", targetName);
            viewer.closeInventory();
            return;
        }
        var registered = registry.find(key).orElse(null);
        if (registered == null || !registry.visible(registered, viewer, target)) {
            lang.send(viewer, "player-menu.unavailable");
            refresh(viewer, target);
            return;
        }
        var result = registry.click(registered, viewer, target, type);
        if (result.isEmpty()) {
            lang.send(viewer, "player-menu.failed");
            if (isOpen(viewer)) refresh(viewer, target);
            return;
        }
        switch (result.get()) {
            case CLOSE -> { if (isOpen(viewer)) viewer.closeInventory(); }
            case REFRESH -> { if (isOpen(viewer) && target.isOnline()) refresh(viewer, target); }
            case KEEP -> { }
        }
    }

    private void refresh(Player viewer, Player target) {
        List<PlayerMenuRegistry.Rendered> entries = registry.render(viewer, target);
        if (entries.isEmpty()) {
            viewer.closeInventory();
            return;
        }
        if (size(entries.size()) != inventory.getSize()) {
            // A different row count needs a new inventory; this one is released when it closes.
            new PlayerMenuView(core, registry, viewer, target).show(viewer, target, entries);
            return;
        }
        fill(viewer, target, entries);
    }

    private boolean isOpen(Player viewer) {
        try { return viewer.isOnline() && Vystorm.vystorm_core.compat.InventoryCompat.top(viewer.getOpenInventory()) == inventory; }
        catch (RuntimeException failure) { return false; }
    }
}
