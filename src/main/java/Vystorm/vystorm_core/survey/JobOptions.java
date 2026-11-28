package Vystorm.vystorm_core.survey;

/**
 * Options of a pre-compute job.
 *
 * @param generate         generate missing chunks (Paper: asynchronously; Spigot: one at a time on the server thread
 *                         inside the tick budget); false skips chunks that were never generated
 * @param workers          worker threads for the surveys (0 = Core's pool size)
 * @param snapshotsPerTick most chunk snapshots taken per tick
 * @param millisPerTick    most milliseconds of server-thread time per tick
 * @param tpsFloor         below this TPS the job waits
 * @param yieldToPlayers   while players are online the job uses a quarter of the budget
 * @since 0.68.0
 */
public record JobOptions(boolean generate, int workers, int snapshotsPerTick, double millisPerTick, double tpsFloor,
                         boolean yieldToPlayers) {
    public JobOptions {
        if (workers < 0 || workers > 64) throw new IllegalArgumentException("workers " + workers);
        if (snapshotsPerTick < 1 || snapshotsPerTick > 256) throw new IllegalArgumentException("snapshotsPerTick " + snapshotsPerTick);
        if (!(millisPerTick > 0) || millisPerTick > 45) throw new IllegalArgumentException("millisPerTick " + millisPerTick);
        if (!(tpsFloor >= 0) || tpsFloor > 20) throw new IllegalArgumentException("tpsFloor " + tpsFloor);
    }

    /** Generate missing chunks, Core's workers, 9 snapshots and 5 ms per tick, wait below 18 TPS, yield to players. */
    public static JobOptions defaults() { return new JobOptions(true, 0, 9, 5.0, 18.0, true); }

    public JobOptions withGenerate(boolean value) { return new JobOptions(value, workers, snapshotsPerTick, millisPerTick, tpsFloor, yieldToPlayers); }

    public JobOptions withWorkers(int value) { return new JobOptions(generate, value, snapshotsPerTick, millisPerTick, tpsFloor, yieldToPlayers); }

    public JobOptions withSnapshotsPerTick(int value) { return new JobOptions(generate, workers, value, millisPerTick, tpsFloor, yieldToPlayers); }

    public JobOptions withMillisPerTick(double value) { return new JobOptions(generate, workers, snapshotsPerTick, value, tpsFloor, yieldToPlayers); }

    public JobOptions withTpsFloor(double value) { return new JobOptions(generate, workers, snapshotsPerTick, millisPerTick, value, yieldToPlayers); }

    public JobOptions withYieldToPlayers(boolean value) { return new JobOptions(generate, workers, snapshotsPerTick, millisPerTick, tpsFloor, value); }
}
