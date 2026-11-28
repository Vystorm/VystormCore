package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.knownplayers.KnownPlayers;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickEnd;
import Vystorm.vystorm_core.picker.PlayerSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The picker as Core's own 54-slot chest (design 3.5), for players without the client mod whose screen did not open
 * (falling, liquid, recent damage, screens off, no pack). Slots come from {@link PickerChestLayout}; clicks are
 * cancelled and run one tick later. Search closes the chest (Core's own switch, not a Back), asks through
 * {@code dialogs().prompt} (Paper dialog or chat form) and opens the chest again on the Search tab. Heads use the
 * stored skin address ({@code SkullMeta.setOwnerProfile}, Bukkit API, no lookup) or the online player. Server thread.
 * Core-internal.
 */
final class PickerChest {
    private final PickerRuntime rt;
    private final PlayerPickFlow flow;
    private final Map<Integer, PickerChestLayout.Slot> slots = new HashMap<>();
    private Inventory inventory;
    private boolean busy;
    /** Core closes the chest itself (search prompt, reopen): the close is not an end. */
    private boolean switching;

    PickerChest(PickerRuntime rt, PlayerPickFlow flow) {
        this.rt = rt;
        this.flow = flow;
    }

    boolean showing() {
        Player p = flow.viewer;
        try { return inventory != null && Vystorm.vystorm_core.compat.InventoryCompat.top(p.getOpenInventory()) == inventory; }
        catch (RuntimeException | LinkageError unknown) { return inventory != null; }
    }

    boolean open() {
        Player viewer = flow.viewer;
        String title = PickerRuntime.plain(viewer, flow.pick.title());
        if (title.length() > 48) title = title.substring(0, 47) + "…";
        try {
            inventory = Vystorm.vystorm_core.main.getApi().gui().createInventory(rt.core, null, viewer.getUniqueId(), PickerChestLayout.SIZE, title, new GuiCallbacks() {
                @Override public void click(InventoryClickEvent event) { onClick(event); }
                @Override public void close(InventoryCloseEvent event) { onClose(event); }
            });
        } catch (RuntimeException failure) {
            DebugMessages.high("[Pickers] Chest could not be created", failure);
            return false;
        }
        fill();
        switching = true;
        try { viewer.openInventory(inventory); } finally { switching = false; }
        return true;
    }

    void redraw() {
        if (flow.ended || !showing()) return;
        fill();
    }

    private void fill() {
        Player viewer = flow.viewer;
        flow.model.refresh(rt.sources(flow));
        PickerChestLayout.Context ctx = new PickerChestLayout.Context(rt.exactLastSeen(viewer), System.currentTimeMillis(), ZoneId.systemDefault(),
                flow.error, flow.pick.confirmLabel() == null ? null : PickerRuntime.plain(viewer, flow.pick.confirmLabel()));
        List<PickerChestLayout.Slot> layout = PickerChestLayout.layout(flow.model, ctx);
        inventory.clear();
        slots.clear();
        PickerConfig.Heads heads = rt.config().heads();
        for (PickerChestLayout.Slot s : layout) {
            inventory.setItem(s.slot(), item(viewer, s, heads));
            slots.put(s.slot(), s);
        }
    }

    // ------------------------------------------------------------------ items

    static Material material(PickerChestLayout.Icon icon) {
        return switch (icon) {
            case HEAD -> Material.PLAYER_HEAD;
            case CHOSEN -> Material.LIME_CONCRETE;
            case BARRIER -> Material.BARRIER;
            case CLOCK -> Material.CLOCK;
            case COMPASS -> Material.COMPASS;
            case BOOK -> Material.BOOK;
            case NAME_TAG -> Material.NAME_TAG;
            case PAPER -> Material.PAPER;
            case LIME_DYE -> Material.LIME_DYE;
            case ARROW -> Material.ARROW;
        };
    }

    private static ItemStack item(Player viewer, PickerChestLayout.Slot s, PickerConfig.Heads heads) {
        ItemStack item = new ItemStack(material(s.icon()));
        Component name = text(viewer, s.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        List<Component> lore = new ArrayList<>();
        for (PickerText.Line line : s.lore()) lore.add(text(viewer, line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        try {
            Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> {
                Vystorm.vystorm_core.compat.PlatformCompat.displayName(meta, name);
                Vystorm.vystorm_core.compat.PlatformCompat.lore(meta, lore);
                if (s.glint()) {
                    try { meta.setEnchantmentGlintOverride(true); } catch (RuntimeException | LinkageError olderApi) { }
                }
                if (s.row() != null && meta instanceof SkullMeta skull) head(skull, s.row().candidate(), heads);
            });
        } catch (RuntimeException failure) {
            DebugMessages.low("[Pickers] Chest item not styled: " + failure);
        }
        return item;
    }

    /** Skin from the stored address (no lookup), else the online player, else a plain head. */
    static void head(SkullMeta skull, PickerModel.Candidate c, PickerConfig.Heads heads) {
        if (heads == PickerConfig.Heads.NONE) return;
        if (c.skin() != null && (heads == PickerConfig.Heads.ALL || c.online())) {
            try {
                org.bukkit.profile.PlayerProfile profile = Bukkit.createPlayerProfile(c.id(), c.name().length() <= 16 ? c.name() : null);
                org.bukkit.profile.PlayerTextures textures = profile.getTextures();
                textures.setSkin(java.net.URI.create(c.skin()).toURL());
                profile.setTextures(textures);
                skull.setOwnerProfile(profile);
                return;
            } catch (RuntimeException | java.net.MalformedURLException | LinkageError noProfile) {
                // fall through to the online player
            }
        }
        if (c.online()) {
            Player online = Bukkit.getPlayer(c.id());
            if (online != null) {
                try { skull.setOwningPlayer(online); } catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }

    /**
     * 0.71.1: the head a row draws on a Vystorm Screen, by the same rule as the chest's {@link #head}: the stored skin
     * address, else the skin the server already has with an online player's profile ({@code onlineSkin}, no lookup);
     * null = the plain head. Pure.
     */
    static Vystorm.vystorm_core.screen.internal.ScreenHeads.Head screenHead(PickerModel.Candidate c, PickerConfig.Heads heads,
                                                                         java.util.function.Function<java.util.UUID, String> onlineSkin) {
        if (c == null || heads == PickerConfig.Heads.NONE) return null;
        if (c.skin() != null && (heads == PickerConfig.Heads.ALL || c.online())) {
            var head = Vystorm.vystorm_core.screen.internal.ScreenHeads.skin(c.id(), c.name(), c.skin());
            if (head != null) return head;
        }
        if (!c.online() || onlineSkin == null) return null;
        return Vystorm.vystorm_core.screen.internal.ScreenHeads.skin(c.id(), c.name(), onlineSkin.apply(c.id()));
    }

    /** A key line in the viewer's language (placeholder values that are refs or components resolved), or a raw text. */
    static Component text(Player viewer, PickerText.Line line) {
        if (line.key() == null) return PickerRuntime.component(viewer, line.raw());
        Object[] args = line.args().clone();
        for (int i = 1; i < args.length; i += 2) {
            if (args[i] instanceof Component c) args[i] = PickerRuntime.plain(viewer, c);
            else if (args[i] instanceof String s && s.contains("{lang:")) args[i] = PickerRuntime.plain(viewer, s);
        }
        return PickerRuntime.lang().component(viewer, line.key(), args);
    }

    // ------------------------------------------------------------------ clicks

    private void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (busy || flow.ended || event.getClickedInventory() != inventory) return;
        PickerChestLayout.Slot slot = slots.get(event.getRawSlot());
        if (slot == null || slot.action() == PickerChestLayout.Action.NONE) return;
        ClickType type = event.getClick();
        busy = true;
        // the action may open a dialog or another menu: never from inside the click event
        Bukkit.getScheduler().runTask(rt.core, () -> {
            busy = false;
            if (!flow.ended && showing()) run(slot, type);
        });
    }

    private void run(PickerChestLayout.Slot slot, ClickType type) {
        rt.touch(flow);
        PickerModel m = flow.model;
        Player viewer = flow.viewer;
        Lang lang = PickerRuntime.lang();
        switch (slot.action()) {
            case ROW -> {
                PickerModel.Candidate c = slot.row().candidate();
                if (!m.multiple()) {
                    handOver(c);
                    return;
                }
                switch (m.toggle(c.id())) {
                    case FULL -> lang.send(viewer, "pickers.full", "max", m.max());
                    case DENIED -> Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, Component.text(PickerRuntime.plain(viewer, m.verdict(c).reason()), NamedTextColor.RED));
                    default -> { }
                }
                fill();
            }
            case TAB -> {
                m.select(slot.tab());
                fill();
            }
            case SEARCH -> {
                if (type.isRightClick()) {
                    m.query("");
                    m.select(PlayerSource.SEARCH);
                    fill();
                } else {
                    search();
                }
            }
            case DONE -> handOver(null);
            case BACK -> {
                rt.end(flow, PickEnd.BACK, false);
                close();
                rt.returnLater(flow, null);
            }
            case PREVIOUS -> { m.page(m.page() - 1, PickerModel.CHEST_PAGE); fill(); }
            case NEXT -> { m.page(m.page() + 1, PickerModel.CHEST_PAGE); fill(); }
            case NONE -> { }
        }
    }

    private void handOver(PickerModel.Candidate single) {
        Player viewer = flow.viewer;
        Result r = rt.finish(flow, single);
        if (flow.ended) {
            if (rt.flow(viewer) != null) return;   // onPick opened the next picker: it replaced this chest
            close();
            rt.returnLater(flow, r);
            return;
        }
        if (!r.message().isEmpty())
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, Component.text("✖ " + PickerRuntime.plain(viewer, r.message()), NamedTextColor.RED));
        fill();
    }

    private void close() {
        switching = true;
        try { if (showing()) flow.viewer.closeInventory(); } finally { switching = false; }
    }

    /** Search: chest closes (not a Back), the prompt asks, the chest opens again on the Search tab. */
    private void search() {
        Player viewer = flow.viewer;
        close();
        flow.prompting = true;
        Lang lang = PickerRuntime.lang();
        try {
            Vystorm.vystorm_core.main.getApi().dialogs().prompt(rt.core, viewer, lang.ref("pickers.search.title"), lang.ref("pickers.search.question"),
                    flow.model.query(), text -> back(text), () -> back(null), p -> !flow.ended && p.isOnline());
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[Pickers] Search prompt failed", failure);
            back(null);
        }
    }

    /** After the prompt: the search text (null = unchanged) and the chest again, one tick later. */
    private void back(String text) {
        Bukkit.getScheduler().runTask(rt.core, () -> {
            flow.prompting = false;
            if (flow.ended || !flow.viewer.isOnline()) return;
            rt.touch(flow);
            if (text != null) {
                flow.model.query(text);
                flow.model.select(PlayerSource.SEARCH);
            }
            // a managed chest is released when it closes: a new one with the same state
            if (!open()) rt.end(flow, PickEnd.INTERRUPTED, false);
        });
    }

    private void onClose(InventoryCloseEvent event) {
        if (switching || flow.ended || flow.chest != this || event.getInventory() != inventory) return;
        rt.chestClosed(flow, closeReason(event));
    }

    /** Paper's close reason by name (reflection); null on Spigot, which has none. */
    static String closeReason(InventoryCloseEvent event) {
        try {
            Object reason = event.getClass().getMethod("getReason").invoke(event);
            return reason instanceof Enum<?> e ? e.name() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError none) {
            return null;
        }
    }

    /** Only tests: the current slot map. */
    Map<Integer, PickerChestLayout.Slot> slots() { return slots; }

    /** Only tests: whether the chest knows this viewer. */
    UUID viewer() { return flow.viewer.getUniqueId(); }
}
