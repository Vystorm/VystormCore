package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.Screens;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Chest menus of Vystorm plugins as Vystorm Screens (since 0.58.0, config {@code screens.inventories}). When a plugin
 * that depends on Core opens a chest, hopper or dispenser menu, the menu opens on the server as usual, but its window
 * packet is kept from the client ({@link ScreenPackets}) and the same inventory is shown as a screen instead. So for the
 * plugin the menu is really open: {@code getOpenInventory()} checks pass and closing sends the normal close event. A
 * click on a tile is handed to the plugin as an {@link InventoryClickEvent} on the open view; when it opens the next
 * menu, the screen follows. Menus where items really move (the plugin does not cancel the click) switch to the real
 * chest. Plugins in {@code screens.inventories-exclude} keep their chests. Server thread only.
 */
public final class InventoryScreens implements Listener {
    private static final Set<InventoryType> TYPES = Set.of(InventoryType.CHEST, InventoryType.HOPPER, InventoryType.DISPENSER,
            InventoryType.DROPPER, InventoryType.BARREL, InventoryType.SHULKER_BOX);

    private final Plugin core;
    private final ScreenPackets packets = new ScreenPackets();
    private final Map<UUID, Mirror> mirrors = new HashMap<>();
    /** Players whose next open is the real chest (fallback), so it is not caught again. */
    private final Set<UUID> passThrough = new HashSet<>();

    private final class Mirror {
        final Player player;
        Inventory top;
        Plugin owner;
        ScreenSession screen;
        InventoryScreenLayout.Result result;
        int hash;
        boolean leaving;
        Mirror(Player player) { this.player = player; }
    }

    public InventoryScreens(Plugin core) {
        this.core = core;
        Bukkit.getScheduler().runTaskTimer(core, this::tick, 10L, 10L);
    }

    private boolean enabled() { return core.getConfig().getBoolean("screens.inventories", true); }

    /** Inventories that always open as the real chest (0.61.0); weak, so closed menus are forgotten. */
    private static final java.util.Set<Inventory> REAL = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** A plugin's menu that needs item transfer: never shown as a screen. Call before opening it. */
    public static void keepAsChest(Inventory inventory) { if (inventory != null) synchronized (REAL) { REAL.add(inventory); } }

    static boolean keptAsChest(Inventory inventory) { synchronized (REAL) { return REAL.contains(inventory); } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !enabled()) return;
        if (passThrough.remove(player.getUniqueId())) return;
        Inventory top = event.getView().getTopInventory();
        Mirror current = mirrors.get(player.getUniqueId());
        boolean menu = TYPES.contains(top.getType()) && !(top.getHolder() instanceof org.bukkit.block.BlockState)
                && !(top.getHolder() instanceof org.bukkit.entity.Entity);   // real containers in the world stay chests
        if (menu && keptAsChest(top)) menu = false;   // the plugin asked for the real chest (items are moved in it)
        Plugin owner = menu ? opener() : null;
        if (owner == null || !mirrorable(owner)) {
            if (current != null) leave(current, false);   // something else opens (a real chest): the screen gives way
            return;
        }
        if (current != null && current.screen != null && current.screen.isOpen()) {
            current.top = top;   // the plugin opened its next menu: follow it
            current.owner = owner;
            Bukkit.getScheduler().runTask(core, () -> refresh(current, true));
            return;
        }
        if (!packets.hide(player)) return;   // no packet hook: the chest opens normally
        Mirror mirror = new Mirror(player);
        mirror.top = top;
        mirror.owner = owner;
        mirrors.put(player.getUniqueId(), mirror);
        // the screen opens one tick later, after the server finished opening the menu
        Bukkit.getScheduler().runTask(core, () -> start(mirror));
    }

    private void start(Mirror mirror) {
        Player player = mirror.player;
        if (!player.isOnline() || mirrors.get(player.getUniqueId()) != mirror || !isOpen(mirror)) { forget(mirror); return; }
        mirror.result = layout(mirror);
        mirror.hash = hash(mirror.top);
        ScreenSpec spec = ScreenSpec.builder("inventory:" + mirror.owner.getName(), mirror.result.layout())
                .backdrop(ScreenSpec.Backdrop.BLACK)
                .onInput((session, input) -> { mirror.screen = session; handle(mirror, input); })
                .build();
        if (!Screens.of(core).open(player, spec).opened()) {
            forget(mirror);
            reopenReal(player, mirror.top);   // no screen possible: show the real chest
        }
    }

    /** The first plugin on the call stack that opened the inventory (not the server, not this class). */
    static Plugin opener() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c != InventoryScreens.class)
                .map(InventoryScreens::plugin)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null));
    }

    private static Plugin plugin(Class<?> type) {
        try { return JavaPlugin.getProvidingPlugin(type); } catch (IllegalArgumentException | IllegalStateException | ClassCastException notPlugin) { return null; }
    }

    /** Core itself and every plugin that depends on Core, minus the configured exclusions. */
    private boolean mirrorable(Plugin owner) {
        List<String> exclude = core.getConfig().getStringList("screens.inventories-exclude");
        if (exclude.stream().anyMatch(name -> name.equalsIgnoreCase(owner.getName()))) return false;
        if (owner == core) return true;
        var description = owner.getDescription();
        return description.getDepend().stream().anyMatch(d -> d.equalsIgnoreCase(core.getName()))
                || description.getSoftDepend().stream().anyMatch(d -> d.equalsIgnoreCase(core.getName()));
    }

    private void handle(Mirror mirror, ScreenInput input) {
        switch (input) {
            case ScreenInput.Click click -> {
                if (InventoryScreenLayout.CLOSE.equals(click.widgetId())) { mirror.screen.close(); return; }
                // 0.61.0: the player asks for the real chest (to put items in, shift-click, drop)
                if (InventoryScreenLayout.CHEST.equals(click.widgetId())) { if (isOpen(mirror)) leave(mirror, true); else mirror.screen.close(); return; }
                int slot = InventoryScreenLayout.slotOf(click.widgetId());
                if (slot >= 0) click(mirror, slot, click.right());
            }
            case ScreenInput.Hover hover -> {
                if (mirror.result == null || mirror.screen == null) return;
                Component hint = hover.widgetId() == null ? null : mirror.result.hints().get(hover.widgetId());
                mirror.screen.setText(InventoryScreenLayout.HINT, hint != null ? hint : mirror.result.hint());
            }
            case ScreenInput.Closed closed -> {
                boolean wasLeaving = mirror.leaving;
                forget(mirror);
                // the player left the screen: close the menu on the server too (the plugin gets its close event)
                if (!wasLeaving && mirror.player.isOnline() && isOpen(mirror)) mirror.player.closeInventory();
            }
            default -> { }
        }
    }

    /** Hands the click to the plugin like a real chest click; an uncancelled click means items move: use the real chest. */
    private void click(Mirror mirror, int slot, boolean right) {
        if (!isOpen(mirror)) { mirror.screen.close(); return; }
        InventoryView view = mirror.player.getOpenInventory();
        ItemStack item = mirror.top.getItem(slot);
        InventoryAction action = item == null || item.getType().isAir() ? InventoryAction.NOTHING
                : right ? InventoryAction.PICKUP_HALF : InventoryAction.PICKUP_ALL;
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, right ? ClickType.RIGHT : ClickType.LEFT, action);
        Inventory before = mirror.top;
        Bukkit.getPluginManager().callEvent(event);
        if (mirror.screen == null || !mirror.screen.isOpen()) return;
        if (!event.isCancelled() && action != InventoryAction.NOTHING && mirror.top == before && isOpen(mirror)) {
            leave(mirror, true);
            return;
        }
        // the plugin may have closed the menu or opened the next one; look again next tick
        Bukkit.getScheduler().runTask(core, () -> {
            if (mirror.screen == null || !mirror.screen.isOpen()) return;
            if (!isOpen(mirror)) mirror.screen.close();
            else refresh(mirror, false);
        });
    }

    /** Leaves the screen; {@code realChest}: show the menu as the real chest instead. */
    private void leave(Mirror mirror, boolean realChest) {
        mirror.leaving = true;
        packets.show(mirror.player);
        Inventory top = mirror.top;
        if (mirror.screen != null && mirror.screen.isOpen()) mirror.screen.close();
        forget(mirror);
        if (realChest) reopenReal(mirror.player, top);
    }

    private void reopenReal(Player player, Inventory top) {
        packets.show(player);
        passThrough.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(core, () -> {
            if (player.isOnline()) player.openInventory(top);
            passThrough.remove(player.getUniqueId());
        });
    }

    private void forget(Mirror mirror) {
        if (mirrors.get(mirror.player.getUniqueId()) == mirror) mirrors.remove(mirror.player.getUniqueId());
        packets.show(mirror.player);
    }

    /** The plugin closed its menu (for example before teleporting the player): close the screen too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Mirror mirror = mirrors.get(event.getPlayer().getUniqueId());
        if (mirror == null || mirror.leaving) return;
        Bukkit.getScheduler().runTask(core, () -> {
            if (mirrors.get(mirror.player.getUniqueId()) != mirror) return;
            if (!isOpen(mirror) && !openMenu(mirror.player)) {
                if (mirror.screen != null && mirror.screen.isOpen()) { mirror.leaving = true; mirror.screen.close(); }
                forget(mirror);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Mirror mirror = mirrors.remove(event.getPlayer().getUniqueId());
        if (mirror != null) packets.show(event.getPlayer());
        passThrough.remove(event.getPlayer().getUniqueId());
    }

    private boolean isOpen(Mirror mirror) {
        try { return mirror.player.getOpenInventory().getTopInventory() == mirror.top; } catch (RuntimeException failure) { return false; }
    }

    private static boolean openMenu(Player player) {
        try { return TYPES.contains(player.getOpenInventory().getTopInventory().getType()); } catch (RuntimeException failure) { return false; }
    }

    /** Menus that change their items over time (timers, live prices) are redrawn twice a second when something changed. */
    private void tick() {
        for (Mirror mirror : new ArrayList<>(mirrors.values())) {
            if (mirror.screen == null || !mirror.screen.isOpen()) continue;
            if (hash(mirror.top) != mirror.hash) refresh(mirror, false);
        }
    }

    private void refresh(Mirror mirror, boolean always) {
        int now = hash(mirror.top);
        if (!always && now == mirror.hash && mirror.result != null) return;
        mirror.hash = now;
        mirror.result = layout(mirror);
        if (mirror.screen != null && mirror.screen.isOpen()) mirror.screen.setLayout(mirror.result.layout());
    }

    private static int hash(Inventory top) { return Arrays.hashCode(top.getContents()); }

    private InventoryScreenLayout.Result layout(Mirror mirror) {
        Player player = mirror.player;
        ItemStack[] contents = mirror.top.getContents();
        List<InventoryScreenLayout.Slot> slots = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) slots.add(slot(i, contents[i]));
        int columns = switch (mirror.top.getType()) { case HOPPER -> 5; case DISPENSER, DROPPER -> 3; default -> 9; };
        ScreenProfile profile = Screens.of(core).profile(player);
        var lang = Localization.get().core();
        return InventoryScreenLayout.layout(title(player), slots, columns, profile, lang.component(player, "menu.screen.close"),
                lang.component(player, "screen.inventory.hint"), lang.component(player, "screen.inventory.chest"), lang.component(player, "screen.inventory.chest-hint"));
    }

    private static Component title(Player player) {
        InventoryView view = player.getOpenInventory();
        try { return view.title(); } catch (LinkageError olderApi) { return Component.text(view.getTitle()); }
    }

    static InventoryScreenLayout.Slot slot(int index, ItemStack item) {
        if (item == null || item.getType().isAir()) return new InventoryScreenLayout.Slot(index, null, Component.empty(), List.of(), 0, false);
        ItemMeta meta = item.getItemMeta();
        Component name = meta == null ? null : PlatformCompat.displayName(meta);
        List<Component> lore = meta == null ? null : PlatformCompat.lore(meta);
        boolean filler = isPane(item.getType()) && (name == null || InventoryScreenLayout.plain(name).isBlank());
        if (name == null) name = Component.translatable(PlatformCompat.translationKey(item.getType()));
        return new InventoryScreenLayout.Slot(index, item, name, lore, item.getAmount(), filler);
    }

    static boolean isPane(Material type) { return type == Material.GLASS_PANE || type.name().endsWith("_STAINED_GLASS_PANE"); }
}
