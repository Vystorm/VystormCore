package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.knownplayers.KnownPlayer;
import Vystorm.vystorm_core.knownplayers.KnownPlayers;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.VystormPanelCloseEvent;
import Vystorm.vystorm_core.panel.internal.PanelOpener;
import Vystorm.vystorm_core.panel.internal.PanelRegistry;

import Vystorm.vystorm_core.picker.PickEnd;
import Vystorm.vystorm_core.picker.PickFlow;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.PlayerPick;
import Vystorm.vystorm_core.picker.PlayerPicks;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

/**
 * Runs Core's player pickers (design 2.3, 3.4 to 3.6): one flow per player, the Core panel {@code vystorm_core:player-pick}
 * (client mod, Vystorm Screen, or Core's own 54-slot chest when no screen opens), the end rules (only Done and Back
 * take the player back; anything the game or another plugin caused leaves the player where they are), the idle
 * timeout with an "Open again" button, the online refresh on join and quit (coalesced to once a second), quit, death
 * and a disabled owner. Server thread. Core-internal.
 */
public final class PickerRuntime implements Listener {
    public static final String PANEL = "player-pick";
    static final long RETURN_TICKS = 2L;

    private static volatile PickerRuntime current;

    final Plugin core;
    private final Map<UUID, PlayerPickFlow> flows = new HashMap<>();
    private int tokens;
    private int timer = -1;
    private boolean refreshQueued;
    /** World picks (0.71.0): the same one-flow-per-player rule across both kinds. */
    WorldPickRuntime world;
    /** {@code /vadmin pick demo}: the calling panel that shows what a return keeps (0.71.0). */
    PickReturnDemo demo;

    /** Package-private for tests (a runtime that is not started); Core uses {@link #start}. */
    PickerRuntime(Plugin core) { this.core = core; }

    /** Only tests: run this flow as the player's one flow without opening anything. */
    void track(PlayerPickFlow flow) { flows.put(flow.viewer.getUniqueId(), flow); }

    public static PickerRuntime get() { return current; }

    // ------------------------------------------------------------------ lifecycle

    public static synchronized void start(Plugin core) {
        if (current != null) return;
        PickerRuntime r = new PickerRuntime(core);
        Panels.of(core).register(PANEL, PanelSpec.builder(Localization.get().core().ref("pickers.title"))
                .access(p -> r.flows.containsKey(p.getUniqueId()))
                .render(ctx -> PickerPanel.render(r, ctx))
                .fallback(r::openChest)
                .build());
        Bukkit.getPluginManager().registerEvents(r, core);
        r.timer = Bukkit.getScheduler().runTaskTimer(core, r::tick, 20L, 20L).getTaskId();
        PanelOpener.closedListener = r::screenClosed;
        PanelOpener.listIcons = r::screenHead;
        Vystorm.vystorm_core.knownplayers.internal.KnownPlayersAdmin.pickCommand = (sender, args) -> PickerAdmin.handle(r, sender, args);
        r.world = new WorldPickRuntime(r);
        r.world.start();
        r.demo = new PickReturnDemo(r);
        r.demo.register();
        current = r;
    }

    public static synchronized void shutdown() {
        PickerRuntime r = current;
        if (r == null) return;
        for (PlayerPickFlow f : List.copyOf(r.flows.values())) r.end(f, PickEnd.OWNER_DISABLED, true);
        if (r.world != null) r.world.shutdown();
        current = null;
        PanelOpener.closedListener = null;
        PanelOpener.listIcons = null;
        Vystorm.vystorm_core.knownplayers.internal.KnownPlayersAdmin.pickCommand = null;
        HandlerList.unregisterAll(r);
        if (r.timer != -1) {
            try { Bukkit.getScheduler().cancelTask(r.timer); } catch (RuntimeException ignored) { }
        }
    }

    /** Core lifecycle: a disabled plugin's flows end with {@link PickEnd#OWNER_DISABLED}. */
    public static void release(Plugin owner) {
        PickerRuntime r = current;
        if (r == null || owner == r.core) return;
        for (PlayerPickFlow f : List.copyOf(r.flows.values())) if (f.owner == owner) r.end(f, PickEnd.OWNER_DISABLED, true);
        if (r.world != null) r.world.release(owner);
    }

    /** World pick from a panel handler (see {@code Pickers.world}). */
    public Result world(PanelAction from, Vystorm.vystorm_core.picker.WorldPick pick) { return world.world(from, pick); }

    /** World pick from anything else (see {@code Pickers.world}). */
    public PickFlow world(Plugin owner, Player viewer, Vystorm.vystorm_core.picker.WorldPick pick, PickReturn back) { return world.world(owner, viewer, pick, back); }

    public boolean available() { return core.isEnabled() && core.getConfig().getBoolean("pickers.enabled", true); }

    PickerConfig config() { return PickerConfig.read(core.getConfig()); }

    // ------------------------------------------------------------------ start

    /** From a panel handler: close the panel, open the picker two ticks later, come back to the same panel. */
    public Result players(PanelAction from, PlayerPick pick) {
        Player viewer = from.player();
        if (!available()) return Result.error(lang().plain(viewer, "pickers.switched-off"));
        PickReturn back = PickReturn.panel(from);
        Plugin owner = back.owner().orElse(core);
        from.closeAndThen(p -> later(() -> { if (p.isOnline()) players(owner, p, pick, back); }));
        return Result.ok();
    }

    public PickFlow players(Plugin owner, Player viewer, PlayerPick pick, PickReturn back) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Pickers require the server thread");
        java.util.Objects.requireNonNull(owner, "owner");
        java.util.Objects.requireNonNull(viewer, "viewer");
        java.util.Objects.requireNonNull(pick, "pick");
        PickReturn to = back == null ? PickReturn.none() : back;
        // a running world pick ends first (one flow per player across both kinds)
        WorldPickFlow picking = world == null ? null : world.flow(viewer);
        if (picking != null) {
            world.finish(picking, picking.handingOver ? WorldPickEnds.Cause.NEW_PICK_FROM_HANDLER : WorldPickEnds.Cause.NEW_PICK, null);
            Optional<PickFlow> nested = active(viewer);
            if (nested.isPresent()) return nested.get();
        }
        PlayerPickFlow old = flows.get(viewer.getUniqueId());
        if (old != null) {
            // a pick started from the old one's onPick: that one is done, and the new picker replaces its UI
            if (old.handingOver) end(old, PickEnd.DONE, false);
            else end(old, PickEnd.REPLACED, true);
            // the old one's onEnd started a flow itself: that one stays, this request is dropped
            PlayerPickFlow nested = flows.get(viewer.getUniqueId());
            if (nested != null) return nested;
        }
        PickerConfig cfg = config();
        PlayerPickFlow flow = new PlayerPickFlow(owner, viewer, pick, to, model(viewer, pick, cfg), "f" + (++tokens), System.currentTimeMillis(), f -> end(f, PickEnd.CANCELLED_BY_PLUGIN, true));
        if (!available() || !owner.isEnabled()) {
            lang().send(viewer, "pickers.switched-off");
            flow.ended = true;
            flow.end = PickEnd.INTERRUPTED;
            return flow;
        }
        flows.put(viewer.getUniqueId(), flow);
        flow.model.refresh(sources(flow));
        flow.model.select(flow.model.firstTab());
        forgetScreenHistory(viewer);   // the picker brings the player back itself: no Core Back button on its screen
        PanelRegistry.Registered reg = PanelRegistry.find(Panels.of(core).id(PANEL)).orElse(null);
        PanelOpenResult result = reg == null ? PanelOpenResult.NONE : PanelOpener.open(viewer, reg, flow.token);
        if (flow.ended) return flow;
        switch (result) {
            case NATIVE -> flow.route = PlayerPickFlow.Route.NATIVE;
            case DIALOG -> flow.route = PlayerPickFlow.Route.SCREEN;
            case FALLBACK -> { if (flow.route != PlayerPickFlow.Route.CHEST) end(flow, PickEnd.INTERRUPTED, false); }
            default -> end(flow, PickEnd.INTERRUPTED, false);
        }
        return flow;
    }

    PickerModel model(Player viewer, PlayerPick pick, PickerConfig cfg) {
        int radius = Math.min(cfg.maxNearby(), pick.nearbyRadius() > 0 ? pick.nearbyRadius() : cfg.defaultNearby());
        return new PickerModel(viewer.getUniqueId(), pick.sources(), pick.multiple(), pick.max(), pick.includeSelf(), radius,
                pick.preselected(),
                c -> pick.filter().show(viewer, c.picked()),
                c -> pick.rule().check(viewer, c.picked()),
                new PickerModel.Limits(cfg.searchMinChars(), cfg.searchMaxHits(), PickerConfig.RECENT_LIMIT));
    }

    /** Server and known-player answers for one viewer. */
    PickerModel.Sources sources(PlayerPickFlow flow) {
        Player viewer = flow.viewer;
        PickerConfig cfg = config();
        int days = flow.pick.recentDays() > 0 ? flow.pick.recentDays() : cfg.recentDays();
        return new PickerModel.Sources() {
            @Override public List<PickerModel.Online> online() {
                List<PickerModel.Online> out = new ArrayList<>();
                Location at = viewer.getLocation();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    boolean visible = p == viewer || (viewer.canSee(p) && !vanished(p));
                    double distance = -1;
                    Location there = p.getLocation();
                    if (at.getWorld() != null && at.getWorld().equals(there.getWorld())) distance = at.distance(there);
                    out.add(new PickerModel.Online(p.getUniqueId(), p.getName(), visible, distance, skin(p.getUniqueId())));
                }
                return out;
            }
            @Override public List<PickerModel.Candidate> recent() {
                return KnownPlayers.recent(Duration.ofDays(days), PickerConfig.RECENT_LIMIT + Bukkit.getOnlinePlayers().size()).stream().map(PickerRuntime::offline).toList();
            }
            @Override public List<PickerModel.Candidate> search(String prefix, int limit) {
                return KnownPlayers.search(prefix, limit).stream().map(PickerRuntime::offline).toList();
            }
            @Override public PickerModel.Candidate lookup(UUID id) {
                Player online = Bukkit.getPlayer(id);
                if (online != null && (viewer.canSee(online) && !vanished(online))) return new PickerModel.Candidate(id, online.getName(), true, System.currentTimeMillis(), -1, skin(id));
                return KnownPlayers.get(id).map(PickerRuntime::offline).orElse(null);
            }
        };
    }

    static PickerModel.Candidate offline(KnownPlayer k) { return new PickerModel.Candidate(k.id(), k.name(), false, k.lastSeen(), -1, k.skinUrl().orElse(null)); }

    static String skin(UUID id) { return KnownPlayers.get(id).flatMap(KnownPlayer::skinUrl).orElse(null); }

    /** The common {@code vanished} metadata of vanish plugins. */
    static boolean vanished(Player p) {
        try {
            for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        } catch (RuntimeException odd) {
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------ flows

    public PlayerPickFlow flow(Player viewer) { return viewer == null ? null : flows.get(viewer.getUniqueId()); }

    /**
     * 0.71.1 ({@code PanelOpener.listIcons}): the skin head of a picker row ({@code p:<uuid>}) on a Vystorm Screen, as
     * the chest shows it; null for other panels and rows. Server thread; no lookup.
     */
    Object screenHead(Player viewer, String panelId, String entryKey) {
        if (entryKey == null || !entryKey.startsWith("p:") || !Panels.of(core).id(PANEL).equals(panelId)) return null;
        PlayerPickFlow flow = flow(viewer);
        if (flow == null || flow.ended) return null;
        UUID id;
        try { id = UUID.fromString(entryKey.substring(2)); } catch (IllegalArgumentException notAPlayer) { return null; }
        return PickerChest.screenHead(flow.model.find(id), config().heads(), PickerRuntime::onlineSkin);
    }

    /** The skin address the server has with this online player's profile, or null. */
    private static String onlineSkin(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online == null) return null;
        try {
            org.bukkit.profile.PlayerProfile profile = Vystorm.vystorm_core.compat.PlatformCompat.getPlayerProfile(online);
            java.net.URL url = profile == null || profile.getTextures() == null ? null : profile.getTextures().getSkin();
            return url == null ? null : url.toString();
        } catch (RuntimeException | LinkageError none) {
            return null;
        }
    }

    /** The player picker or the world pick this player is in. */
    public Optional<PickFlow> active(Player viewer) {
        PlayerPickFlow f = flow(viewer);
        if (f != null) return Optional.of(f);
        return Optional.ofNullable(world == null ? null : world.flow(viewer));
    }

    public boolean cancel(Player viewer) {
        Optional<PickFlow> f = active(viewer);
        return f.isPresent() && f.get().cancel();
    }

    /** Only tests: number of running flows. */
    int size() { return flows.size(); }

    /**
     * Ends a flow once: removes it, closes what shows it ({@code closeUi}; not when the UI closing is what ended it),
     * tells the owner, and for {@link PickEnd#TIMEOUT} sends the "Open again" line.
     */
    boolean end(PlayerPickFlow flow, PickEnd end, boolean closeUi) {
        if (flow.ended) return false;
        flow.ended = true;
        flow.end = end;
        flows.remove(flow.viewer.getUniqueId(), flow);
        if (closeUi) closeUi(flow);
        PlayerPick.EndHandler onEnd = flow.pick.onEnd();
        if (onEnd != null) {
            try { onEnd.ended(flow.viewer, end); }
            catch (RuntimeException failure) { DebugMessages.high("[Pickers] onEnd of " + flow.owner.getName() + " failed", failure); }
        }
        if (end == PickEnd.TIMEOUT && flow.viewer.isOnline()) openAgainLine(flow);
        return true;
    }

    private void closeUi(PlayerPickFlow flow) {
        Player p = flow.viewer;
        if (!p.isOnline()) return;
        try {
            switch (flow.route) {
                case NATIVE -> Panels.of(core).close(p, PANEL);
                case SCREEN -> {
                    var runtime = Vystorm.vystorm_core.screen.internal.ScreenRuntime.get();
                    runtime.session(p).filter(s -> s.spec().id().equals("panel:" + Panels.of(core).id(PANEL))).ifPresent(s -> runtime.close(p, ScreenInput.Reason.PLUGIN));
                }
                case CHEST -> { if (flow.chest != null && flow.chest.showing()) p.closeInventory(); }
                case NONE -> { }
            }
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.low("[Pickers] Picker of " + p.getName() + " not closed: " + failure);
        }
    }

    private void openAgainLine(PlayerPickFlow flow) {
        Player p = flow.viewer;
        Lang lang = lang();
        Component button = Vystorm.vystorm_core._uiMethods.chat.ChatActions.button(lang.plain(p, "pickers.open-again"), lang.plain(p, "pickers.open-again-hover"),
                q -> { if (flow.owner.isEnabled()) players(flow.owner, q, flow.pick, flow.back); });
        try { Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(p, lang.component(p, "pickers.timed-out").append(Component.space()).append(button)); }
        catch (RuntimeException | LinkageError noChat) { }
    }

    /** After Done or Back: back to the calling menu two ticks later, unless another menu is open by then. */
    void returnLater(PlayerPickFlow flow, Result result) {
        later(() -> {
            Player p = flow.viewer;
            if (!p.isOnline()) return;
            if (flows.containsKey(p.getUniqueId()) || WorldPickRuntime.picking(p) || otherMenuOpen(p)) {
                PickReturn.none().go(p, result);   // something else is in front now: only the message
                return;
            }
            forgetScreenHistory(p);   // the caller gets no Core Back button that leads into the closed picker
            flow.back.go(p, result);
        });
    }

    static void forgetScreenHistory(Player p) {
        try { Vystorm.vystorm_core.screen.internal.ScreenRuntime.get().forgetHistory(p); }
        catch (RuntimeException | LinkageError noScreens) { }
    }

    /** A chest or screen of someone else is open (Spigot reports every chest close as the player's own). */
    static boolean otherMenuOpen(Player p) {
        try {
            InventoryView view = p.getOpenInventory();
            org.bukkit.inventory.Inventory top = Vystorm.vystorm_core.compat.InventoryCompat.top(view);
            if (top != null && top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return true;
        } catch (RuntimeException | LinkageError unknown) {
            // fall through
        }
        try {
            return Vystorm.vystorm_core.screen.internal.ScreenRuntime.get().session(p).isPresent();
        } catch (RuntimeException | LinkageError noScreens) {
            return false;
        }
    }

    void later(Runnable task) { Bukkit.getScheduler().runTaskLater(core, task, RETURN_TICKS); }

    void touch(PlayerPickFlow flow) { flow.lastActivity = System.currentTimeMillis(); }

    // ------------------------------------------------------------------ choosing (shared by panel and chest)

    /**
     * The hand-over: the rule is asked again for the newly chosen rows, then the plugin's handler. Ok ends the flow
     * with {@link PickEnd#DONE}; so does a handler that throws (the player still goes back, with {@code panel.failed}).
     * The caller checks {@code flow.ended}: ended = close the UI and {@link #returnLater}; open = show the error.
     */
    Result finish(PlayerPickFlow flow, PickerModel.Candidate single) {
        Player viewer = flow.viewer;
        if (single != null) {
            PickVerdict v = flow.model.verdict(single);
            if (!v.allowed()) return Result.error(plain(viewer, v.reason()));
        } else {
            List<PickerModel.Row> denied = flow.model.deniedAdded();
            if (!denied.isEmpty()) {
                PickerModel.Row first = denied.getFirst();
                return Result.error(lang().plain(viewer, "pickers.denied-chosen", "player", first.candidate().name(), "reason", plain(viewer, first.verdict().reason())));
            }
        }
        PlayerPicks picks = flow.model.picks(single, flow.back.session());
        Result r;
        flow.handingOver = true;   // a pick started from onPick ends this one as DONE (see players)
        try {
            r = flow.pick.onPick().picked(viewer, picks);
        } catch (RuntimeException failure) {
            flow.handingOver = false;
            DebugMessages.high("[Pickers] Pick handler of " + flow.owner.getName() + " failed", failure);
            // a failing handler still goes back (design 2.2): the flow ends, the caller sees flow.ended
            end(flow, PickEnd.DONE, false);
            return Result.error(lang().plain(viewer, "panel.failed"));
        }
        flow.handingOver = false;
        if (r == null || r.isPending()) r = Result.ok();
        if (!r.success()) {
            flow.error = r.message().isEmpty() ? null : r.message();
            return r;
        }
        end(flow, PickEnd.DONE, false);
        return r;
    }

    // ------------------------------------------------------------------ closes

    /** The picker's screen closed (PanelScreens hook). */
    void screenClosed(Player player, String panelId, String arg, ScreenInput.Reason reason) {
        if (!panelId.equals(Panels.of(core).id(PANEL))) return;
        PlayerPickFlow flow = flow(player);
        if (flow == null || flow.ended || !flow.token.equals(arg) || flow.route != PlayerPickFlow.Route.SCREEN) return;
        PickEnd end = endForScreen(reason);
        end(flow, end, false);
        if (end == PickEnd.BACK) returnLater(flow, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPanelClose(VystormPanelCloseEvent event) {
        if (!event.panelId().equals(Panels.of(core).id(PANEL))) return;
        PlayerPickFlow flow = flow(event.getPlayer());
        if (flow == null || flow.ended || flow.route != PlayerPickFlow.Route.NATIVE || flow.nativeSession != event.session()) return;
        PickEnd end = endForPanel(event.reason());
        end(flow, end, false);
        if (end == PickEnd.BACK) returnLater(flow, null);
    }

    /** The session id of the picker's client-mod panel, so closes of an older picker session do not count. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPanelOpen(Vystorm.vystorm_core.panel.VystormPanelOpenEvent event) {
        if (!event.panelId().equals(Panels.of(core).id(PANEL))) return;
        PlayerPickFlow flow = flow(event.getPlayer());
        if (flow != null && !flow.ended) flow.nativeSession = event.session();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        PlayerPickFlow flow = flow(event.getPlayer());
        if (flow != null) flow.teleportedAt = System.currentTimeMillis();
    }

    /** The picker's chest closed without Core closing it. */
    void chestClosed(PlayerPickFlow flow, String reasonName) {
        if (flow.ended) return;
        if (reasonName == null) {
            // Spigot names no reason: a teleport, a quit or a death also close the chest. Decide one tick later,
            // when the quit and death handlers have run; a teleport just before the close is not a Back.
            boolean teleported = System.currentTimeMillis() - flow.teleportedAt < 1000;
            Bukkit.getScheduler().runTask(core, () -> {
                if (flow.ended) return;
                PickEnd end = !flow.viewer.isOnline() ? PickEnd.QUIT : teleported || flow.viewer.isDead() ? PickEnd.INTERRUPTED : PickEnd.BACK;
                end(flow, end, false);
                if (end == PickEnd.BACK) returnLater(flow, null);
            });
            return;
        }
        PickEnd end = endForChest(reasonName);
        end(flow, end, false);
        if (end == PickEnd.BACK) returnLater(flow, null);
    }

    static PickEnd endForScreen(ScreenInput.Reason reason) {
        return switch (reason) {
            case PLAYER -> PickEnd.BACK;
            case QUIT -> PickEnd.QUIT;
            case DAMAGE, TELEPORT, DEATH, WORLD -> PickEnd.INTERRUPTED;
            case PLUGIN, COMMAND -> PickEnd.REPLACED;
        };
    }

    static PickEnd endForPanel(int reason) {
        return switch (reason) {
            case PanelLimits.CLOSED_PLAYER -> PickEnd.BACK;
            case PanelLimits.CLOSED_ERROR -> PickEnd.INTERRUPTED;
            default -> PickEnd.REPLACED;
        };
    }

    /** Paper's {@code InventoryCloseEvent.Reason} by name; null (Spigot has no reason) counts as the player's own close. */
    static PickEnd endForChest(String reason) {
        if (reason == null) return PickEnd.BACK;
        return switch (reason) {
            case "PLAYER" -> PickEnd.BACK;
            case "DISCONNECT" -> PickEnd.QUIT;
            case "OPEN_NEW", "PLUGIN" -> PickEnd.REPLACED;
            default -> PickEnd.INTERRUPTED;   // DEATH, TELEPORT, CANT_USE, UNLOADED, UNKNOWN
        };
    }

    /** Only the ends the player asked for take them back (owner decision 6). */
    static boolean returns(PickEnd end) { return end == PickEnd.DONE || end == PickEnd.BACK; }

    /** Idle longer than the timeout; while the search prompt is open (typing on a phone, a chat form) three times as long. */
    static boolean timedOut(PlayerPickFlow flow, long now) { return now - flow.lastActivity > flow.pick.timeout().toMillis() * (flow.prompting ? 3 : 1); }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerPickFlow flow = flow(event.getPlayer());
        if (flow != null) end(flow, PickEnd.QUIT, false);
        queueRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PlayerPickFlow flow = flow(event.getEntity());
        if (flow != null) end(flow, PickEnd.INTERRUPTED, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { queueRefresh(); }

    /** Online rows follow joins and quits, at most once a second (client mod and chest; a screen on its next click). */
    private void queueRefresh() {
        if (refreshQueued || flows.isEmpty()) return;
        refreshQueued = true;
        Bukkit.getScheduler().runTaskLater(core, () -> {
            refreshQueued = false;
            for (PlayerPickFlow f : List.copyOf(flows.values())) redraw(f);
        }, 20L);
    }

    void redraw(PlayerPickFlow f) {
        if (f.ended) return;
        switch (f.route) {
            case NATIVE -> Panels.of(core).refresh(f.viewer, PANEL);
            case CHEST -> { if (f.chest != null) f.chest.redraw(); }
            default -> { }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (PlayerPickFlow f : List.copyOf(flows.values())) if (timedOut(f, now)) end(f, PickEnd.TIMEOUT, true);
    }

    // ------------------------------------------------------------------ chest

    /** The panel's fallback: Core's own chest (no client mod, no screen). */
    void openChest(Player viewer) {
        PlayerPickFlow flow = flow(viewer);
        if (flow == null || flow.ended) return;
        PickerChest chest = new PickerChest(this, flow);
        flow.chest = chest;
        flow.route = PlayerPickFlow.Route.CHEST;
        if (!chest.open()) {
            flow.route = PlayerPickFlow.Route.NONE;
            lang().send(viewer, "panel.unavailable");
        }
    }

    // ------------------------------------------------------------------ texts

    static Lang lang() { return Localization.get().core(); }

    /** A reason (String, {@code Lang.ref}, Component) as plain text for this viewer. */
    static String plain(Player viewer, Object text) {
        if (text == null) return "";
        if (text instanceof Component c) return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        String resolved = Localization.get().resolve(viewer, String.valueOf(text));
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(resolved));
    }

    /** A title (String, {@code Lang.ref}, Component) as a component for this viewer. */
    static Component component(Player viewer, Object text) {
        if (text instanceof Component c) return c;
        return Component.text(plain(viewer, text));
    }

    boolean exactLastSeen(Player viewer) { return viewer.hasPermission("vystorm_core.pickers.exact-last-seen"); }
}
