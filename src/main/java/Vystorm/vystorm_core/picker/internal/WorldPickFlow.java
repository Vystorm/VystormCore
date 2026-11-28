package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PickEnd;
import Vystorm.vystorm_core.picker.PickFlow;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.WorldPick;
import Vystorm.vystorm_core.visual.Point3;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** One running world pick: the pick, where it returns to, its timing state and what the preview shows. Server thread. Core-internal. */
public final class WorldPickFlow implements PickFlow {
    final Plugin owner;
    final Player viewer;
    final WorldPick pick;
    final PickReturn back;
    final long startedAt;
    final int reach;
    final UUID startWorld;
    final WorldPickState state;
    final Consumer<WorldPickFlow> canceller;
    boolean ended;
    PickEnd end;
    /** onPick runs: a new pick started from it ends this flow as DONE. */
    boolean handingOver;
    /** What the last preview pass aimed at, its verdict and the plugin's extra boxes for it. */
    WorldTargets.Resolution aimed;
    PickVerdict verdict;
    List<WorldPick.Box> extra = List.of();
    /** The boss bar (an Adventure BossBar, kept as Object so this class loads without it). */
    Object bossBar;
    String lastBar;
    long lastBarAt;
    WorldPickOptions options;
    /** Last point the preview drew from (tests and the admin dump). */
    Point3 lastEye;

    // ------------------------------------------------------------------ AREA only (null otherwise)
    /** The two corners and the confirm step. */
    final AreaSteps area;
    /** The box the preview shows (corner so far to the aimed cell, or the final box). */
    Vystorm.vystorm_core.picker.Picked.Area box;
    /** BUILD: chunk answers of the live preview, and the last answer. */
    AreaGuard.Preview guardPreview;
    AreaGuard.Answer guardAnswer;
    /** BUILD: the full check of the confirm view's box, and when it started (ms). */
    AreaGuard.Scan scan;
    long scanStartedAt;
    AreaGuard.State scanShown;
    /** The open confirm view. */
    AreaConfirmView confirm;
    /** A handler refusal shown in the confirm view (null without one). */
    String confirmMessage;
    /** Last boss bar title sent (plain), so the bar is renamed only on a change. */
    String lastTitle;

    WorldPickFlow(Plugin owner, Player viewer, WorldPick pick, PickReturn back, int reach, UUID startWorld, long now, Consumer<WorldPickFlow> canceller) {
        this.owner = owner;
        this.viewer = viewer;
        this.pick = pick;
        this.back = back;
        this.reach = reach;
        this.startWorld = startWorld;
        this.startedAt = now;
        this.state = new WorldPickState(now, pick.timeout().toMillis());
        this.canceller = canceller;
        this.area = pick.kind() == PickKind.AREA ? new AreaSteps() : null;
    }

    /** The two-corner steps of an AREA pick; null for single targets. */
    public AreaSteps area() { return area; }

    @Override public Plugin owner() { return owner; }
    @Override public Player viewer() { return viewer; }
    @Override public PickKind kind() { return pick.kind(); }
    @Override public long startedAt() { return startedAt; }
    @Override public boolean active() { return !ended; }

    @Override public boolean cancel() {
        if (ended) return false;
        canceller.accept(this);
        return true;
    }

    public WorldPick pick() { return pick; }
    public PickEnd end() { return end; }
    public WorldPickState state() { return state; }
    public int reach() { return reach; }
}
