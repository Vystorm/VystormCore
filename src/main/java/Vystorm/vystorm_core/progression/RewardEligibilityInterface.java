package Vystorm.vystorm_core.progression;

import org.bukkit.entity.Entity;

/**
 * Manager view of {@link RewardEligibility}, reached through {@code api.loot().eligibility()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link RewardEligibility}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface RewardEligibilityInterface {
    /** The delegating default. Plugins use {@code api.loot().eligibility()}. */
    RewardEligibilityInterface DEFAULT = new RewardEligibilityInterface() {};

    /** See {@link RewardEligibility#allowed}. */
    default boolean allowed(Entity entity) { return RewardEligibility.allowed(entity); }
}
