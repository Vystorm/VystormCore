package Vystorm.vystorm_core.mob;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ein Kontextbeitrag eines Plugins (Arena-Match, Stadt, Belagerung, Dungeon …). {@code combatRules} und
 * {@code aiRules} dürfen {@code null} sein ("keine Vorgabe"). Attribute sind frei, bekannte Schlüssel:
 * {@code team}, {@code matchId}, {@code gameMode}, {@code cityId}, {@code owner}, {@code faction},
 * {@code siegeState}, {@code protectionState}, {@code disposition}.
 */
public record ContextLayer(ContextKind kind, String id, String owner, int priority, Set<String> tags,
                           Map<String, String> attributes, CombatRules combatRules, AiRules aiRules, List<Objective> objectives) {
    public ContextLayer {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("context id missing");
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    public String attribute(String key, String fallback) { return attributes.getOrDefault(key, fallback); }
}
