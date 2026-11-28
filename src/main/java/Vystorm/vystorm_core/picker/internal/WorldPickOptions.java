package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Lang;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The options menu of a world pick as Core's own 27-slot chest ({@link WorldOptionsLayout}); for players with Core's
 * pack it is mirrored as a Vystorm Screen like every Core chest. Clicks are cancelled and run one tick later; closing
 * the chest any other way (ESC, sneak on the screen, damage) goes back to choosing. Server thread. Core-internal.
 */
final class WorldPickOptions {
    private final WorldPickRuntime rt;
    private final WorldPickFlow flow;
    private final Map<Integer, WorldOptionsLayout.Slot> slots = new HashMap<>();
    private Inventory inventory;
    private boolean busy;
    /** Core closes the chest itself: the close is handled by the action, not as "back to choosing". */
    private boolean switching;

    WorldPickOptions(WorldPickRuntime rt, WorldPickFlow flow) {
        this.rt = rt;
        this.flow = flow;
    }

    boolean showing() {
        try { return inventory != null && Vystorm.vystorm_core.compat.InventoryCompat.top(flow.viewer.getOpenInventory()) == inventory; }
        catch (RuntimeException | LinkageError unknown) { return inventory != null; }
    }

    boolean open() {
        Player viewer = flow.viewer;
        String title = PickerRuntime.plain(viewer, flow.pick.title());
        if (title.length() > 48) title = title.substring(0, 47) + "…";
        try {
            inventory = Vystorm.vystorm_core.main.getApi().gui().createInventory(rt.core, null, viewer.getUniqueId(), WorldOptionsLayout.SIZE, title, new GuiCallbacks() {
                @Override public void click(InventoryClickEvent event) { onClick(event); }
                @Override public void close(InventoryCloseEvent event) { onClose(event); }
                // the open was refused (another plugin cancelled it) or the chest was released: back to choosing
                @Override public void discarded(Inventory gone) {
                    if (switching || flow.ended || flow.options != WorldPickOptions.this) return;
                    Bukkit.getScheduler().runTask(rt.core, () -> rt.option(flow, WorldOptionsLayout.Action.RESUME));
                }
            });
        } catch (RuntimeException failure) {
            DebugMessages.high("[Pickers] World pick options could not be created", failure);
            return false;
        }
        Lang lang = PickerRuntime.lang();
        boolean redo = flow.area != null && flow.area.step() == AreaSteps.Step.SECOND;
        for (WorldOptionsLayout.Slot s : WorldOptionsLayout.layout(flow.pick.kind(), redo)) {
            List<Component> lore = s.action() == WorldOptionsLayout.Action.INFO
                    ? List.of(lang.component(viewer, "pickers.world.options.info-kind", "kind", lang.plain(viewer, "pickers.world.kind." + flow.pick.kind().name().toLowerCase(Locale.ROOT))),
                              lang.component(viewer, "pickers.world.options.info-reach", "reach", flow.reach))
                    : List.of(lang.component(viewer, "pickers.world.options." + s.key() + "-hint"));
            Component name = s.action() == WorldOptionsLayout.Action.INFO ? PickerRuntime.component(viewer, flow.pick.title()) : lang.component(viewer, "pickers.world.options." + s.key());
            inventory.setItem(s.slot(), item(material(s.icon()), name, lore));
            slots.put(s.slot(), s);
        }
        switching = true;
        try { viewer.openInventory(inventory); } finally { switching = false; }
        return showing();   // false when another plugin refused the open: the caller goes back to choosing
    }

    static Material material(WorldOptionsLayout.Icon icon) {
        return switch (icon) {
            case PAPER -> Material.PAPER;
            case COMPASS -> Material.COMPASS;
            case WRITABLE_BOOK -> Material.WRITABLE_BOOK;
            case SPYGLASS -> Material.SPYGLASS;
            case BARRIER -> Material.BARRIER;
            case ARROW -> Material.ARROW;
            case SPECTRAL_ARROW -> Material.SPECTRAL_ARROW;
            case LADDER -> Material.LADDER;
            case LIME_DYE -> Material.LIME_DYE;
            case GRAY_DYE -> Material.GRAY_DYE;
            case MAP -> Material.MAP;
        };
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        try {
            Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> {
                Vystorm.vystorm_core.compat.PlatformCompat.displayName(meta, name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                Vystorm.vystorm_core.compat.PlatformCompat.lore(meta, lore.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            });
        } catch (RuntimeException failure) {
            DebugMessages.low("[Pickers] Options item not styled: " + failure);
        }
        return item;
    }

    private void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (busy || flow.ended || event.getClickedInventory() != inventory) return;
        WorldOptionsLayout.Slot slot = slots.get(event.getRawSlot());
        if (slot == null || slot.action() == WorldOptionsLayout.Action.INFO) return;
        busy = true;
        // the action may open a dialog or the calling menu: never from inside the click event
        Bukkit.getScheduler().runTask(rt.core, () -> {
            busy = false;
            if (flow.ended || flow.options != this || !showing()) return;
            close();
            rt.option(flow, slot.action());
        });
    }

    void close() {
        switching = true;
        try { if (showing()) flow.viewer.closeInventory(); } finally { switching = false; }
    }

    private void onClose(InventoryCloseEvent event) {
        if (switching || flow.ended || flow.options != this || event.getInventory() != inventory) return;
        // ESC, sneak on the mirrored screen, damage, another menu: back to choosing (another menu is seen there)
        Bukkit.getScheduler().runTask(rt.core, () -> rt.option(flow, WorldOptionsLayout.Action.RESUME));
    }
}
