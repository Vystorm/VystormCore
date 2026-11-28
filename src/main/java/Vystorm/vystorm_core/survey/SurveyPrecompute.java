package Vystorm.vystorm_core.survey;

import java.util.List;
import java.util.Optional;
import org.bukkit.World;

/**
 * Pre-compute jobs (an overnight run that surveys a whole area): {@code api.survey().precompute()}. One job per
 * world; admins use {@code /vadmin survey start|pause|resume|cancel|status}.
 *
 * @since 0.68.0
 */
public interface SurveyPrecompute {
    /**
     * Starts a job over the square {@code centre ± radius} (blocks), ring by ring from the centre. Replaces a finished
     * or cancelled job of the world; fails with {@link IllegalStateException} while one runs or is paused, or while
     * the survey is off.
     */
    SurveyJob start(World world, int centerX, int centerZ, int radius, JobOptions options);

    /** The job of a world (also a finished one, until the next start). */
    Optional<SurveyJob> job(World world);

    /** All jobs. */
    List<SurveyJob> jobs();
}
