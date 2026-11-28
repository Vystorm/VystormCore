package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.blocks.CellTest;
import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * {@link Vystorm.vystorm_core.picker.PickProtection#BUILD} for a whole box (design 4.6) without loading a chunk and
 * without freezing the server thread:
 * <ul>
 *   <li>Core asks {@code api.area().mayEdit(..)} once per chunk the box touches, with chunk coordinates and the box's
 *       height only ({@link Asker}); no chunk is read or loaded (Core's regions answer from memory, and edit guards
 *       must not do I/O by their contract). A 512 x 512 box touches at most 33 x 33 chunks.</li>
 *   <li>The preview ({@link Preview}) asks at most a budget of new chunks per pass and keeps the answers per chunk for
 *       {@value #CACHE_MS} ms, so a growing box only asks its new chunks: any deny is red, any partial yellow, chunks
 *       not asked yet are "pending".</li>
 *   <li>The full check of the confirm view ({@link Scan}) asks the chunks in slices with a time budget per server tick;
 *       a partial chunk is then tested cell by cell OFF the server thread (a {@link CellTest} is pure and thread-safe
 *       by its contract), the first denied cell wins, a deadline ends a test that takes too long, and an ended pick
 *       cancels it.</li>
 * </ul>
 * Reasons: the guard's key ({@code regions}, a plugin's key), {@code partial} (a denied cell in a partial chunk),
 * {@code guard-failed}, {@code too-slow}, {@code cancelled}. Core-internal.
 */
public final class AreaGuard {
    private AreaGuard() {}

    /** Answers of the preview are kept this long. */
    static final long CACHE_MS = 10_000;
    /** How often the cell test looks at the deadline and the cancel flag. */
    static final int CHECK_EVERY = 4096;

    /** {@code mayEdit} for one chunk and a height range. Server thread. */
    @FunctionalInterface
    public interface Asker { EditVerdict ask(int chunkX, int chunkZ, int minY, int maxY); }

    public enum State { PENDING, ALLOWED, PARTIAL, DENIED, FAILED }

    /** The answer so far; {@code reason} and {@code cell} only for DENIED and FAILED ({@code cell} only for a denied cell). */
    public record Answer(State state, String reason, BlockPosition cell) {
        static final Answer PENDING = new Answer(State.PENDING, null, null), ALLOWED = new Answer(State.ALLOWED, null, null),
                PARTIAL = new Answer(State.PARTIAL, null, null);
        static Answer denied(String reason, BlockPosition cell) { return new Answer(State.DENIED, reason == null || reason.isEmpty() ? "unknown" : reason, cell); }
        static Answer failed(String reason) { return new Answer(State.FAILED, reason, null); }
        /** Whether the area may be used. */
        public boolean allowed() { return state == State.ALLOWED; }
    }

    /** The chunks the box touches, row by row (x outer), as {chunkX, chunkZ}. */
    public static List<int[]> chunks(Picked.Area box) {
        List<int[]> out = new ArrayList<>();
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++)
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) out.add(new int[] {cx, cz});
        return out;
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private static EditVerdict safeAsk(Asker asker, int cx, int cz, int minY, int maxY) {
        try {
            EditVerdict v = asker.ask(cx, cz, minY, maxY);
            return v == null ? new EditVerdict.Allow() : v;
        } catch (RuntimeException | LinkageError failure) {
            return new EditVerdict.Deny("guard-failed");
        }
    }

    // ------------------------------------------------------------------ preview

    /** Chunk-level answer for the live preview, with a per-flow cache. Server thread. */
    public static final class Preview {
        private final Asker asker;
        private final LongSupplier clock;
        private final Map<Long, EditVerdict> cache = new HashMap<>();
        private long cacheStart;
        private int cacheMinY = Integer.MIN_VALUE, cacheMaxY = Integer.MIN_VALUE;

        public Preview(Asker asker, LongSupplier clockMs) {
            this.asker = asker;
            this.clock = clockMs;
            this.cacheStart = clockMs.getAsLong();
        }

        /** Asks at most {@code budget} chunks that are not in the cache yet. */
        public Answer ask(Picked.Area box, int budget) {
            long now = clock.getAsLong();
            if (now - cacheStart > CACHE_MS || box.min().y() != cacheMinY || box.max().y() != cacheMaxY) {
                cache.clear();
                cacheStart = now;
                cacheMinY = box.min().y();
                cacheMaxY = box.max().y();
            }
            int asked = 0;
            boolean pending = false, partial = false;
            for (int[] c : chunks(box)) {
                EditVerdict v = cache.get(key(c[0], c[1]));
                if (v == null) {
                    if (asked >= budget) { pending = true; continue; }
                    v = safeAsk(asker, c[0], c[1], box.min().y(), box.max().y());
                    cache.put(key(c[0], c[1]), v);
                    asked++;
                }
                if (v instanceof EditVerdict.Deny d) return Answer.denied(d.reasonKey(), null);
                if (v instanceof EditVerdict.Partial) partial = true;
            }
            return pending ? Answer.PENDING : partial ? Answer.PARTIAL : Answer.ALLOWED;
        }
    }

    // ------------------------------------------------------------------ the full check

    /** The full check of one box for the confirm view. {@link #step} on the server thread; the cell test runs on {@code cells}. */
    public static final class Scan {
        private final Picked.Area box;
        private final Asker asker;
        private final Executor cells;
        private final LongSupplier nanos;
        private final long deadlineAt;
        private final List<int[]> chunks;
        private final List<Object[]> partials = new ArrayList<>();
        private int next;
        private boolean submitted;
        private volatile boolean cancelled;
        private final java.util.concurrent.atomic.AtomicReference<Answer> answer = new java.util.concurrent.atomic.AtomicReference<>(Answer.PENDING);

        /** @param deadlineNanos how long the whole check may take, counted from now */
        public Scan(Picked.Area box, Asker asker, Executor cells, LongSupplier nanos, long deadlineNanos) {
            this.box = box;
            this.asker = asker;
            this.cells = cells;
            this.nanos = nanos;
            long start = nanos.getAsLong();
            this.deadlineAt = deadlineNanos <= 0 || Long.MAX_VALUE - start < deadlineNanos ? Long.MAX_VALUE : start + deadlineNanos;
            this.chunks = chunks(box);
        }

        public Picked.Area box() { return box; }

        public Answer answer() { return answer.get(); }

        /** Ends a check that cannot go on (no thread for the cell test): FAILED with the reason, unless it had finished. */
        public void fail(String reason) { answer.compareAndSet(Answer.PENDING, Answer.failed(reason)); }

        /** Ends the check (the pick ended or the box changed): FAILED "cancelled" unless it had finished. */
        public void cancel() {
            cancelled = true;
            answer.compareAndSet(Answer.PENDING, Answer.failed("cancelled"));
        }

        /** Asks the next chunks until {@code budgetNanos} or {@code maxChunks} is used up; server thread. */
        public void step(long budgetNanos, int maxChunks) {
            if (cancelled || submitted || answer.get().state() != State.PENDING) return;
            long start = nanos.getAsLong();
            int asked = 0;
            while (next < chunks.size() && asked < maxChunks && nanos.getAsLong() - start < budgetNanos) {
                int[] c = chunks.get(next++);
                asked++;
                EditVerdict v = safeAsk(asker, c[0], c[1], box.min().y(), box.max().y());
                if (v instanceof EditVerdict.Deny d) { answer.compareAndSet(Answer.PENDING, Answer.denied(d.reasonKey(), null)); return; }
                if (v instanceof EditVerdict.Partial p) partials.add(new Object[] {c, p.allowed()});
            }
            if (next < chunks.size()) return;
            if (nanos.getAsLong() > deadlineAt) { answer.compareAndSet(Answer.PENDING, Answer.failed("too-slow")); return; }
            if (partials.isEmpty()) { answer.compareAndSet(Answer.PENDING, Answer.ALLOWED); return; }
            submitted = true;
            List<Object[]> work = List.copyOf(partials);
            cells.execute(() -> testCells(work));
        }

        /** Off the server thread: every cell of the box inside the partial chunks. */
        private void testCells(List<Object[]> work) {
            if (cancelled) return;
            int counter = 0;
            try {
                for (Object[] w : work) {
                    int[] c = (int[]) w[0];
                    CellTest test = (CellTest) w[1];
                    int x0 = Math.max(box.min().x(), c[0] << 4), x1 = Math.min(box.max().x(), (c[0] << 4) + 15);
                    int z0 = Math.max(box.min().z(), c[1] << 4), z1 = Math.min(box.max().z(), (c[1] << 4) + 15);
                    for (int y = box.min().y(); y <= box.max().y(); y++)
                        for (int x = x0; x <= x1; x++)
                            for (int z = z0; z <= z1; z++) {
                                if (++counter % CHECK_EVERY == 0) {
                                    if (cancelled) return;
                                    if (nanos.getAsLong() > deadlineAt) { finish(Answer.failed("too-slow")); return; }
                                }
                                if (!test.allowed(x, y, z)) { finish(Answer.denied("partial", new BlockPosition(x, y, z))); return; }
                            }
                }
                finish(Answer.ALLOWED);
            } catch (RuntimeException | LinkageError failure) {
                finish(Answer.failed("guard-failed"));
            }
        }

        /** Only from PENDING: a cancel that came first wins, and nothing overwrites a finished answer. */
        private void finish(Answer a) { answer.compareAndSet(Answer.PENDING, a); }
    }
}
