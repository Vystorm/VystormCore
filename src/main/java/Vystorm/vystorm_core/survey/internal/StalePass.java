package Vystorm.vystorm_core.survey.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The end of a pre-compute job: chunks of the job's area that went stale while it ran (world generation changes
 * blocks of its own fresh chunks: falling sand, flowing water, growing plants) are surveyed again, in ring order, in
 * at most {@link #MAX_PASSES} passes; the job ends when a pass finds none or after the last pass.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class StalePass {
    public static final int MAX_PASSES = 5;

    private StalePass() {}

    /**
     * The stale chunks of the plan to survey again in this pass, as chunk keys in ring order; empty when the job
     * is done ({@code passesDone} reached the limit or nothing of the plan is stale).
     */
    public static List<Long> next(Collection<Long> stale, RingPlan plan, int passesDone) {
        if (passesDone >= MAX_PASSES) return List.of();
        List<long[]> found = new ArrayList<>();
        for (long key : stale) {
            long index = plan.indexOf(RoomJoin.keyX(key), RoomJoin.keyZ(key));
            if (index >= 0) found.add(new long[] {index, key});
        }
        found.sort((a, b) -> Long.compare(a[0], b[0]));
        List<Long> out = new ArrayList<>(found.size());
        for (long[] f : found) out.add(f[1]);
        return out;
    }

    /** How many chunks of the plan are stale (for the final count). */
    public static int count(Collection<Long> stale, RingPlan plan) {
        int n = 0;
        for (long key : stale) if (plan.contains(RoomJoin.keyX(key), RoomJoin.keyZ(key))) n++;
        return n;
    }
}
