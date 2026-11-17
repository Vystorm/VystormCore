package Vystorm.vystorm_core.spawn;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein fest gesetzter Spawnpunkt in einer Region (z. B. {@code dungeon:<id>}).
 *
 * @param id         eindeutig über alle Punkte; wechselt, wenn derselbe Ort neu belegt werden soll (z. B. neue Welle)
 * @param region     Regionsschlüssel {@code art:id} aus dem {@link Vystorm.vystorm_core.region.RegionService}
 * @param role       Rolle für den Spawner, z. B. {@code MELEE}, {@code RANGED}, {@code ELITE}, {@code BOSS}
 * @param theme      Mob-Thema, nach dem der Spawner die Definition wählt
 * @param level      0 = Level des Spawner-Profils, sonst 1..100
 * @param attributes freie Zusatzangaben (z. B. {@code wave})
 * @since 0.13.0
 */
public record SpawnPoint(String id, String region, UUID world, int x, int y, int z, String role, String theme, int level,
                         Map<String, String> attributes) {
    public SpawnPoint {
        Objects.requireNonNull(id, "id");Objects.requireNonNull(region, "region");Objects.requireNonNull(world, "world");
        Objects.requireNonNull(role, "role");theme = theme == null ? "" : theme;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (id.isBlank() || id.length() > 200 || role.isBlank() || level < 0 || level > 100)
            throw new IllegalArgumentException("Invalid spawn point " + id);
    }

    public static SpawnPoint of(String id, String region, UUID world, int x, int y, int z, String role, String theme) {
        return new SpawnPoint(id, region, world, x, y, z, role, theme, 0, Map.of());
    }
}
