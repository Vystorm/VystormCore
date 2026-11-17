package Vystorm.vystorm_core.worldevent.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A currently running strategic event. No Bukkit objects are retained here. */
public record ActiveEvent(
        UUID instanceId,
        EventDefinition definition,
        EventScope scope,
        String scopeKey,
        Instant startedAt,
        Instant endsAt) {

    public ActiveEvent {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(scope, "scope");
        scopeKey = Objects.requireNonNull(scopeKey, "scopeKey").trim().toLowerCase(java.util.Locale.ROOT);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (scopeKey.isEmpty()) throw new IllegalArgumentException("scopeKey must not be blank");
        if (!definition.scopes().contains(scope)) throw new IllegalArgumentException("event does not support scope " + scope);
        if (endsAt.isBefore(startedAt)) throw new IllegalArgumentException("endsAt must be >= startedAt");
    }

    public boolean expired(Instant now) {
        return !endsAt.isAfter(Objects.requireNonNull(now, "now"));
    }
}
