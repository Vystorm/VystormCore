package Vystorm.vystorm_core.item;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Neutrale Lesesicht auf ein Vystorm-Item für andere Plugins. {@code groups} enthält Typgruppen wie
 * {@code WEAPON}, {@code MELEE}, {@code ARMOR}, {@code TOOL}; {@code type} den konkreten Itemtyp (z. B. {@code DAGGER}).
 */
public record ItemView(UUID instanceId, String templateId, String type, Set<String> groups, String rarity, String tier, int level,
                       double quality, Optional<UUID> boundTo, Set<String> tags, Map<String, Double> stats, int schemaVersion) {
    public ItemView {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        stats = stats == null ? Map.of() : Map.copyOf(stats);
        boundTo = boundTo == null ? Optional.empty() : boundTo;
    }

    public boolean is(String typeOrGroup) {
        if (typeOrGroup == null) return false;
        return (type != null && type.equalsIgnoreCase(typeOrGroup)) || groups.contains(typeOrGroup.toUpperCase(java.util.Locale.ROOT));
    }

    public double stat(String id) { return stats.getOrDefault(id, 0.0); }
}
