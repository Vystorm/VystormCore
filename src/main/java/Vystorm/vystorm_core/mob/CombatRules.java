package Vystorm.vystorm_core.mob;

import java.util.Set;

/**
 * Welche Effekte Mob-Fähigkeiten und AOE im aktuellen Kontext haben dürfen. Blockschaden ist nie eine freie
 * AI-Entscheidung, sondern nur über {@link #mobBlockDamage} plus {@link #allowedBlockTargets} erlaubt
 * (z. B. CITY_WALL, GATE, DEFENSE_OBJECT, BARRICADE).
 */
public record CombatRules(boolean damagePlayers, boolean damageMobs, boolean damageNpcs, boolean damagePets,
                          boolean friendlyFire, boolean mobBlockDamage, Set<String> allowedBlockTargets,
                          double damageMultiplier) {
    public static final CombatRules OPEN_WORLD = new CombatRules(true, true, true, true, false, false, Set.of(), 1.0);
    public static final CombatRules ARENA = new CombatRules(true, true, true, true, false, false, Set.of(), 1.0);
    public static final CombatRules PROTECTED = new CombatRules(false, true, false, false, false, false, Set.of(), 1.0);

    public CombatRules {
        allowedBlockTargets = allowedBlockTargets == null ? Set.of() : Set.copyOf(allowedBlockTargets);
        if (!Double.isFinite(damageMultiplier) || damageMultiplier < 0) damageMultiplier = 1.0;
    }

    public static CombatRules siege(Set<String> targets) {
        return new CombatRules(true, true, true, true, false, true, targets, 1.0);
    }

    public boolean mayDamage(EntityRef.Kind kind) {
        return switch (kind) {
            case PLAYER -> damagePlayers;
            case NPC -> damageNpcs;
            case PET -> damagePets;
            default -> damageMobs;
        };
    }

    public boolean mayBreak(String blockTarget) { return mobBlockDamage && blockTarget != null && allowedBlockTargets.contains(blockTarget); }

    /** Restriktivste Kombination zweier Regelsätze. */
    public CombatRules restrict(CombatRules other) {
        if (other == null) return this;
        java.util.Set<String> blocks = new java.util.HashSet<>(allowedBlockTargets);
        blocks.retainAll(other.allowedBlockTargets);
        return new CombatRules(damagePlayers && other.damagePlayers, damageMobs && other.damageMobs, damageNpcs && other.damageNpcs,
                damagePets && other.damagePets, friendlyFire && other.friendlyFire, mobBlockDamage && other.mobBlockDamage, blocks,
                Math.min(damageMultiplier, other.damageMultiplier));
    }
}
