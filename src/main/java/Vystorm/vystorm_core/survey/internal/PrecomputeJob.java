package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.survey.JobOptions;
import Vystorm.vystorm_core.survey.SurveyJob;
import java.util.ArrayDeque;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * A pre-compute job: issues surveys in ring order from the server-thread tick, at most twice the worker count at a
 * time and only while the TPS stay above the floor. Chunks with an up-to-date record are counted as done without a
 * survey. The progress file is written every ten seconds and on pause, cancel and stop.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class PrecomputeJob implements SurveyJob {
    private final SurveyRuntime rt;
    final String worldName;
    private final JobState s;
    private final RingPlan plan;
    private final long total;
    private final TreeSet<Long> ahead = new TreeSet<>();
    private final AtomicInteger running = new AtomicInteger();
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private long cursor;
    /** End passes: stale chunks of the area surveyed again (see {@link StalePass}). */
    private final java.util.ArrayDeque<Long> passQueue = new java.util.ArrayDeque<>();
    private int passes;
    private long passWaitUntil;
    private int passSurveys;
    private long done;
    /** Start of the current running stretch (0 while not running) and the milliseconds before it. */
    private long runningSince;
    private long millisBefore;
    private long lastSave;
    volatile long finishedAt;

    PrecomputeJob(SurveyRuntime rt, String worldName, JobState state) {
        this.rt = rt;
        this.worldName = worldName;
        this.s = state;
        this.plan = state.plan();
        this.total = plan.count();
        this.cursor = state.next;
        this.done = state.next;
        this.millisBefore = state.millis;
        if (state.state == State.RUNNING) runningSince = System.currentTimeMillis();
    }

    /** Ends the running stretch (before a state change away from RUNNING). */
    private void settle() {
        if (runningSince == 0) return;
        millisBefore += System.currentTimeMillis() - runningSince;
        runningSince = 0;
    }

    @Override public UUID worldId() { return s.worldId; }

    @Override public JobOptions options() { return s.options; }

    State state() { return s.state; }

    int centerX() { return s.centerX; }

    int centerZ() { return s.centerZ; }

    int radius() { return s.radius; }

    int chunkRadius() { return plan.chunkRadius(); }

    boolean contains(int chunkX, int chunkZ) { return plan.contains(chunkX, chunkZ); }

    @Override
    public synchronized void pause() {
        if (s.state != State.RUNNING) return;
        settle();
        s.state = State.PAUSED;
        saveNow();
    }

    @Override
    public synchronized void resume() {
        if (s.state != State.PAUSED) return;
        s.state = State.RUNNING;
        runningSince = System.currentTimeMillis();
        saveNow();
    }

    @Override
    public synchronized void cancel() {
        if (s.state == State.FINISHED || s.state == State.CANCELLED) return;
        settle();
        s.state = State.CANCELLED;
        finishedAt = System.currentTimeMillis();
        saveNow();
    }

    @Override
    public synchronized Progress progress() {
        long now = System.currentTimeMillis();
        double rate = 0;
        long[] first = samples.peekFirst();
        if (first != null && now - first[0] > 1000) rate = (done - first[1]) * 1000.0 / (now - first[0]);
        long eta = rate > 0 ? (long) Math.ceil((total - done) / rate) : -1;
        if (s.state == State.FINISHED) eta = 0;
        return new Progress(s.worldId, s.state, done, total, s.skipped, rate, eta);
    }

    /** Server thread, every tick. */
    void tick() {
        State state = s.state;
        if (state != State.RUNNING) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            samples.addLast(new long[] {now, done});
            while (samples.size() > 2 && now - samples.peekFirst()[0] > 60_000) samples.removeFirst();
        }
        World world = Bukkit.getWorld(s.worldId);
        if (world == null) return;
        SurveyRuntime.WorldState ws = rt.state(world);
        synchronized (this) {
            if (cursor >= total && passQueue.isEmpty() && running.get() == 0) {
                // world generation changes its own fresh chunks (sand falls, water flows): wait 5 s, then survey the
                // chunks of the area that went stale again, at most five passes
                if (passWaitUntil == 0) { passWaitUntil = now + 5_000; return; }
                if (now < passWaitUntil) return;
                passWaitUntil = 0;
                java.util.List<Long> next = StalePass.next(ws.stale, plan, passes);
                if (!next.isEmpty()) {
                    passes++;
                    passQueue.addAll(next);
                    passSurveys += next.size();
                    rt.core.getLogger().info("World survey job in " + worldName + ": pass " + passes + " surveys " + next.size() + " chunks again that changed during the job");
                    return;
                }
                settle();
                s.state = State.FINISHED;
                finishedAt = now;
                saveNow();
                rt.core.getLogger().info("World survey job in " + worldName + " finished: " + done + " chunks, " + s.skipped + " skipped, "
                        + passSurveys + " surveyed again in " + passes + " end passes, " + StalePass.count(ws.stale, plan) + " stale left");
                rt.core.getLogger().info("World survey work: " + rt.breakdown());
                return;
            }
        }
        double tps;
        try { tps = PlatformCompat.getTPS()[0]; } catch (RuntimeException | LinkageError unknown) { tps = 20; }
        if (tps < s.options.tpsFloor()) return;
        int limit = (s.options.workers() > 0 ? s.options.workers() : rt.poolSize) * 2;
        int perTick = s.options.snapshotsPerTick();
        if (s.options.yieldToPlayers() && !Bukkit.getOnlinePlayers().isEmpty()) perTick = Math.max(1, perTick / 4);
        int issued = 0;
        while (issued < perTick && running.get() < limit && cursor < total) {
            long index = cursor++;
            int[] c = plan.chunkAt(index);
            running.incrementAndGet();
            issued++;
            rt.request(ws, world, c[0], c[1], s.options.generate(), false).whenComplete((ok, failure) -> finished(index, failure != null));
        }
        while (issued < perTick && running.get() < limit && cursor >= total && !passQueue.isEmpty()) {
            long key;
            synchronized (this) { key = passQueue.poll(); }
            running.incrementAndGet();
            issued++;
            rt.request(ws, world, RoomJoin.keyX(key), RoomJoin.keyZ(key), false, true).whenComplete((ok, failure) -> running.decrementAndGet());
        }
        if (now - lastSave > 10_000) saveNow();
    }

    private synchronized void finished(long index, boolean failed) {
        running.decrementAndGet();
        done++;
        if (failed) s.skipped++;
        ahead.add(index);
        while (!ahead.isEmpty() && ahead.first() == s.next) {
            ahead.pollFirst();
            s.next++;
        }
    }

    synchronized void saveNow() {
        lastSave = System.currentTimeMillis();
        s.millis = millisBefore + (runningSince == 0 ? 0 : lastSave - runningSince);
        rt.writeJob(s.worldId, s.toText());
    }
}
