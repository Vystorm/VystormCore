package Vystorm.vystorm_core.mob;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.util.Vector;

/**
 * Ein Ziel: Typ, Zielreferenz oder Position(en), Priorität, Ablaufzeit und optionale Abschlusskriterien.
 * {@code expiresAtMillis <= 0} bedeutet unbegrenzt. Positionen sind Blockkoordinaten in {@code worldId}.
 */
public record Objective(String id, ObjectiveType type, UUID targetEntity, UUID worldId, List<Vector> points,
                        double radius, int priority, long expiresAtMillis, String sourcePlugin, Map<String, String> completion) {
    public Objective {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("objective id missing");
        Objects.requireNonNull(type, "type");
        points = points == null ? List.of() : points.stream().map(Vector::clone).toList();
        completion = completion == null ? Map.of() : Map.copyOf(completion);
        if (radius < 0) radius = 0;
    }

    public static Objective at(String id, ObjectiveType type, UUID worldId, Vector point, double radius, int priority) {
        return new Objective(id, type, null, worldId, List.of(point), radius, priority, 0, null, Map.of());
    }

    public static Objective entity(String id, ObjectiveType type, UUID target, int priority) {
        return new Objective(id, type, target, null, List.of(), 0, priority, 0, null, Map.of());
    }

    public boolean expired(long nowMillis) { return expiresAtMillis > 0 && nowMillis >= expiresAtMillis; }

    public Objective withExpiry(long expiresAtMillis) {
        return new Objective(id, type, targetEntity, worldId, points, radius, priority, expiresAtMillis, sourcePlugin, completion);
    }
}
