package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.blocks.EditAsk;
import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickCandidate;
import Vystorm.vystorm_core.picker.PickEnd;
import Vystorm.vystorm_core.picker.PickFlow;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickProtection;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.picker.WorldPick;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Runs Core's world picks of single targets (design 4.3 to 4.8): pick mode without any item, one flow per player
 * (shared with the player picker), the preview every 4 ticks (own ray trace, the plugin's check cached per target,
 * green/red dust particles only the picking player sees, the hint or the reason in the action bar, a boss bar with the
 * idle time), the triggers (arm swing, interact, entity use or hit; debounced), sneak for the options, the hand-over
 * to the plugin and the end rules: a choice or Cancel goes back through the {@link PickReturn}; a timeout, death, a
 * world change or another menu leave the player in the world with an "Open again" chat button; quit, a disabled owner
 * or {@code pickers().cancel(..)} end it quietly. Damage and teleports within the world keep it. Server thread.
 * Core-internal.
 */
public final class WorldPickRuntime implements Listener {
    static final long PREVIEW_TICKS = 4L;
    /** The action bar fades after a few seconds: it is sent again at least this often. */
    static final long BAR_REFRESH_MS = 1000;
    static final Color OK = Color.fromRGB(0x55, 0xFF, 0x55), DENIED = Color.fromRGB(0xFF, 0x55, 0x55);

    private static volatile WorldPickRuntime current;

    final PickerRuntime picker;
    final Plugin core;
    private final Map<UUID, WorldPickFlow> flows = new HashMap<>();
    /** The AREA part (two corners, box preview, confirm view). */
    final AreaPickRuntime area = new AreaPickRuntime(this);
    private int timer = -1;
    private final Particle dust;
    private final boolean dustColoured;

    WorldPickRuntime(PickerRuntime picker) {
        this.picker = picker;
        this.core = picker.core;
        Particle p = particle("DUST");
        if (p == null) p = particle("REDSTONE");
        dustColoured = p != null;
        dust = p != null ? p : particle("END_ROD");
    }

    static Particle particle(String name) {
        try { return Particle.valueOf(name); } catch (IllegalArgumentException | LinkageError missing) { return null; }
    }

    /** Whether this player is in a world pick (guard, Core's own menu shortcut). */
    public static boolean picking(Player player) {
        WorldPickRuntime r = current;
        return r != null && player != null && r.flows.containsKey(player.getUniqueId());
    }

    static WorldPickRuntime get() { return current; }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        Bukkit.getPluginManager().registerEvents(this, core);
        try {
            WorldPickGuard.register(Vystorm.vystorm_core.main.getApi().priority().stages(), core, WorldPickRuntime::picking, (p, swing) -> trigger(p, swing));
        } catch (RuntimeException | LinkageError noStages) {
            core.getLogger().log(java.util.logging.Level.WARNING, "World pick guard not started", noStages);
        }
        timer = Bukkit.getScheduler().runTaskTimer(core, this::preview, PREVIEW_TICKS, PREVIEW_TICKS).getTaskId();
        current = this;
    }

    void shutdown() {
        for (WorldPickFlow f : List.copyOf(flows.values())) finish(f, WorldPickEnds.Cause.OWNER_DISABLED, null);
        current = null;
        HandlerList.unregisterAll(this);
        if (timer != -1) {
            try { Bukkit.getScheduler().cancelTask(timer); } catch (RuntimeException ignored) { }
        }
    }

    void release(Plugin owner) {
        for (WorldPickFlow f : List.copyOf(flows.values())) if (f.owner == owner) finish(f, WorldPickEnds.Cause.OWNER_DISABLED, null);
    }

    boolean available() { return picker.available() && config().enabled(); }

    WorldPickConfig config() { return WorldPickConfig.read(core.getConfig()); }

    // ------------------------------------------------------------------ start

    Result world(PanelAction from, WorldPick pick) {
        Player viewer = from.player();
        if (!available()) return Result.error(PickerRuntime.lang().plain(viewer, "pickers.world.switched-off"));
        PickReturn back = PickReturn.panel(from);
        Plugin owner = back.owner().orElse(core);
        from.closeAndThen(p -> picker.later(() -> { if (p.isOnline()) world(owner, p, pick, back); }));
        return Result.ok();
    }

    PickFlow world(Plugin owner, Player viewer, WorldPick pick, PickReturn back) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Pickers require the server thread");
        java.util.Objects.requireNonNull(owner, "owner");
        java.util.Objects.requireNonNull(viewer, "viewer");
        java.util.Objects.requireNonNull(pick, "pick");
        PickReturn to = back == null ? PickReturn.none() : back;
        // refusals first: a refused start leaves a running flow alone
        String refused = !available() || !owner.isEnabled() ? "pickers.world.switched-off"
                : viewer.getGameMode() == GameMode.SPECTATOR ? "pickers.world.spectator" : null;
        if (refused != null) {
            World here = viewer.getWorld();
            WorldPickFlow none = new WorldPickFlow(owner, viewer, pick, to, 1, here == null ? new UUID(0, 0) : here.getUID(), System.currentTimeMillis(), f -> { });
            none.ended = true;
            none.end = PickEnd.INTERRUPTED;
            WorldPick.EndHandler onEnd = pick.onEnd();
            if (onEnd != null) {
                try { onEnd.ended(viewer, PickEnd.INTERRUPTED); }
                catch (RuntimeException | LinkageError failure) { DebugMessages.high("[Pickers] onEnd of " + owner.getName() + " failed", failure); }
            }
            String text = PickerRuntime.lang().plain(viewer, refused);
            if (flows.containsKey(viewer.getUniqueId()) || picker.flow(viewer) != null) send(viewer, Component.text(text, NamedTextColor.GRAY));
            else to.go(viewer, Result.error(text));   // back where the player came from, with the reason
            return none;
        }
        // one flow per player: an old world pick or player picker ends first
        WorldPickFlow old = flows.get(viewer.getUniqueId());
        if (old != null) {
            finish(old, old.handingOver ? WorldPickEnds.Cause.NEW_PICK_FROM_HANDLER : WorldPickEnds.Cause.NEW_PICK, null);
            WorldPickFlow nested = flows.get(viewer.getUniqueId());
            if (nested != null) return nested;
        }
        PlayerPickFlow players = picker.flow(viewer);
        if (players != null) {
            picker.end(players, players.handingOver ? PickEnd.DONE : PickEnd.REPLACED, true);
            Optional<PickFlow> nested = picker.active(viewer);
            if (nested.isPresent()) return nested.get();
        }
        WorldPickConfig cfg = config();
        World world = viewer.getWorld();
        WorldPickFlow flow = new WorldPickFlow(owner, viewer, pick, to, cfg.reach(pick), world == null ? new UUID(0, 0) : world.getUID(),
                System.currentTimeMillis(), f -> finish(f, WorldPickEnds.Cause.PLUGIN_CANCEL, null));
        // a caller's chest or screen (start from a chest click or command) closes, or the pick would end as REPLACED
        flows.put(viewer.getUniqueId(), flow);
        closeMenusLater(flow);
        startLine(flow);
        showBar(flow);
        tick(flow, System.currentTimeMillis(), cfg);
        return flow;
    }

    /**
     * One tick after the start (never inside the caller's click event): a chest or screen the caller left open is
     * closed, so pick mode does not take it for "another menu". The start grace covers that tick.
     */
    private void closeMenusLater(WorldPickFlow f) {
        Bukkit.getScheduler().runTask(core, () -> {
            if (f.ended || flow(f.viewer) != f || f.state.phase() != WorldPickState.Phase.CHOOSING || !PickerRuntime.otherMenuOpen(f.viewer)) return;
            try {
                var screens = Vystorm.vystorm_core.screen.internal.ScreenRuntime.get();
                if (screens.session(f.viewer).isPresent()) screens.close(f.viewer, Vystorm.vystorm_core.screen.ScreenInput.Reason.PLUGIN);
            } catch (RuntimeException | LinkageError noScreens) { }
            try { f.viewer.closeInventory(); } catch (RuntimeException | LinkageError odd) { }
        });
    }

    WorldPickFlow flow(Player viewer) { return viewer == null ? null : flows.get(viewer.getUniqueId()); }

    /** Only tests and the admin dump: number of running world picks. */
    int size() { return flows.size(); }

    // ------------------------------------------------------------------ ends

    /**
     * Ends a flow once: removes it, takes down the boss bar, the action bar and the options, tells the owner and, when
     * {@code openAgain}, sends the "Open again" chat line.
     */
    boolean end(WorldPickFlow flow, PickEnd end, boolean openAgain) {
        if (flow.ended) return false;
        flow.ended = true;
        flow.end = end;
        flows.remove(flow.viewer.getUniqueId(), flow);
        if (flow.state.phase() == WorldPickState.Phase.PROMPT && flow.viewer.isOnline()) {
            try { Vystorm.vystorm_core.main.getApi().dialogs().close(flow.viewer); } catch (RuntimeException | LinkageError ignored) { }
        }
        if (flow.options != null) {
            try { flow.options.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
        if (flow.scan != null) flow.scan.cancel();
        if (flow.confirm != null) {
            AreaConfirmView view = flow.confirm;
            flow.confirm = null;
            try { view.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
        hideBar(flow);
        if (flow.viewer.isOnline()) actionBar(flow.viewer, Component.empty());
        WorldPick.EndHandler onEnd = flow.pick.onEnd();
        if (onEnd != null) {
            try { onEnd.ended(flow.viewer, end); }
            catch (RuntimeException failure) { DebugMessages.high("[Pickers] onEnd of " + flow.owner.getName() + " failed", failure); }
        }
        if (openAgain && flow.viewer.isOnline()) openAgainLine(flow, end);
        return true;
    }

    /**
     * Ends a flow by its cause through the end rules ({@link WorldPickEnds}): the end, the "Open again" line, and for a
     * choice or Cancel the way back with {@code result} as the status. False when the cause keeps the pick or it had ended.
     */
    boolean finish(WorldPickFlow flow, WorldPickEnds.Cause cause, Result result) {
        WorldPickEnds.Rule rule = WorldPickEnds.rule(cause);
        if (rule.end() == null || !end(flow, rule.end(), rule.openAgain())) return false;
        if (rule.goBack()) returnLater(flow, result);
        return true;
    }

    /** After a choice or Cancel: back to the calling menu two ticks later, unless another menu or pick is open by then. */
    void returnLater(WorldPickFlow flow, Result result) {
        picker.later(() -> {
            Player p = flow.viewer;
            if (!p.isOnline()) return;
            if (flows.containsKey(p.getUniqueId()) || picker.flow(p) != null || PickerRuntime.otherMenuOpen(p)) {
                PickReturn.none().go(p, result);
                return;
            }
            PickerRuntime.forgetScreenHistory(p);
            flow.back.go(p, result);
        });
    }

    private void openAgainLine(WorldPickFlow flow, PickEnd end) {
        Player p = flow.viewer;
        Lang lang = PickerRuntime.lang();
        Component button = Vystorm.vystorm_core._uiMethods.chat.ChatActions.button(lang.plain(p, "pickers.world.open-again"), lang.plain(p, "pickers.world.open-again-hover"),
                q -> { if (flow.owner.isEnabled() && !picking(q)) world(flow.owner, q, flow.pick, flow.back); });
        send(p, lang.component(p, "pickers.world.ended." + end.name().toLowerCase(Locale.ROOT).replace('_', '-'), "title", PickerRuntime.plain(p, flow.pick.title()))
                .append(Component.space()).append(button));
    }

    private void startLine(WorldPickFlow flow) {
        Player p = flow.viewer;
        Lang lang = PickerRuntime.lang();
        Component options = Vystorm.vystorm_core._uiMethods.chat.ChatActions.button(lang.plain(p, "pickers.world.button-options"), lang.plain(p, "pickers.world.button-options-hover"),
                q -> { WorldPickFlow f = flow(q); if (f == flow && f.state.phase() == WorldPickState.Phase.CHOOSING) openOptions(f); });
        Component cancel = Vystorm.vystorm_core._uiMethods.chat.ChatActions.button(lang.plain(p, "pickers.world.button-cancel"), lang.plain(p, "pickers.world.button-cancel-hover"),
                q -> { WorldPickFlow f = flow(q); if (f == flow) back(f); });
        Component line = lang.component(p, "pickers.world.started", "title", PickerRuntime.plain(p, flow.pick.title()),
                "hint", lang.plain(p, "pickers.world.hint." + flow.pick.kind().name().toLowerCase(Locale.ROOT)));
        if (flow.pick.subtitle() != null) line = line.append(Component.text(" " + PickerRuntime.plain(p, flow.pick.subtitle()), NamedTextColor.GRAY));
        send(p, line.append(Component.space()).append(options).append(Component.space()).append(cancel));
    }

    /** Cancel (options, chat button): ends with BACK and goes back. */
    void back(WorldPickFlow flow) {
        finish(flow, WorldPickEnds.Cause.CANCEL_BUTTON, null);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        WorldPickFlow f = flow(event.getPlayer());
        if (f != null) finish(f, WorldPickEnds.Cause.QUIT, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        WorldPickFlow f = flow(event.getEntity());
        if (f != null) finish(f, WorldPickEnds.Cause.DEATH, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        WorldPickFlow f = flow(event.getPlayer());
        if (f != null) finish(f, WorldPickEnds.Cause.WORLD_CHANGE, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        // in a boat or minecart sneaking means getting off: there the chat button opens the options
        if (!event.isSneaking() || event.getPlayer().isInsideVehicle()) return;
        WorldPickFlow f = flow(event.getPlayer());
        if (f != null && f.state.sneak(System.currentTimeMillis())) openOptions(f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        WorldPickFlow f = flow(event.getPlayer());
        if (f != null) f.state.dropped(System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        String type;
        try { type = event.getAnimationType().name(); } catch (RuntimeException | LinkageError unknown) { type = "ARM_SWING"; }
        if (type.equals("ARM_SWING")) trigger(event.getPlayer(), true);
    }

    /** A click of a picking player (guard events and the arm swing): the choice runs one tick later, outside the event. */
    void trigger(Player player, boolean armSwing) {
        WorldPickFlow f = flow(player);
        if (f == null || !f.state.trigger(System.currentTimeMillis(), armSwing)) return;
        Bukkit.getScheduler().runTask(core, () -> {
            // a sneak in the same tick may have opened the options meanwhile: then the click is dropped
            if (!f.ended && flow(f.viewer) == f && f.state.phase() == WorldPickState.Phase.CHOOSING) choose(f, resolve(f, config()));
        });
    }

    // ------------------------------------------------------------------ preview

    private void preview() {
        if (flows.isEmpty()) return;
        long now = System.currentTimeMillis();
        WorldPickConfig cfg = config();
        for (WorldPickFlow f : List.copyOf(flows.values())) {
            try { tick(f, now, cfg); }
            catch (RuntimeException | LinkageError failure) { DebugMessages.low("[Pickers] World pick preview of " + f.viewer.getName() + " failed: " + failure); }
        }
    }

    void tick(WorldPickFlow f, long now, WorldPickConfig cfg) {
        if (f.ended) return;
        Player p = f.viewer;
        if (!p.isOnline()) { finish(f, WorldPickEnds.Cause.QUIT, null); return; }
        if (f.state.timedOut(now)) { finish(f, WorldPickEnds.Cause.TIMEOUT, null); return; }
        updateBar(f, now);
        if (f.area != null) { area.tick(f, now, cfg); return; }
        if (f.state.phase() != WorldPickState.Phase.CHOOSING) return;
        if (!f.state.settling(now) && PickerRuntime.otherMenuOpen(p)) { finish(f, WorldPickEnds.Cause.OTHER_MENU, null); return; }
        WorldTargets.Resolution r = resolve(f, cfg);
        if (f.state.aim(WorldTargets.key(r), now) || f.verdict == null) {
            f.aimed = r;
            f.verdict = verdict(f, r);
            f.state.cache(f.verdict);
            f.extra = r.target() != null ? extra(f, r.target()) : List.of();
        } else {
            f.aimed = r;   // same target: keep the verdict, take the newest values (a position moves within its cell)
        }
        Location eye = p.getEyeLocation();
        Point3 from = new Point3(eye.getX(), eye.getY(), eye.getZ());
        f.lastEye = from;
        // a refused typed coordinate: its cell stays outlined in red while the reason is held
        List<Point3> refused = refusedOutline(f.pick.kind(), f.state.heldTarget(now), from, cfg);
        boolean refusedCell = !refused.isEmpty();
        List<Point3> points = refusedCell ? refused : PickOutline.points(f.pick.kind(), r, f.extra, from, cfg.previewDistance(), cfg.previewPoints());
        particles(p, points, !refusedCell && f.verdict.allowed() ? OK : DENIED);
        Object held = f.state.held(now);
        Component bar = held != null ? Component.text(PickerRuntime.plain(p, held), NamedTextColor.RED)
                : !f.verdict.allowed() ? Component.text(PickerRuntime.plain(p, f.verdict.reason()), NamedTextColor.RED)
                : PickerRuntime.lang().component(p, "pickers.world.hint." + f.pick.kind().name().toLowerCase(Locale.ROOT));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(bar) + (held != null || !f.verdict.allowed() ? "!" : "");
        if (!plain.equals(f.lastBar) || now - f.lastBarAt >= BAR_REFRESH_MS) {
            f.lastBar = plain;
            f.lastBarAt = now;
            actionBar(p, bar);
        }
    }

    /**
     * The outline of a held refused typed cell, or empty. A refused resolution has no target, only the block or entity
     * the coordinates named (its {@code hit}): that is what is outlined (first client check: keying on the target drew
     * nothing, and the aimed block stayed green).
     */
    static List<Point3> refusedOutline(PickKind kind, Object held, Point3 eye, WorldPickConfig cfg) {
        if (!(held instanceof WorldTargets.Resolution r)) return List.of();
        return PickOutline.points(kind, r, List.of(), eye, cfg.previewDistance(), cfg.previewPoints());
    }

    /** Dust in the colour only this player sees (at most 512 points; the particle without colour where dust is missing). */
    void particles(Player p, List<Point3> points, Color colour) {
        if (points.isEmpty() || dust == null) return;
        Object data = dustColoured ? new Particle.DustOptions(colour, 0.7f) : null;
        Vystorm.vystorm_core.visual.Visuals.particles(p, dust, points.size() > 512 ? points.subList(0, 512) : points, data);
    }

    /** What the player aims at now, by Core's own ray trace (fluids ignored, passable blocks skipped). */
    WorldTargets.Resolution resolve(WorldPickFlow f, WorldPickConfig cfg) {
        Player p = f.viewer;
        World w = p.getWorld();
        boolean allowed = worldAllowed(f, w);
        if (f.pick.kind() == PickKind.POSITION) {
            Location l = p.getLocation();
            return WorldTargets.position(w.getUID(), new Point3(l.getX(), l.getY(), l.getZ()), l.getYaw(), l.getPitch(), allowed);
        }
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        WorldTargets.Hit hit;
        if (f.pick.kind() == PickKind.ENTITY) {
            // entities of other types (a dropped item in front of a painting) do not stop the ray; grass and torches do not either
            java.util.Set<org.bukkit.entity.EntityType> types = f.pick.entities();
            RayTraceResult ray = w.rayTrace(eye, dir, f.reach, FluidCollisionMode.NEVER, true, 0.0,
                    e -> e != p && !(e instanceof Player) && (types.isEmpty() || types.contains(e.getType())));
            hit = ray == null ? WorldTargets.NONE : ray.getHitEntity() != null ? entityHit(f, ray.getHitEntity()) : ray.getHitBlock() != null ? blockHit(f, ray.getHitBlock(), ray.getHitBlockFace(), eye) : WorldTargets.NONE;
        } else {
            // passable blocks count (rails, levers, signs, flowers can be chosen); fluids are looked through
            RayTraceResult ray = w.rayTraceBlocks(eye, dir, f.reach, FluidCollisionMode.NEVER, false);
            hit = ray == null || ray.getHitBlock() == null ? WorldTargets.NONE : blockHit(f, ray.getHitBlock(), ray.getHitBlockFace(), eye);
        }
        return WorldTargets.resolve(f.pick.kind(), w.getUID(), hit, f.pick.faces(), allowed);
    }

    private boolean worldAllowed(WorldPickFlow f, World w) {
        try { return f.pick.worlds().test(w); }
        catch (RuntimeException failure) { DebugMessages.low("[Pickers] worlds filter of " + f.owner.getName() + " failed: " + failure); return false; }
    }

    WorldTargets.Hit.OnBlock blockHit(WorldPickFlow f, Block b, BlockFace face, Location eye) {
        BlockPosition pos = new BlockPosition(b.getX(), b.getY(), b.getZ());
        BlockFace side = face != null && Vystorm.vystorm_core.picker.PickFaces.ANY.contains(face) ? face : WorldTargets.faceTowards(pos, new Point3(eye.getX(), eye.getY(), eye.getZ()));
        boolean accepted;
        try { accepted = f.pick.blocks().test(b); }
        catch (RuntimeException failure) { DebugMessages.low("[Pickers] blocks filter of " + f.owner.getName() + " failed: " + failure); accepted = false; }
        boolean container = false;
        if (f.pick.kind() == PickKind.CONTAINER) {
            try { container = Vystorm.vystorm_core.compat.PlatformCompat.getState(b, false) instanceof org.bukkit.block.Container; }
            catch (RuntimeException | LinkageError odd) { container = false; }
        }
        return new WorldTargets.Hit.OnBlock(pos, side, b.getType().name().toLowerCase(Locale.ROOT), accepted, container);
    }

    WorldTargets.Hit.OnEntity entityHit(WorldPickFlow f, Entity e) {
        Location l = e.getLocation();
        BlockFace facing;
        if (e instanceof Hanging h) {
            BlockFace hf = h.getFacing();
            facing = hf == null ? WorldTargets.facing(l.getYaw()) : hf;
        } else {
            facing = WorldTargets.facing(l.getYaw());
        }
        BoundingBox box = e.getBoundingBox();
        boolean accepted = f.pick.entities().isEmpty() || f.pick.entities().contains(e.getType());
        return new WorldTargets.Hit.OnEntity(e.getUniqueId(), e.getType().name().toLowerCase(Locale.ROOT), new Point3(l.getX(), l.getY(), l.getZ()), facing,
                new Point3(box.getMinX(), box.getMinY(), box.getMinZ()), new Point3(box.getMaxX(), box.getMaxY(), box.getMaxZ()), accepted);
    }

    /** Protection, then the plugin's check; a target that is no target gets its reason. */
    PickVerdict verdict(WorldPickFlow f, WorldTargets.Resolution r) {
        Player p = f.viewer;
        Lang lang = PickerRuntime.lang();
        if (!r.ok()) return PickVerdict.deny(lang.plain(p, "pickers.world.reason." + r.reason(), "reach", f.reach));
        if (f.pick.protection() == PickProtection.BUILD) {
            String who = protection(f, r.target());
            if (who != null) return PickVerdict.deny(lang.plain(p, "pickers.world.reason.protected"));
        }
        return check(f, candidate(f, r.target()));
    }

    /** The plugin's check; a check that throws or returns null counts as refused / allowed. */
    PickVerdict check(WorldPickFlow f, PickCandidate c) {
        try {
            PickVerdict v = f.pick.check().check(f.viewer, c);
            return v == null ? PickVerdict.ok() : v;
        } catch (RuntimeException failure) {
            DebugMessages.high("[Pickers] Check of " + f.owner.getName() + " failed", failure);
            return PickVerdict.deny(PickerRuntime.lang().plain(f.viewer, "pickers.world.reason.check-failed"));
        }
    }

    /** Null = may build there; otherwise who said no. */
    String protection(WorldPickFlow f, Picked target) {
        Player p = f.viewer;
        if (f.pick.bypass() != null && p.hasPermission(f.pick.bypass())) return null;
        BlockPosition cell = WorldTargets.editCell(target);
        World w = Bukkit.getWorld(target.world());
        if (w == null) return "unknown";
        try {
            EditVerdict v = Vystorm.vystorm_core.main.getApi().area().mayEdit(new EditAsk(f.owner, p.getUniqueId(), w, cell.x() >> 4, cell.z() >> 4, cell.y(), cell.y()));
            return PickProtectionCheck.denied(v, cell, false);
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.low("[Pickers] Protection ask failed: " + failure);
            return "guard-failed";
        }
    }

    private List<WorldPick.Box> extra(WorldPickFlow f, Picked target) {
        if (f.pick.outline() == null) return List.of();
        try {
            List<WorldPick.Box> boxes = f.pick.outline().boxes(f.viewer, candidate(f, target));
            return boxes == null ? List.of() : boxes.stream().filter(java.util.Objects::nonNull).limit(64).toList();
        } catch (RuntimeException failure) {
            DebugMessages.low("[Pickers] Outline of " + f.owner.getName() + " failed: " + failure);
            return List.of();
        }
    }

    PickCandidate candidate(WorldPickFlow f, Picked target) { return new PickCandidate(target, Optional.empty(), f.back.session()); }

    // ------------------------------------------------------------------ choosing

    /**
     * The hand-over: protection and check again (the world may have changed), then the plugin's handler. Ok ends the
     * flow with DONE and goes back; an error keeps pick mode with the reason in the action bar; a handler that throws
     * still goes back, with {@code panel.failed}. Returns the refusal shown (null when the pick went through or ended).
     */
    String choose(WorldPickFlow f, WorldTargets.Resolution r) {
        if (f.area != null) return area.chooseCorner(f, r);   // a corner; the handler runs from the confirm view
        long now = System.currentTimeMillis();
        Player p = f.viewer;
        PickVerdict v = verdict(f, r);
        if (!v.allowed()) {
            String reason = PickerRuntime.plain(p, v.reason());
            f.state.hold(reason, now);
            f.lastBar = null;
            return reason;
        }
        Result result;
        f.handingOver = true;
        try {
            result = f.pick.onPick().picked(p, candidate(f, r.target()));
        } catch (RuntimeException | LinkageError failure) {
            f.handingOver = false;
            DebugMessages.high("[Pickers] Pick handler of " + f.owner.getName() + " failed", failure);
            finish(f, WorldPickEnds.Cause.HANDLER_FAILED, Result.error(PickerRuntime.lang().plain(p, "panel.failed")));
            return null;
        } finally {
            f.handingOver = false;
        }
        if (f.ended) return null;   // onPick started another pick (this one is DONE) or cancelled it
        if (result == null || result.isPending()) result = Result.ok();
        if (!result.success()) {
            String reason = result.message().isEmpty() ? PickerRuntime.lang().plain(p, "pickers.world.reason.refused") : PickerRuntime.plain(p, result.message());
            f.state.hold(reason, now);
            f.lastBar = null;
            return reason;
        }
        finish(f, WorldPickEnds.Cause.CHOSEN, result);
        return null;
    }

    // ------------------------------------------------------------------ options

    void openOptions(WorldPickFlow f) {
        if (f.ended) return;
        // an area with both corners (its confirm view was closed): sneak and [Options] open the confirm view again
        if (f.area != null && f.area.step() == AreaSteps.Step.CONFIRM) { area.openConfirm(f); return; }
        f.state.openOptions(System.currentTimeMillis());
        actionBar(f.viewer, Component.empty());
        WorldPickOptions o = new WorldPickOptions(this, f);
        f.options = o;
        if (!o.open()) {
            f.options = null;
            f.state.resume(System.currentTimeMillis());
            send(f.viewer, PickerRuntime.lang().component(f.viewer, "panel.unavailable"));
        }
    }

    /** An option was chosen (or the options closed: RESUME). */
    void option(WorldPickFlow f, WorldOptionsLayout.Action action) {
        if (f.ended || flow(f.viewer) != f) return;
        f.options = null;
        long now = System.currentTimeMillis();
        switch (action) {
            case CANCEL -> back(f);
            case RESUME, INFO -> f.state.resume(now);
            case STAND -> {
                f.state.resume(now);
                choose(f, standing(f));
            }
            case TYPE -> prompt(f);
            case REDO_FIRST -> {
                f.state.resume(now);
                if (f.area != null) area.redoFirstFromOptions(f);
            }
        }
    }

    /** "Use where I stand": the position, or the block under the feet. */
    WorldTargets.Resolution standing(WorldPickFlow f) {
        Player p = f.viewer;
        World w = p.getWorld();
        Location l = p.getLocation();
        boolean allowed = worldAllowed(f, w);
        if (f.pick.kind() == PickKind.POSITION) return WorldTargets.position(w.getUID(), new Point3(l.getX(), l.getY(), l.getZ()), l.getYaw(), l.getPitch(), allowed);
        // an area corner: the cell below the feet, also when it is air (flying, jumping)
        if (f.area != null) {
            int y = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, (int) Math.floor(l.getY() - 0.01)));   // kept inside the world (void, flying high)
            return WorldTargets.areaCell(w.getUID(), new BlockPosition(l.getBlockX(), y, l.getBlockZ()), allowed);
        }
        // the block the feet rest on: just below the feet point (slabs, carpets, snow layers and paths are that block)
        Block below = w.getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() - 0.01), l.getBlockZ());
        WorldTargets.Hit hit = below.getType().isAir() ? WorldTargets.NONE : blockHit(f, below, BlockFace.UP, p.getEyeLocation());
        return WorldTargets.resolve(f.pick.kind(), w.getUID(), hit, f.pick.faces(), allowed);
    }

    private void prompt(WorldPickFlow f) {
        Player p = f.viewer;
        f.state.prompt(System.currentTimeMillis());
        Lang lang = PickerRuntime.lang();
        BlockPosition prefill = f.aimed != null && f.aimed.target() != null ? f.aimed.target().cell() : new BlockPosition(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
        String question = f.pick.kind() == PickKind.FACE ? "pickers.world.typed.question-face" : "pickers.world.typed.question";
        try {
            Vystorm.vystorm_core.main.getApi().dialogs().prompt(core, p, lang.ref("pickers.world.typed.title"), lang.ref(question),
                    CoordinateInput.text(prefill.x(), prefill.y(), prefill.z()),
                    text -> Bukkit.getScheduler().runTask(core, () -> typed(f, text)),
                    () -> Bukkit.getScheduler().runTask(core, () -> { if (!f.ended) f.state.resume(System.currentTimeMillis()); }),
                    q -> !f.ended && q.isOnline());
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[Pickers] Coordinate prompt failed", failure);
            f.state.resume(System.currentTimeMillis());
        }
    }

    /** Typed coordinates: parsed, limited (distance, loaded, height), then the same rules as a click. */
    void typed(WorldPickFlow f, String text) {
        if (f.ended || flow(f.viewer) != f) return;
        long now = System.currentTimeMillis();
        f.state.resume(now);
        Player p = f.viewer;
        Lang lang = PickerRuntime.lang();
        Location l = p.getLocation();
        CoordinateInput.Parsed in = CoordinateInput.parse(text, new Point3(l.getX(), l.getY(), l.getZ()), f.pick.kind() == PickKind.FACE);
        if (!in.ok()) { refuseTyped(f, shown(text), lang.plain(p, "pickers.world.typed." + in.error()), null, now); return; }
        World w = p.getWorld();
        Point3 at = in.point();
        WorldPickConfig cfg = config();
        BlockPosition cell = BlockPosition.containing(at);
        String problem = at.distanceSquared(new Point3(l.getX(), l.getY(), l.getZ())) > (double) cfg.typedDistance() * cfg.typedDistance() ? "far"
                : cell.y() < w.getMinHeight() || cell.y() >= w.getMaxHeight() ? "height"
                // an area corner is a cell and its block is never read, so it may lie in an unloaded chunk
                : f.area == null && !w.isChunkLoaded(cell.x() >> 4, cell.z() >> 4) ? "unloaded" : null;
        if (problem != null) { refuseTyped(f, cell.x() + " " + cell.y() + " " + cell.z(), lang.plain(p, "pickers.world.typed." + problem, "max", cfg.typedDistance()), null, now); return; }
        boolean allowed = worldAllowed(f, w);
        WorldTargets.Resolution r;
        if (f.area != null) {
            r = WorldTargets.areaCell(w.getUID(), cell, allowed);
        } else if (f.pick.kind() == PickKind.POSITION) {
            r = WorldTargets.position(w.getUID(), at, l.getYaw(), l.getPitch(), allowed);
        } else {
            Block b = w.getBlockAt(cell.x(), cell.y(), cell.z());
            Location eye = p.getEyeLocation();
            BlockFace face = in.face() != null ? in.face() : WorldTargets.faceTowards(cell, new Point3(eye.getX(), eye.getY(), eye.getZ()));
            WorldTargets.Hit hit = b.getType().isAir() ? WorldTargets.NONE : blockHit(f, b, face, eye);
            r = WorldTargets.resolve(f.pick.kind(), w.getUID(), hit, f.pick.faces(), allowed);
        }
        String refused = choose(f, r);
        if (refused != null && !f.ended) refuseTyped(f, cell.x() + " " + cell.y() + " " + cell.z(), refused, r, now);
    }

    /** What the player typed, safe to repeat in a chat line (no tags, at most 32 characters, "?" when empty). */
    static String shown(String typed) {
        String s = typed == null ? "" : typed.replaceAll("[<>&§\\\\]", "").strip();
        if (s.length() > 32) s = s.substring(0, 31) + "…";
        return s.isEmpty() ? "?" : s;
    }

    /**
     * A refused typed coordinate (0.71.0): the reason stays in the action bar for {@link WorldPickState#HOLD_TYPED} ms
     * and goes to chat once (the action bar alone was gone before it could be read), and the refused cell stays
     * outlined in red for that time instead of the block the player happens to aim at.
     */
    void refuseTyped(WorldPickFlow f, String at, String reason, WorldTargets.Resolution refused, long now) {
        f.state.hold(reason, now, WorldPickState.HOLD_TYPED, refused);
        f.lastBar = null;
        send(f.viewer, PickerRuntime.lang().component(f.viewer, "pickers.world.typed.refused", "at", at == null ? "" : at.strip(), "reason", reason));
    }

    // ------------------------------------------------------------------ bars and chat

    private void showBar(WorldPickFlow f) {
        try {
            net.kyori.adventure.bossbar.BossBar bar = net.kyori.adventure.bossbar.BossBar.bossBar(barTitle(f), 1f,
                    net.kyori.adventure.bossbar.BossBar.Color.BLUE, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            Vystorm.vystorm_core.compat.PlatformCompat.showBossBar(f.viewer, bar);
            f.bossBar = bar;
        } catch (RuntimeException | LinkageError noBar) {
            f.bossBar = null;
        }
    }

    private Component barTitle(WorldPickFlow f) {
        if (f.area != null) return area.title(f);
        return PickerRuntime.lang().component(f.viewer, "pickers.world.bar", "title", PickerRuntime.plain(f.viewer, f.pick.title()));
    }

    private void updateBar(WorldPickFlow f, long now) {
        if (f.bossBar instanceof net.kyori.adventure.bossbar.BossBar bar) {
            try { bar.progress((float) f.state.left(now)); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private void hideBar(WorldPickFlow f) {
        if (f.bossBar instanceof net.kyori.adventure.bossbar.BossBar bar) {
            try { Vystorm.vystorm_core.compat.PlatformCompat.hideBossBar(f.viewer, bar); } catch (RuntimeException | LinkageError ignored) { }
        }
        f.bossBar = null;
    }

    static void actionBar(Player p, Component text) {
        try { Vystorm.vystorm_core.compat.PlatformCompat.sendActionBar(p, text); } catch (RuntimeException | LinkageError noBar) { }
    }

    static void send(Player p, Component text) {
        try { Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(p, text); } catch (RuntimeException | LinkageError noChat) { }
    }
}
