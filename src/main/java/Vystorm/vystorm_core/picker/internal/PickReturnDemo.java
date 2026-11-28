package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import Vystorm.vystorm_core.panel.internal.PanelOpener;
import Vystorm.vystorm_core.panel.internal.PanelRegistry;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickProtection;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPick;
import Vystorm.vystorm_core.picker.WorldPick;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /vadmin pick demo [arg]} (0.71.0): a small calling panel for admins that starts each pick with
 * {@link PickReturn#panel(PanelAction)} and shows, after the return, the same argument, the session counter and the
 * result as its status line and in a "last result" row, so a lost state is visible at once. Its chest fallback (no
 * screen: Spigot, Paper 1.20.6, {@code screens.panels: false}, or for a moment in water) is a context fallback
 * ({@link FallbackContext}): it shows the same argument, the same session counter and the returning pick's result, and
 * starts its picks with {@link PickReturn#panel(FallbackContext)}, so the counter is the same on both routes and a
 * return opens the screen again when it can. Nothing in the world changes. Core-internal.
 */
final class PickReturnDemo {
    static final String PANEL = "pick-demo";
    static final String COUNT = "count";
    static final String LAST = "last";
    static final String PERMISSION = "vystorm_core.admin";

    /** Chest slots (27). */
    static final int SLOT_ARG = 0, SLOT_COUNT = 4, SLOT_ADD = 6, SLOT_STATUS = 8, SLOT_LAST = 13, SLOT_PLAYER = 19, SLOT_PLAYERS = 21, SLOT_BLOCK = 23, SLOT_AREA = 25;

    private final PickerRuntime rt;

    /** What the chest shows: all of it from the panel's own context (no state of the chest's own). */
    record ChestView(String arg, int count, Object last, List<String> status) {
        static ChestView of(Player viewer, FallbackContext ctx, Result status) {
            return new ChestView(ctx.arg(), PickReturnDemo.count(ctx.session()), ctx.session().get(LAST), PanelOpener.status(viewer, status));
        }
    }

    PickReturnDemo(PickerRuntime rt) { this.rt = rt; }

    void register() {
        Panels.of(rt.core).register(PANEL, PanelSpec.builder(lang().ref("pickers.return-demo.title"))
                .access(p -> p.hasPermission(PERMISSION))
                .render(this::render)
                .contextFallback(this::openChest)
                .build());
    }

    static Lang lang() { return Localization.get().core(); }

    /** In game: opens the panel by the normal route (screen, client mod or the chest fallback). */
    void open(Player player, String arg) {
        PanelRegistry.Registered reg = PanelRegistry.find(Panels.of(rt.core).id(PANEL)).orElse(null);
        PanelOpenResult result = reg == null ? PanelOpenResult.NONE : PanelOpener.open(player, reg, arg);
        DebugMessages.low("[Pickers] Return demo for " + player.getName() + " opened as " + result);
    }

    // ------------------------------------------------------------------ the panel

    Panel render(PanelContext ctx) {
        return build(ctx.player(), ctx.arg(), count(ctx.session()), ctx.session().get(LAST), new Handlers(rt));
    }

    static int count(PanelSession s) {
        Object v = s == null ? null : s.get(COUNT);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** What the buttons do; separate so tests can render without a runtime. */
    record Handlers(PickerRuntime rt) {
        Result add(PanelAction a) {
            a.session().put(COUNT, count(a.session()) + 1);
            a.refresh();
            return Result.ok();
        }

        Result players(PanelAction a, boolean multi) {
            return rt.players(a, playerPick(multi, (viewer, text) -> { }));
        }

        Result world(PanelAction a, PickKind kind) {
            return rt.world(a, worldPick(kind, (viewer, text) -> { }));
        }
    }

    static Panel build(Player viewer, String arg, int count, Object last, Handlers h) {
        Lang lang = lang();
        Panel panel = Panel.page(lang.component(viewer, "pickers.return-demo.title")).size(Panel.Size.LARGE)
                .subtitle(lang.component(viewer, "pickers.return-demo.subtitle"));
        panel.child(Ui.text(lang.component(viewer, "pickers.return-demo.arg", "arg", arg == null || arg.isEmpty() ? "-" : arg)).style(Ui.TextStyle.MUTED));
        panel.child(Ui.row().gap(4).wrap()
                .child(Ui.text(lang.component(viewer, "pickers.return-demo.counter", "count", count)))
                .child(Ui.button("add").label(lang.component(viewer, "pickers.return-demo.add")).ghost().onClick(h::add)));
        panel.child(Ui.text(lang.component(viewer, "pickers.return-demo.last", "result", lastText(viewer, last))));
        panel.child(Ui.row().gap(4).wrap()
                .child(Ui.button("pick-player").label(lang.component(viewer, "pickers.return-demo.pick-player")).primary().onClick(a -> h.players(a, false)))
                .child(Ui.button("pick-players").label(lang.component(viewer, "pickers.return-demo.pick-players")).onClick(a -> h.players(a, true)))
                .child(Ui.button("pick-block").label(lang.component(viewer, "pickers.return-demo.pick-block")).onClick(a -> h.world(a, PickKind.BLOCK)))
                .child(Ui.button("pick-area").label(lang.component(viewer, "pickers.return-demo.pick-area")).onClick(a -> h.world(a, PickKind.AREA))));
        return panel;
    }

    static String lastText(Player viewer, Object last) {
        return last == null || last.toString().isEmpty() ? lang().plain(viewer, "pickers.return-demo.none") : last.toString();
    }

    // ------------------------------------------------------------------ the picks (shared by panel and chest)

    /**
     * A player pick whose handler writes its line into the carried session ({@code last}) and hands it to {@code sink}
     * too; the line is also the handler's message, so it becomes the reopened panel's status line.
     */
    static PlayerPick playerPick(boolean multi, BiConsumer<Player, String> sink) {
        Lang lang = lang();
        PlayerPick.Builder b = PlayerPick.builder(lang.ref("pickers.return-demo.title")).subtitle(lang.ref("pickers.return-demo.pick-subtitle"))
                .onPick((viewer, picks) -> {
                    String text = lang.plain(viewer, "pickers.demo.picked", "players",
                            picks.chosen().stream().map(PickedPlayer::name).collect(Collectors.joining(", ")),
                            "added", picks.added().size(), "removed", picks.removed().size());
                    picks.session().put(LAST, text);
                    sink.accept(viewer, text);
                    return Result.ok(text);
                });
        if (multi) b.multiple(10);
        return b.build();
    }

    /** A block or area pick with BUILD (so a protected place shows red or yellow), the same handler as above. */
    static WorldPick worldPick(PickKind kind, BiConsumer<Player, String> sink) {
        Lang lang = lang();
        return WorldPick.builder(lang.ref("pickers.return-demo.title")).subtitle(lang.ref("pickers.return-demo.pick-subtitle"))
                .target(kind).protection(PickProtection.BUILD)
                .onPick((viewer, c) -> {
                    String text = lang.plain(viewer, "pickers.world.demo.picked", "target", WorldPickAdmin.describe(viewer, c.target()));
                    c.session().put(LAST, text);
                    sink.accept(viewer, text);
                    return Result.ok(text);
                }).build();
    }

    // ------------------------------------------------------------------ the chest fallback

    /** The panel's context fallback: the same arg and session as the screen, and the returning pick's result. */
    void openChest(Player player, FallbackContext ctx) {
        Result status = ctx.result().orElse(null);
        // the status slot shows it: no extra chat line, but only once the chest is really open
        if (drawChest(player, ctx, status) && status != null) ctx.resultShown();
    }

    /** Draws the chest from {@code ctx}; {@code status} is shown in the status slot (null = none). */
    boolean drawChest(Player player, FallbackContext ctx, Result status) {
        Lang lang = lang();
        Inventory inv;
        try {
            inv = Vystorm.vystorm_core.main.getApi().gui().createInventory(rt.core, null, player.getUniqueId(), 27, lang.plain(player, "pickers.return-demo.chest-title"), new GuiCallbacks() {
                @Override public void click(InventoryClickEvent event) { onChestClick(player, ctx, event); }
            });
        } catch (RuntimeException failure) {
            DebugMessages.high("[Pickers] Return demo chest could not be created", failure);
            return false;
        }
        for (Map.Entry<Integer, ItemStack> e : chestItems(player, ChestView.of(player, ctx, status)).entrySet()) inv.setItem(e.getKey(), e.getValue());
        player.openInventory(inv);
        return true;
    }

    static Map<Integer, ItemStack> chestItems(Player player, ChestView view) {
        Lang lang = lang();
        Map<Integer, ItemStack> out = new HashMap<>();
        String arg = view.arg() == null || view.arg().isEmpty() ? "-" : view.arg();
        out.put(SLOT_ARG, item(Material.OAK_SIGN, lang.component(player, "pickers.return-demo.arg", "arg", arg), List.of(lang.component(player, "pickers.return-demo.chest-note"))));
        out.put(SLOT_COUNT, item(Material.PAPER, lang.component(player, "pickers.return-demo.counter", "count", view.count()), List.of()));
        out.put(SLOT_ADD, item(Material.LIME_DYE, lang.component(player, "pickers.return-demo.add"), List.of()));
        if (!view.status().isEmpty()) {
            boolean failed = view.status().getFirst().startsWith("✖");
            List<Component> more = view.status().stream().skip(1).map(line -> (Component) Component.text(line)).toList();
            out.put(SLOT_STATUS, item(failed ? Material.REDSTONE : Material.EMERALD, Component.text(view.status().getFirst(), failed ? NamedTextColor.RED : NamedTextColor.GREEN), more));
        }
        out.put(SLOT_LAST, item(Material.BOOK, lang.component(player, "pickers.return-demo.last", "result", lastText(player, view.last())), List.of()));
        out.put(SLOT_PLAYER, item(Material.NAME_TAG, lang.component(player, "pickers.return-demo.pick-player"), List.of()));
        out.put(SLOT_PLAYERS, item(Material.WRITABLE_BOOK, lang.component(player, "pickers.return-demo.pick-players"), List.of()));
        out.put(SLOT_BLOCK, item(Material.GRASS_BLOCK, lang.component(player, "pickers.return-demo.pick-block"), List.of()));
        out.put(SLOT_AREA, item(Material.MAP, lang.component(player, "pickers.return-demo.pick-area"), List.of()));
        return out;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        try {
            Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> {
                Vystorm.vystorm_core.compat.PlatformCompat.displayName(meta, name.colorIfAbsent(NamedTextColor.WHITE).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                Vystorm.vystorm_core.compat.PlatformCompat.lore(meta, lore.stream()
                        .map(l -> l.colorIfAbsent(NamedTextColor.GRAY).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            });
        } catch (RuntimeException failure) {
            DebugMessages.low("[Pickers] Return demo item not styled: " + failure);
        }
        return item;
    }

    private void onChestClick(Player player, FallbackContext ctx, InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        // the pick opens its own menu or puts the player into the world: never from inside the click event
        Bukkit.getScheduler().runTask(rt.core, () -> chestAction(player, ctx, slot));
    }

    /** +1 in the chest: the panel's own session counter, the one the screen shows. */
    static void add(FallbackContext ctx) { ctx.session().put(COUNT, count(ctx.session()) + 1); }

    /** One chest button; package-private for tests. Picks come back to this panel with the same session. */
    void chestAction(Player player, FallbackContext ctx, int slot) {
        if (!player.isOnline() || !player.hasPermission(PERMISSION)) return;
        BiConsumer<Player, String> sink = (viewer, text) -> { };   // the handler writes LAST into the carried session itself
        PickReturn back = PickReturn.panel(ctx);
        org.bukkit.plugin.Plugin owner = back.owner().orElse(rt.core);
        switch (slot) {
            case SLOT_ADD -> { add(ctx); drawChest(player, ctx, null); }
            case SLOT_PLAYER -> { player.closeInventory(); rt.players(owner, player, playerPick(false, sink), back); }
            case SLOT_PLAYERS -> { player.closeInventory(); rt.players(owner, player, playerPick(true, sink), back); }
            case SLOT_BLOCK -> { player.closeInventory(); rt.world(owner, player, worldPick(PickKind.BLOCK, sink), back); }
            case SLOT_AREA -> { player.closeInventory(); rt.world(owner, player, worldPick(PickKind.AREA, sink), back); }
            default -> { }
        }
    }

    /** {@code /vadmin pick demo [arg]}. */
    static void handle(CommandSender sender, String[] args) {
        PickerRuntime rt = PickerRuntime.get();
        if (rt == null || rt.demo == null || !rt.available()) {
            lang().send(sender, "pickers.switched-off");
            return;
        }
        if (!(sender instanceof Player player)) {
            lang().send(sender, "pickers.return-demo.in-game");
            return;
        }
        rt.demo.open(player, args.length >= 3 ? args[2] : "demo");
    }
}
