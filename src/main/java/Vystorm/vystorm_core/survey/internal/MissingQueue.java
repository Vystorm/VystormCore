package Vystorm.vystorm_core.survey.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loaded chunks without a usable record, waiting for a survey. Never one survey per load burst: the runtime takes a
 * few per second, the nearest to a player first, and only chunks within the view distance of a player (chunks that
 * the surveys themselves load as neighbours are never queued for their own sake, so the queue cannot crawl outwards).
 * Thread safe.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class MissingQueue {
    private final Set<Long> keys = new LinkedHashSet<>();
    private long added;

    public synchronized void add(long key) { if (keys.add(key)) added++; }

    public synchronized void remove(long key) { keys.remove(key); }

    public synchronized int size() { return keys.size(); }

    /** Chunks ever queued (for the status). */
    public synchronized long added() { return added; }

    /**
     * Up to {@code max} chunks, nearest to a player first (Chebyshev distance in chunks), only those within
     * {@code viewDistance} of a player; the picked ones leave the queue.
     *
     * @param players chunk x, z of each player in the world ({@code {x0, z0, x1, z1, ...}})
     */
    public synchronized List<Long> pick(int max, int[] players, int viewDistance) {
        if (max <= 0 || players.length == 0 || keys.isEmpty()) return List.of();
        List<long[]> near = new ArrayList<>();
        for (long key : keys) {
            int x = RoomJoin.keyX(key), z = RoomJoin.keyZ(key), best = Integer.MAX_VALUE;
            for (int i = 0; i + 1 < players.length; i += 2)
                best = Math.min(best, Math.max(Math.abs(x - players[i]), Math.abs(z - players[i + 1])));
            if (best <= viewDistance) near.add(new long[] {best, key});
        }
        near.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        List<Long> out = new ArrayList<>(Math.min(max, near.size()));
        for (int i = 0; i < near.size() && out.size() < max; i++) {
            out.add(near.get(i)[1]);
            keys.remove(near.get(i)[1]);
        }
        return out;
    }
}
