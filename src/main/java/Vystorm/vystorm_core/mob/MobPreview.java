package Vystorm.vystorm_core.mob;

import java.util.List;

/** Aufgelöste Werte eines Spawn-Auftrags ohne Spawn (Director-Planung, Admin-Vorschau). */
public record MobPreview(String definitionId, String displayName, int level, MobTier tier, double health, double damage,
                         double armor, double movementSpeed, List<String> abilities, List<String> traits, List<String> affixes,
                         ThreatQuote threat) {
    public MobPreview {
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
        traits = traits == null ? List.of() : List.copyOf(traits);
        affixes = affixes == null ? List.of() : List.copyOf(affixes);
    }
}
