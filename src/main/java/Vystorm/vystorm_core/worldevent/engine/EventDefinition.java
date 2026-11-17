package Vystorm.vystorm_core.worldevent.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record EventDefinition(
        String id,
        Set<EventScope> scopes,
        EventType type,
        String category,
        double baseWeight,
        Set<String> tags,
        Duration cooldown,
        Duration minimumDuration,
        Duration maximumDuration,
        Set<String> conflicts,
        Set<String> synergies,
        Set<String> compatibilityGroups,
        double pressureValue,
        double recoveryValue,
        int maxConcurrent) {

    public EventDefinition {
        id = normalizeId(id, "id");
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        Objects.requireNonNull(type, "type");
        category = normalizeId(category, "category");
        tags = normalizedSet(tags, "tags");
        cooldown = requireDuration(cooldown, "cooldown");
        minimumDuration = requireDuration(minimumDuration, "minimumDuration");
        maximumDuration = requireDuration(maximumDuration, "maximumDuration");
        conflicts = normalizedSet(conflicts, "conflicts");
        synergies = normalizedSet(synergies, "synergies");
        compatibilityGroups = normalizedSet(compatibilityGroups, "compatibilityGroups");
        if (maximumDuration.compareTo(minimumDuration) < 0) {
            throw new IllegalArgumentException("maximumDuration must be >= minimumDuration");
        }
        if (!Double.isFinite(baseWeight) || baseWeight < 0.0D) throw new IllegalArgumentException("baseWeight must be finite and >= 0");
        if (!Double.isFinite(pressureValue) || pressureValue < 0.0D) throw new IllegalArgumentException("pressureValue must be finite and >= 0");
        if (!Double.isFinite(recoveryValue) || recoveryValue < 0.0D) throw new IllegalArgumentException("recoveryValue must be finite and >= 0");
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be >= 1");
        if (scopes.isEmpty()) throw new IllegalArgumentException("scopes must not be empty");
        if (tags.isEmpty()) throw new IllegalArgumentException("tags must not be empty");
        if (compatibilityGroups.isEmpty()) throw new IllegalArgumentException("compatibilityGroups must not be empty");
    }

    private static Duration requireDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return duration;
    }

    private static Set<String> normalizedSet(Set<String> input, String name) {
        Objects.requireNonNull(input, name);
        return input.stream().map(value -> normalizeId(value, name)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalizeId(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
