package Vystorm.vystorm_core.mob.extension;

/**
 * Verhaltensmodifikator. Kategorien der Utility-AI: ATTACK, ABILITY, MOVE, REPOSITION, FLEE, ASSIST, IDLE, OBJECTIVE.
 */
public interface MobTrait {
    String id();

    default double utilityMultiplier(String category) { return 1.0; }

    /** Modifiziert Basiswerte (health, damage, armor, movementSpeed, aggression, bravery). */
    default double stat(String stat, double value) { return value; }

    /** Zusätzlicher Threat-Beitrag für {@code quoteThreat}. */
    default double threat() { return 0; }
}
