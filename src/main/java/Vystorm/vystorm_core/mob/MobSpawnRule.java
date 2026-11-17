package Vystorm.vystorm_core.mob;

import java.util.Set;

/**
 * Natürliche Spawnregel einer Mob-Definition (seit Core 0.15.0). Vystorm-Mobs liefert sie aus den Definitionen bzw.
 * {@code spawns.yml}; Spawn-Planer (SpawnDirector) übernehmen sie automatisch in ihren Katalog. {@code weight} ist das
 * wirksame Gewicht und enthält bereits den Faktor der Seltenheit ({@code rarity}). Leere Mengen bedeuten „keine
 * Einschränkung“. Biome als Namespaced-Key ({@code minecraft:plains}), Dimensionen als {@code NORMAL|NETHER|THE_END}.
 */
public record MobSpawnRule(String mobId, String entityType, String displayName, double weight, String rarity,
                           Set<String> biomes, Set<String> excludedBiomes, Set<String> dimensions, Set<String> terrains,
                           Set<String> roles, int minLevel, int maxLevel, double threatCost, boolean eliteAllowed,
                           boolean groupAllowed) {
    public MobSpawnRule {
        biomes = biomes == null ? Set.of() : Set.copyOf(biomes);
        excludedBiomes = excludedBiomes == null ? Set.of() : Set.copyOf(excludedBiomes);
        dimensions = dimensions == null ? Set.of() : Set.copyOf(dimensions);
        terrains = terrains == null ? Set.of() : Set.copyOf(terrains);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        rarity = rarity == null ? "COMMON" : rarity;
    }
}
