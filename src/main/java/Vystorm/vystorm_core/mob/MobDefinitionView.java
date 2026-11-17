package Vystorm.vystorm_core.mob;

import java.util.List;
import java.util.Set;

/** Lesesicht auf eine Mob-Definition; enthält keine AI-Interna. */
public record MobDefinitionView(String id, String displayName, String entityType, String species, String faction,
                                String archetype, String brain, MobTier tier, double baseThreat, Set<String> tags,
                                Set<String> roles, Set<String> environments, List<String> abilities, List<String> traits,
                                String provider, boolean boss) {
    public MobDefinitionView {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        environments = environments == null ? Set.of() : Set.copyOf(environments);
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
        traits = traits == null ? List.of() : List.copyOf(traits);
        tier = tier == null ? MobTier.NORMAL : tier;
    }
}
