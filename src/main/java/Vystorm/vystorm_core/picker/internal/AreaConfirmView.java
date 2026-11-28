package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.picker.AreaHeight;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The confirm view of an area pick as Core's own 27-slot chest ({@link AreaConfirmLayout}); for pack players it is
 * mirrored as a Vystorm Screen like every Core chest (mirrors redraw when items change, so the protection answer shows
 * up without reopening). Clicks are cancelled and run one tick later with the view still open: "Use this area" keeps it
 * open with the reason when it is refused, "Full height" redraws it in place, the redo buttons and Cancel close it.
 * Closing it any other way (ESC, sneak on the screen) keeps both corners. Server thread. Core-internal.
 */
final class AreaConfirmView {
    private final WorldPickRuntime rt;
    private final AreaPickRuntime area;
    private final WorldPickFlow flow;
    private final Map<Integer, AreaConfirmLayout.Slot> slots = new HashMap<>();
    private Inventory inventory;
    private boolean busy;
    /** Core closes the chest itself: the close is handled by the action. */
    private boolean switching;

    AreaConfirmView(WorldPickRuntime rt, AreaPickRuntime area, WorldPickFlow flow) {
        this.rt = rt;
        this.area = area;
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
            inventory = Vystorm.vystorm_core.main.getApi().gui().createInventory(rt.core, null, viewer.getUniqueId(), AreaConfirmLayout.SIZE, title, new GuiCallbacks() {
                @Override public void click(InventoryClickEvent event) { onClick(event); }
                @Override public void close(InventoryCloseEvent event) { onClose(event); }
                @Override public void discarded(Inventory gone) {
                    if (switching || flow.ended || flow.confirm != AreaConfirmView.this) return;
                    Bukkit.getScheduler().runTask(rt.core, () -> area.confirmClosed(flow));
                }
            });
        } catch (RuntimeException failure) {
            DebugMessages.high("[Pickers] Area confirm view could not be created", failure);
            return false;
        }
        render();
        switching = true;
        try { viewer.openInventory(inventory); } finally { switching = false; }
        return showing();
    }

    /** Draws every button from the flow's state (the summary, whether "Use this area" is ready, the height switch). */
    void render() {
        if (inventory == null) return;
        Player viewer = flow.viewer;
        Lang lang = PickerRuntime.lang();
        boolean canExpand = area.canExpand(flow);
        List<Component> summary = area.summary(flow);
        AreaPickRuntime.Status st = area.status(flow);
        slots.clear();
        for (AreaConfirmLayout.Slot s : AreaConfirmLayout.layout(canExpand)) {
            String key = "pickers.world.area.confirm." + s.key();
            Component name;
            List<Component> lore;
            Material material = WorldPickOptions.material(s.icon());
            switch (s.action()) {
                // a mirrored screen shows names only: the size and the block count are tile names, the lore keeps the rest
                case INFO -> { name = flow.box == null ? PickerRuntime.component(viewer, flow.pick.title()) : Component.text(AreaLimits.size(flow.box), NamedTextColor.WHITE); lore = summary; }
                case BLOCKS -> { name = lang.component(viewer, key, "blocks", flow.box == null ? "?" : AreaLimits.count(flow.box.volume())); lore = summary; }
                case USE -> {
                    name = lang.component(viewer, key);
                    if (st.ready()) lore = List.of(lang.component(viewer, key + "-hint"));
                    else {
                        material = Material.GRAY_DYE;
                        lore = List.of(Component.text(st.text(), st.checking() ? NamedTextColor.YELLOW : NamedTextColor.RED));
                    }
                }
                case HEIGHT -> {
                    boolean on = flow.area.expanded();
                    name = lang.component(viewer, on ? key + "-off" : key);
                    lore = List.of(lang.component(viewer, on ? key + "-off-hint" : key + "-hint"));
                }
                default -> { name = lang.component(viewer, key); lore = List.of(lang.component(viewer, key + "-hint")); }
            }
            inventory.setItem(s.slot(), item(material, name, lore));
            slots.put(s.slot(), s);
        }
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        try {
            Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> {
                Vystorm.vystorm_core.compat.PlatformCompat.displayName(meta, name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                Vystorm.vystorm_core.compat.PlatformCompat.lore(meta, lore.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            });
        } catch (RuntimeException failure) {
            DebugMessages.low("[Pickers] Confirm item not styled: " + failure);
        }
        return item;
    }

    private void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (busy || flow.ended || event.getClickedInventory() != inventory) return;
        AreaConfirmLayout.Slot slot = slots.get(event.getRawSlot());
        if (slot == null || slot.action() == AreaConfirmLayout.Action.INFO || slot.action() == AreaConfirmLayout.Action.BLOCKS) return;
        busy = true;
        // the action may open the calling menu or end the pick: never from inside the click event
        Bukkit.getScheduler().runTask(rt.core, () -> {
            busy = false;
            if (flow.ended || flow.confirm != this || !showing()) return;
            area.confirmAction(flow, slot.action());
        });
    }

    void close() {
        switching = true;
        try { if (showing()) flow.viewer.closeInventory(); } finally { switching = false; }
    }

    private void onClose(InventoryCloseEvent event) {
        if (switching || flow.ended || flow.confirm != this || event.getInventory() != inventory) return;
        Bukkit.getScheduler().runTask(rt.core, () -> { if (flow.confirm == this) area.confirmClosed(flow); });
    }
}
