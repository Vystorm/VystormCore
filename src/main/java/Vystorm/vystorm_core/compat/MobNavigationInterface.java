package Vystorm.vystorm_core.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.entity.Mob;

/**
 * Manager view of {@link MobNavigation}, reached through {@code api.compat().navigation()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MobNavigation}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MobNavigationInterface {
    /** The delegating default. Plugins use {@code api.compat().navigation()}. */
    MobNavigationInterface DEFAULT = new MobNavigationInterface() {};

    /** See {@link MobNavigation#moveTo}. */
    default boolean moveTo(Mob mob, Location target, double speed) { return MobNavigation.moveTo(mob, target, speed); }

    /** See {@link MobNavigation#moveTo}. */
    default boolean moveTo(Mob mob, org.bukkit.entity.LivingEntity target, double speed) { return MobNavigation.moveTo(mob, target, speed); }

    /** See {@link MobNavigation#stop}. */
    default void stop(Mob mob) { MobNavigation.stop(mob); }

    /** See {@link MobNavigation#clearTargets}. */
    default void clearTargets(Mob mob) { MobNavigation.clearTargets(mob); }
}
