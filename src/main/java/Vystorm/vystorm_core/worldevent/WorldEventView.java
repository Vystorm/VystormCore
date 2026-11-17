package Vystorm.vystorm_core.worldevent;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Plugin-neutrale Sicht auf ein laufendes World-Event.
 *
 * @param scope    GLOBAL, WORLD, REGIONAL oder LOCAL
 * @param scopeKey "global", Welt-UUID/-Name oder Core-Regions-ID
 * @param type     POSITIVE, NEGATIVE, MIXED oder LEGENDARY
 */
public record WorldEventView(UUID instanceId, String eventId, String category, String type, String scope, String scopeKey,
                             Set<String> tags, Instant startedAt, Instant endsAt, double pressure) {
    public WorldEventView {
        Objects.requireNonNull(instanceId, "instanceId");
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId missing");
        category = category == null ? "" : category;
        type = type == null ? "MIXED" : type;
        scope = scope == null ? "GLOBAL" : scope;
        scopeKey = scopeKey == null ? "global" : scopeKey;
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!Double.isFinite(pressure)) throw new IllegalArgumentException("pressure");
    }

    public boolean hasTag(String tag) { return tags.stream().anyMatch(t -> t.equalsIgnoreCase(tag)); }
}
