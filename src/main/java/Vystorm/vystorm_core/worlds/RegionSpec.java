package Vystorm.vystorm_core.worlds;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Region, die Core nach dem Laden für die Welt anmeldet (volle Höhe, Rechteck in Blockkoordinaten, Grenzen inklusiv) und
 * beim Entladen wieder abmeldet. SpawnDirector, Mobs, Bounty u. a. lesen Art und Tags.
 *
 * Fixed-shape value record: constructor and components remain frozen for compatibility.
 * Add extensible options to WorldSpec or a separate type.
 *
 * @since 0.26.0
 */
public record RegionSpec(String kind, String id, int priority, int minX, int minZ, int maxX, int maxZ, Set<String> tags,
                         Map<String, Boolean> flags) {
    public RegionSpec {
        if (kind == null || kind.isBlank() || id == null || id.isBlank()) throw new IllegalArgumentException("region kind/id missing");
        kind = kind.toLowerCase(Locale.ROOT);
        id = id.toLowerCase(Locale.ROOT);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        flags = flags == null ? Map.of() : Map.copyOf(flags);
    }

    /** Quadrat um (0, 0) mit halber Kantenlänge {@code half}. */
    public static RegionSpec square(String kind, String id, int priority, int half, Set<String> tags, Map<String, Boolean> flags) {
        return new RegionSpec(kind, id, priority, -half, -half, half, half, tags, flags);
    }
}
