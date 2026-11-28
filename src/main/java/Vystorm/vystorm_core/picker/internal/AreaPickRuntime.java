package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.blocks.EditAsk;
import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.AreaHeight;
import Vystorm.vystorm_core.picker.AreaRules;
import Vystorm.vystorm_core.picker.PickCandidate;
import Vystorm.vystorm_core.picker.PickProtection;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The AREA part of the world pick runtime (design 4.3 to 4.6): corner 1, then corner 2, each chosen like a block (a
 * click on the outlined block, "Block below me", typed coordinates), the live box from the fixed corner to the aimed
 * block with its size in the action bar and the boss bar, the plugin's {@link AreaRules} under Core's caps, the
 * plugin's check, {@code BUILD} protection for the whole box ({@link AreaGuard}: chunk answers only, partial chunks
 * tested off the server thread), and the confirm view. The rules live in the pure classes ({@link AreaSteps},
 * {@link AreaLimits}, {@link AreaOutline}, {@link AreaGuard}, {@link WorldPickEnds}); this class only wires them to
 * the server. Server thread. Core-internal.
 */
final class AreaPickRuntime {
    /** The full protection check: server-thread time per preview pass, chunks per pass, the whole check's deadline. */
    static final long SCAN_BUDGET_NANOS = 2_000_000L, SCAN_DEADLINE_NANOS = 20_000_000_000L;
    static final int SCAN_CHUNKS = 256, PREVIEW_CHUNKS = 128;
    /** An allowed protection answer older than this is asked again before "Use this area". */
    static final long SCAN_FRESH_MS = 60_000;
    static final Color PARTLY = Color.fromRGB(0xFF, 0xD7, 0x40);

    private final WorldPickRuntime rt;

    AreaPickRuntime(WorldPickRuntime rt) { this.rt = rt; }

    // ------------------------------------------------------------------ boxes and verdicts

    private static World world(UUID id) { return id == null ? null : Bukkit.getWorld(id); }

    /** The box from the fixed corner to {@code cell}, after the height mode. */
    Picked.Area box(WorldPickFlow f, AreaSteps.Corner anchor, BlockPosition cell) {
        World w = world(anchor.world());
        int min = w == null ? -64 : w.getMinHeight(), max = w == null ? 320 : w.getMaxHeight();
        return AreaLimits.box(anchor.world(), anchor.cell(), cell, f.pick.area(), f.area.expanded(), min, max);
    }

    /**
     * Whether the confirm view offers "Full height": the pick says {@code ASK} and the world's whole height fits Core's
     * y cap (a lowered {@code max-area.y} or a world taller than 384 would refuse it anyway, so the button is left out).
     */
    boolean canExpand(WorldPickFlow f) {
        if (f.pick.area().height() != AreaHeight.ASK) return false;
        UUID id = f.area.first() != null ? f.area.first().world() : f.viewer.getWorld().getUID();
        World w = world(id);
        return w != null && w.getMaxHeight() - w.getMinHeight() <= rt.config().area().y();
    }

    /** The finished box (both corners), or null. */
    Picked.Area finalBox(WorldPickFlow f) {
        AreaSteps s = f.area;
        if (s.first() == null || s.second() == null) return null;
        return box(f, s.first(), s.second().cell());
    }

    PickCandidate candidate(WorldPickFlow f, Picked.Area target, Optional<BlockPosition> firstCorner) {
        return new PickCandidate(target, firstCorner, f.back.session());
    }

    /**
     * A corner or a box: the ray's reason when nothing may be chosen; a lone corner gets BUILD for its cell and the
     * plugin's check (candidate = the one-cell area, no first corner); a box gets the limits and the plugin's check
     * (candidate = the box, first corner = the fixed corner). BUILD of a whole box is {@link AreaGuard}'s job.
     */
    PickVerdict verdict(WorldPickFlow f, WorldTargets.Resolution r, Picked.Area box, Optional<AreaSteps.Corner> anchor, UUID aimedWorld) {
        Player p = f.viewer;
        Lang lang = PickerRuntime.lang();
        if (!r.ok()) return PickVerdict.deny(lang.plain(p, "pickers.world.reason." + r.reason(), "reach", f.reach));
        if (box == null) {
            Picked.Area corner = (Picked.Area) r.target();
            if (f.pick.protection() == PickProtection.BUILD && rt.protection(f, corner) != null)
                return PickVerdict.deny(lang.plain(p, "pickers.world.reason.protected"));
            return rt.check(f, candidate(f, corner, Optional.empty()));
        }
        AreaRules rules = f.pick.area();
        AreaLimits.Verdict limits = AreaLimits.check(box, anchor.map(a -> a.world().equals(aimedWorld)).orElse(true),
                rules, rt.config().area(), f.area.fullHeight(rules));
        if (!limits.ok()) return PickVerdict.deny(lang.plain(p, "pickers.world.area.reason." + limits.reason(), limits.pairs()));
        return rt.check(f, candidate(f, box, anchor.map(AreaSteps.Corner::cell)));
    }

    /** Whether BUILD applies to this player (not bypassed). */
    boolean guarded(WorldPickFlow f) {
        return f.pick.protection() == PickProtection.BUILD && !(f.pick.bypass() != null && f.viewer.hasPermission(f.pick.bypass()));
    }

    /** {@code mayEdit} for one chunk of the box's world; never loads the chunk (chunk coordinates only). */
    AreaGuard.Asker asker(WorldPickFlow f, UUID worldId) {
        Player p = f.viewer;
        return (cx, cz, minY, maxY) -> {
            World w = world(worldId);
            if (w == null) return new EditVerdict.Deny("unknown");
            return Vystorm.vystorm_core.main.getApi().area().mayEdit(new EditAsk(f.owner, p.getUniqueId(), w, cx, cz, minY, maxY));
        };
    }

    // ------------------------------------------------------------------ preview

    /** One preview pass of an area pick (after the runtime's checks for quit and timeout). */
    void tick(WorldPickFlow f, long now, WorldPickConfig cfg) {
        Player p = f.viewer;
        stepScan(f);
        WorldPickState.Phase phase = f.state.phase();
        Location eyeLoc = p.getEyeLocation();
        Point3 eye = new Point3(eyeLoc.getX(), eyeLoc.getY(), eyeLoc.getZ());
        f.lastEye = eye;
        if (phase == WorldPickState.Phase.CONFIRM) {
            // the confirm view is open: keep the final box visible and its numbers current
            if (f.confirm != null && f.scan != null && f.scan.answer().state() != f.scanShown) f.confirm.render();
            draw(f, f.box, null, null, eye, cfg, colour(f, f.verdict, f.scan == null ? null : f.scan.answer()));
            updateTitle(f);
            return;
        }
        if (phase != WorldPickState.Phase.CHOOSING) return;
        if (!f.state.settling(now) && PickerRuntime.otherMenuOpen(p)) { rt.finish(f, WorldPickEnds.Cause.OTHER_MENU, null); return; }
        AreaSteps s = f.area;
        if (s.step() == AreaSteps.Step.CONFIRM) {
            // the confirm view was closed with ESC: both corners stay, a click or sneak opens it again
            draw(f, f.box, null, null, eye, cfg, colour(f, f.verdict, f.scan == null ? null : f.scan.answer()));
            bar(f, now, PickerRuntime.lang().component(p, "pickers.world.area.paused"), false);
            updateTitle(f);
            return;
        }
        WorldTargets.Resolution r = rt.resolve(f, cfg);
        BlockPosition aimed = r.ok() ? ((Picked.Area) r.target()).min() : null;
        UUID aimedWorld = r.ok() ? r.target().world() : p.getWorld().getUID();
        Optional<AreaSteps.Corner> anchor = s.anchor();
        Picked.Area box = aimed != null && anchor.isPresent() ? box(f, anchor.get(), aimed) : null;
        String key = "area:" + s.step() + ":" + anchor.map(a -> a.cell().toString()).orElse("-") + ":" + (aimed == null ? "none-" + r.reason() : aimed) + ":" + s.expanded();
        if (f.state.aim(key, now) || f.verdict == null) {
            f.aimed = r;
            f.box = box;
            f.verdict = verdict(f, r, box, anchor, aimedWorld);
            f.state.cache(f.verdict);
        }
        f.guardAnswer = box != null && f.verdict.allowed() && guarded(f)
                ? guardPreview(f, box.world()).ask(box, PREVIEW_CHUNKS) : null;
        // a refused typed corner stays outlined in red while its reason is held
        Object held = f.state.held(now);
        Object heldTarget = f.state.heldTarget(now);
        if (heldTarget instanceof WorldTargets.Resolution hr && hr.target() instanceof Picked.Area hc) {
            draw(f, null, null, hc.min(), eye, cfg, WorldPickRuntime.DENIED);
        } else {
            draw(f, box, anchor.map(AreaSteps.Corner::cell).orElse(null), aimed, eye, cfg, colour(f, f.verdict, f.guardAnswer));
        }
        Component text;
        boolean red;
        Lang lang = PickerRuntime.lang();
        if (held != null) { text = Component.text(PickerRuntime.plain(p, held), NamedTextColor.RED); red = true; }
        else if (!f.verdict.allowed()) { text = Component.text(PickerRuntime.plain(p, f.verdict.reason()), NamedTextColor.RED); red = true; }
        else if (f.guardAnswer != null && f.guardAnswer.state() == AreaGuard.State.DENIED) { text = Component.text(lang.plain(p, "pickers.world.area.reason.protected"), NamedTextColor.RED); red = true; }
        else if (box == null) { text = lang.component(p, "pickers.world.area.corner" + s.choosing()); red = false; }
        else {
            String state = f.guardAnswer == null || f.guardAnswer.state() == AreaGuard.State.ALLOWED ? "corner" + s.choosing()
                    : f.guardAnswer.state() == AreaGuard.State.PARTIAL ? "partial" : "checking";
            text = lang.component(p, "pickers.world.area.size-" + state, "size", AreaLimits.size(box), "blocks", AreaLimits.count(box.volume()), "corner", s.choosing());
            red = false;
        }
        bar(f, now, text, red);
        updateTitle(f);
    }

    private AreaGuard.Preview guardPreview(WorldPickFlow f, UUID worldId) {
        if (f.guardPreview == null) f.guardPreview = new AreaGuard.Preview(asker(f, worldId), System::currentTimeMillis);
        return f.guardPreview;
    }

    static Color colour(WorldPickFlow f, PickVerdict verdict, AreaGuard.Answer guard) {
        if (verdict != null && !verdict.allowed()) return WorldPickRuntime.DENIED;
        if (guard == null || guard.state() == AreaGuard.State.ALLOWED) return WorldPickRuntime.OK;
        return guard.state() == AreaGuard.State.DENIED || guard.state() == AreaGuard.State.FAILED ? WorldPickRuntime.DENIED : PARTLY;
    }

    private void draw(WorldPickFlow f, Picked.Area box, BlockPosition anchor, BlockPosition aimed, Point3 eye, WorldPickConfig cfg, Color colour) {
        List<Point3> points = AreaOutline.points(box, anchor, aimed, eye, cfg.previewDistance(), cfg.previewPoints());
        rt.particles(f.viewer, points, colour);
    }

    private void bar(WorldPickFlow f, long now, Component text, boolean red) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text) + (red ? "!" : "");
        if (!plain.equals(f.lastBar) || now - f.lastBarAt >= WorldPickRuntime.BAR_REFRESH_MS) {
            f.lastBar = plain;
            f.lastBarAt = now;
            WorldPickRuntime.actionBar(f.viewer, text);
        }
    }

    /** The boss bar names the step and the size: "Plot · corner 2 of 2 · 12 x 5 x 9". */
    Component title(WorldPickFlow f) {
        Player p = f.viewer;
        Lang lang = PickerRuntime.lang();
        String title = PickerRuntime.plain(p, f.pick.title());
        AreaSteps s = f.area;
        if (s.step() == AreaSteps.Step.CONFIRM) {
            Picked.Area box = f.box;
            return lang.component(p, "pickers.world.area.bar-confirm", "title", title, "size", box == null ? "?" : AreaLimits.size(box));
        }
        if (f.box != null && s.anchor().isPresent()) return lang.component(p, "pickers.world.area.bar-size", "title", title, "corner", s.choosing(), "size", AreaLimits.size(f.box));
        return lang.component(p, "pickers.world.area.bar", "title", title, "corner", s.choosing());
    }

    private void updateTitle(WorldPickFlow f) {
        if (!(f.bossBar instanceof net.kyori.adventure.bossbar.BossBar bar)) return;
        Component t = title(f);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(t);
        if (plain.equals(f.lastTitle)) return;
        f.lastTitle = plain;
        try { bar.name(t); } catch (RuntimeException | LinkageError ignored) { }
    }

    // ------------------------------------------------------------------ corners

    /**
     * A click, "Block below me" or a typed cell for the corner being chosen. In the confirm step (the view was closed)
     * it opens the confirm view again. Returns the refusal shown, or null.
     */
    String chooseCorner(WorldPickFlow f, WorldTargets.Resolution r) {
        long now = System.currentTimeMillis();
        AreaSteps s = f.area;
        Player p = f.viewer;
        if (s.step() == AreaSteps.Step.CONFIRM) { openConfirm(f); return null; }
        Optional<AreaSteps.Corner> anchor = s.anchor();
        BlockPosition cell = r.ok() ? ((Picked.Area) r.target()).min() : null;
        UUID cellWorld = r.ok() ? r.target().world() : p.getWorld().getUID();
        Picked.Area box = cell != null && anchor.isPresent() ? box(f, anchor.get(), cell) : null;
        PickVerdict v = verdict(f, r, box, anchor, cellWorld);
        String refused = null;
        if (!v.allowed()) refused = PickerRuntime.plain(p, v.reason());
        else if (box != null && guarded(f)) {
            AreaGuard.Answer g = guardPreview(f, box.world()).ask(box, PREVIEW_CHUNKS);
            if (g.state() == AreaGuard.State.DENIED) refused = PickerRuntime.lang().plain(p, "pickers.world.area.reason.protected");
        }
        if (refused != null) {
            f.state.hold(refused, now);
            f.lastBar = null;
            return refused;
        }
        int number = s.choosing();
        s.set(new AreaSteps.Corner(cellWorld, cell));
        f.verdict = null;
        f.box = box;
        f.lastBar = null;
        WorldPickRuntime.send(p, PickerRuntime.lang().component(p, "pickers.world.area.corner-set", "corner", number, "x", cell.x(), "y", cell.y(), "z", cell.z()));
        if (s.step() == AreaSteps.Step.CONFIRM) openConfirm(f);
        return null;
    }

    /** "Redo corner 1" in the options while corner 2 is chosen: start over. */
    void redoFirstFromOptions(WorldPickFlow f) {
        if (f.area.redoFirst()) {
            f.verdict = null;
            f.box = null;
            WorldPickRuntime.send(f.viewer, PickerRuntime.lang().component(f.viewer, "pickers.world.area.corner-cleared"));
        }
    }

    // ------------------------------------------------------------------ confirm view

    /** Opens the confirm view for both corners: the final box, its limits and check, and (BUILD) the full protection check. */
    void openConfirm(WorldPickFlow f) {
        if (f.ended) return;
        long now = System.currentTimeMillis();
        Picked.Area box = finalBox(f);
        if (box == null) return;
        f.box = box;
        f.confirmMessage = null;
        f.verdict = confirmVerdict(f, box);
        restartScanIfNeeded(f, box, now, false);
        f.state.openConfirm(now);
        WorldPickRuntime.actionBar(f.viewer, Component.empty());
        AreaConfirmView view = new AreaConfirmView(rt, this, f);
        f.confirm = view;
        if (!view.open()) {
            f.confirm = null;
            f.state.resume(System.currentTimeMillis());
            WorldPickRuntime.send(f.viewer, PickerRuntime.lang().component(f.viewer, "panel.unavailable"));
        }
    }

    /** Limits and the plugin's check of the final box (candidate: the box, first corner = corner 1). */
    PickVerdict confirmVerdict(WorldPickFlow f, Picked.Area box) {
        AreaSteps s = f.area;
        AreaRules rules = f.pick.area();
        Player p = f.viewer;
        AreaLimits.Verdict limits = AreaLimits.check(box, s.first().world().equals(s.second().world()), rules, rt.config().area(), s.fullHeight(rules));
        if (!limits.ok()) return PickVerdict.deny(PickerRuntime.lang().plain(p, "pickers.world.area.reason." + limits.reason(), limits.pairs()));
        return rt.check(f, candidate(f, box, Optional.of(s.first().cell())));
    }

    private void restartScanIfNeeded(WorldPickFlow f, Picked.Area box, long now, boolean force) {
        if (!guarded(f) || !f.verdict.allowed()) {
            if (f.scan != null) f.scan.cancel();
            f.scan = null;
            return;
        }
        boolean stale = f.scan == null || !f.scan.box().equals(box) || now - f.scanStartedAt > SCAN_FRESH_MS
                || f.scan.answer().state() == AreaGuard.State.FAILED;
        if (!stale && !force) return;
        if (f.scan != null) f.scan.cancel();
        AreaGuard.Scan[] made = new AreaGuard.Scan[1];
        f.scan = made[0] = new AreaGuard.Scan(box, asker(f, box.world()), task -> {
            try { Bukkit.getScheduler().runTaskAsynchronously(rt.core, task); }
            // never inline: the cell test of a large box must not run on the server thread
            catch (RuntimeException | LinkageError disabled) { if (made[0] != null) made[0].fail("guard-failed"); }
        }, System::nanoTime, SCAN_DEADLINE_NANOS);
        f.scanStartedAt = now;
        f.scanShown = null;
        stepScan(f);
    }

    private void stepScan(WorldPickFlow f) {
        if (f.scan == null) return;
        try { f.scan.step(SCAN_BUDGET_NANOS, SCAN_CHUNKS); }
        catch (RuntimeException | LinkageError failure) {
            DebugMessages.low("[Pickers] Area protection check failed: " + failure);
            f.scan.cancel();
        }
    }

    /** What the confirm view and "Use this area" say about the box: null = ready; else the reason (and whether it is only "still checking"). */
    record Status(String text, boolean checking) {
        boolean ready() { return text == null; }
    }

    Status status(WorldPickFlow f) {
        Player p = f.viewer;
        Lang lang = PickerRuntime.lang();
        if (f.confirmMessage != null) return new Status(f.confirmMessage, false);
        if (f.verdict != null && !f.verdict.allowed()) return new Status(PickerRuntime.plain(p, f.verdict.reason()), false);
        if (f.scan == null) return new Status(null, false);
        AreaGuard.Answer a = f.scan.answer();
        return switch (a.state()) {
            case ALLOWED -> new Status(null, false);
            case PENDING, PARTIAL -> new Status(lang.plain(p, "pickers.world.area.reason.checking"), true);
            case DENIED -> a.cell() != null
                    ? new Status(lang.plain(p, "pickers.world.area.reason.partial", "x", a.cell().x(), "y", a.cell().y(), "z", a.cell().z()), false)
                    : new Status(lang.plain(p, "pickers.world.area.reason.protected"), false);
            case FAILED -> new Status(lang.plain(p, "pickers.world.area.reason." + ("too-slow".equals(a.reason()) ? "too-slow" : "guard-failed")), false);
        };
    }

    /** A button of the confirm view (one tick after the click, the view still open). */
    void confirmAction(WorldPickFlow f, AreaConfirmLayout.Action action) {
        if (f.ended || rt.flow(f.viewer) != f) return;
        long now = System.currentTimeMillis();
        f.state.touch(now);   // a click in the confirm view counts as activity for the idle timeout
        switch (action) {
            case INFO, BLOCKS -> { }
            case USE -> use(f);
            case REDO_FIRST, REDO_SECOND -> {
                boolean done = action == AreaConfirmLayout.Action.REDO_FIRST ? f.area.redoFirst() : f.area.redoSecond();
                // the old box's protection check stops (it would go on asking chunks and testing cells meanwhile)
                if (f.scan != null) f.scan.cancel();
                f.scan = null;
                closeConfirm(f);
                f.verdict = null;
                f.state.resume(now);
                if (done) WorldPickRuntime.send(f.viewer, PickerRuntime.lang().component(f.viewer, "pickers.world.area.redo",
                        "corner", action == AreaConfirmLayout.Action.REDO_FIRST ? 1 : 2));
            }
            case HEIGHT -> {
                if (canExpand(f) && f.area.toggleHeight(f.pick.area())) {
                    Picked.Area box = finalBox(f);
                    f.box = box;
                    f.confirmMessage = null;
                    f.verdict = confirmVerdict(f, box);
                    restartScanIfNeeded(f, box, now, false);
                    f.lastTitle = null;
                    if (f.confirm != null) f.confirm.render();
                }
            }
            case CANCEL -> {
                closeConfirm(f);
                rt.back(f);
            }
        }
    }

    /** ESC or another close of the confirm view: the pick stays in the confirm step (both corners kept). */
    void confirmClosed(WorldPickFlow f) {
        if (f.ended || rt.flow(f.viewer) != f) return;
        f.confirm = null;
        if (rt.finish(f, WorldPickEnds.Cause.CONFIRM_CLOSED, null)) return;   // the end rules keep it running today
        f.state.resume(System.currentTimeMillis());
        f.lastBar = null;
        WorldPickRuntime.send(f.viewer, PickerRuntime.lang().component(f.viewer, "pickers.world.area.confirm-closed"));
    }

    private void closeConfirm(WorldPickFlow f) {
        AreaConfirmView v = f.confirm;
        f.confirm = null;
        if (v != null) v.close();
    }

    /**
     * "Use this area": the limits and the plugin's check again (the world may have changed), the protection answer
     * (asked again when old), then the plugin's handler. Ok ends the pick with DONE and goes back; an error keeps the
     * confirm view open with the reason; a handler that throws still goes back, with {@code panel.failed}.
     */
    void use(WorldPickFlow f) {
        long now = System.currentTimeMillis();
        Player p = f.viewer;
        Picked.Area box = finalBox(f);
        if (box == null) return;
        f.box = box;
        f.confirmMessage = null;
        f.verdict = confirmVerdict(f, box);
        restartScanIfNeeded(f, box, now, false);
        Status st = status(f);
        if (!st.ready()) {
            WorldPickRuntime.send(p, Component.text(st.text(), st.checking() ? NamedTextColor.YELLOW : NamedTextColor.RED));
            if (f.confirm != null) f.confirm.render();
            return;
        }
        Result result;
        f.handingOver = true;
        try {
            result = f.pick.onPick().picked(p, candidate(f, box, Optional.of(f.area.first().cell())));
        } catch (RuntimeException | LinkageError failure) {
            f.handingOver = false;
            DebugMessages.high("[Pickers] Pick handler of " + f.owner.getName() + " failed", failure);
            rt.finish(f, WorldPickEnds.Cause.HANDLER_FAILED, Result.error(PickerRuntime.lang().plain(p, "panel.failed")));
            return;
        } finally {
            f.handingOver = false;
        }
        if (f.ended) return;   // onPick started another pick or cancelled this one
        if (result == null || result.isPending()) result = Result.ok();
        if (!result.success()) {
            f.confirmMessage = result.message().isEmpty() ? PickerRuntime.lang().plain(p, "pickers.world.reason.refused") : PickerRuntime.plain(p, result.message());
            WorldPickRuntime.send(p, Component.text(f.confirmMessage, NamedTextColor.RED));
            if (f.confirm != null) f.confirm.render();
            return;
        }
        rt.finish(f, WorldPickEnds.Cause.CHOSEN, result);
    }

    /** The lines of the confirm view's summary. */
    List<Component> summary(WorldPickFlow f) {
        Player p = f.viewer;
        Lang lang = PickerRuntime.lang();
        List<Component> out = new ArrayList<>();
        Picked.Area box = f.box;
        if (box == null) return out;
        World w = world(box.world());
        out.add(lang.component(p, "pickers.world.area.confirm.from", "from", box.min().x() + " " + box.min().y() + " " + box.min().z(),
                "to", box.max().x() + " " + box.max().y() + " " + box.max().z()));
        out.add(lang.component(p, "pickers.world.area.confirm.size", "size", AreaLimits.size(box), "blocks", AreaLimits.count(box.volume())));
        out.add(lang.component(p, "pickers.world.area.confirm.world", "world", w == null ? "?" : w.getName()));
        AreaRules rules = f.pick.area();
        String height = f.area.fullHeight(rules) ? "height-full" : rules.height() == AreaHeight.FIXED ? "height-fixed" : "height-picked";
        out.add(lang.component(p, "pickers.world.area.confirm." + height, "height", rules.fixedHeight()));
        Status st = status(f);
        out.add(st.ready() ? lang.component(p, "pickers.world.area.confirm.ready")
                : Component.text(st.text(), st.checking() ? NamedTextColor.YELLOW : NamedTextColor.RED));
        if (f.scan != null) f.scanShown = f.scan.answer().state();
        return out;
    }
}
