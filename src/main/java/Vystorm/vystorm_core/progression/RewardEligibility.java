package Vystorm.vystorm_core.progression;

import org.bukkit.entity.Entity;

/**
 * Shared opt-out for practice sessions. Server-thread only. Providers own marker lifecycle and recovery.
 * <p>Preferred access: {@code api.loot().eligibility()}.
 */
public final class RewardEligibility {
    /** Persistent entity/player marker: no progression rewards while present. */
    public static final String PRACTICE_TAG = "vystorm_practice";
    private RewardEligibility() {}
    public static boolean allowed(Entity entity) {
        return entity != null && !entity.getScoreboardTags().contains(PRACTICE_TAG);
    }
}
