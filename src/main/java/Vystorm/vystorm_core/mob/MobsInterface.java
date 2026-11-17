package Vystorm.vystorm_core.mob;

import Vystorm.vystorm_core.mob.extension.MobExtensionsInterface;
import Vystorm.vystorm_core.mythic.MythicMobsBridgeInterface;

/**
 * Mob manager, reached through {@code api.mobs()}: the mob provider directly ({@link MobServices}) and the parts
 * around it.
 *
 * @since 0.64.0
 */
public interface MobsInterface extends MobServicesInterface {
    /** Mechanics, targeters, conditions, triggers, brains, traits and effects of plugins ({@link Vystorm.vystorm_core.mob.extension.MobExtensions}). */
    default MobExtensionsInterface extensions() { return MobExtensionsInterface.DEFAULT; }

    /** Mob references: Vystorm mobs before MythicMobs ({@link MobRefs}). */
    default MobRefsInterface refs() { return MobRefsInterface.DEFAULT; }

    /** Context layers for spawning ({@link MobContextServices}). */
    default MobContextServicesInterface context() { return MobContextServicesInterface.DEFAULT; }

    /** Who is friend or foe ({@link RelationServices}). */
    default RelationServicesInterface relations() { return RelationServicesInterface.DEFAULT; }

    /** MythicMobs, if installed ({@link Vystorm.vystorm_core.mythic.MythicMobsBridge}). */
    default MythicMobsBridgeInterface mythic() { return MythicMobsBridgeInterface.DEFAULT; }
}
