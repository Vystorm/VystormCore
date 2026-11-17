package Vystorm.vystorm_core.mob;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.mythic.MythicMobsBridge;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import Vystorm.vystorm_core.mob.MobRefs.*;

/**
 * Manager view of {@link MobRefs}, reached through {@code api.mobs().refs()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MobRefs}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MobRefsInterface {
    /** The delegating default. Plugins use {@code api.mobs().refs()}. */
    MobRefsInterface DEFAULT = new MobRefsInterface() {};

    /** See {@link MobRefs#id}. */
    default String id(String ref) { return MobRefs.id(ref); }

    /** See {@link MobRefs#vystormKnows}. */
    default boolean vystormKnows(String ref) { return MobRefs.vystormKnows(ref); }

    /** See {@link MobRefs#source}. */
    default Optional<Source> source(String ref) { return MobRefs.source(ref); }

    /** See {@link MobRefs#spawn}. */
    default Optional<Entity> spawn(String ref, Location location, int level, String sourcePlugin) { return MobRefs.spawn(ref, location, level, sourcePlugin); }
}
