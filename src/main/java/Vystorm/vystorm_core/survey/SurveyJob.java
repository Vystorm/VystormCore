package Vystorm.vystorm_core.survey;

import java.util.UUID;

/**
 * A pre-compute job: surveys every chunk of a square around a centre, ring by ring, and stores the records. Its
 * progress file ({@code survey/<world-uuid>/job.properties} in Core's folder) survives a restart: a running job
 * continues after the next start, a paused one stays paused. Methods may be called from any thread.
 *
 * @since 0.68.0
 */
public interface SurveyJob {
    /** State of a job. */
    enum State { RUNNING, PAUSED, FINISHED, CANCELLED }

    /**
     * Progress of a job.
     *
     * @param done            chunks finished (surveyed, already up to date or skipped)
     * @param total           chunks of the job
     * @param skipped         chunks that could not be surveyed (not generated, outside the world border)
     * @param chunksPerSecond rate over the last minute
     * @param etaSeconds      expected seconds until the end (-1 unknown)
     */
    record Progress(UUID worldId, State state, long done, long total, long skipped, double chunksPerSecond, long etaSeconds) {
        /** Share done, 0 to 1. */
        public double fraction() { return total == 0 ? 1 : Math.min(1, (double) done / total); }
    }

    UUID worldId();

    JobOptions options();

    void pause();

    void resume();

    void cancel();

    Progress progress();
}
