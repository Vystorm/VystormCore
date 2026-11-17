package Vystorm.vystorm_core.mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Spawn-Auftrag (typisch vom Spawn Director). {@code level <= 0} = Definition entscheidet, {@code tier == null} =
 * Definitions-Tier. {@code affixes} sind Modifier wie VAMPIRIC oder FIRE_ENCHANTED, {@code role} eine Squad-Rolle.
 */
public record MobSpawnRequest(String definitionId, Location location, int level, MobTier tier, List<String> affixes,
                              String role, UUID squadId, String sourcePlugin, String team, boolean persistent,
                              List<Objective> objectives, Map<String, String> attributes) {
    public MobSpawnRequest {
        if (definitionId == null || definitionId.isBlank()) throw new IllegalArgumentException("definitionId missing");
        location = location == null ? null : location.clone();
        affixes = affixes == null ? List.of() : List.copyOf(affixes);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static Builder of(String definitionId) { return new Builder(definitionId); }

    public Builder toBuilder() {
        Builder builder = new Builder(definitionId).at(location).level(level).tier(tier).role(role).squad(squadId)
                .source(sourcePlugin).team(team).persistent(persistent);
        affixes.forEach(builder::affix);
        objectives.forEach(builder::objective);
        attributes.forEach(builder::attribute);
        return builder;
    }

    public static final class Builder {
        private final String definitionId;
        private Location location;
        private int level;
        private MobTier tier;
        private final List<String> affixes = new ArrayList<>();
        private String role, sourcePlugin, team;
        private UUID squadId;
        private boolean persistent;
        private final List<Objective> objectives = new ArrayList<>();
        private final Map<String, String> attributes = new HashMap<>();

        private Builder(String definitionId) { this.definitionId = Objects.requireNonNull(definitionId, "definitionId"); }

        public Builder at(Location value) { location = value; return this; }
        public Builder level(int value) { level = value; return this; }
        public Builder tier(MobTier value) { tier = value; return this; }
        public Builder affix(String value) { affixes.add(value); return this; }
        public Builder role(String value) { role = value; return this; }
        public Builder squad(UUID value) { squadId = value; return this; }
        public Builder source(String value) { sourcePlugin = value; return this; }
        public Builder team(String value) { team = value; return this; }
        public Builder persistent(boolean value) { persistent = value; return this; }
        public Builder objective(Objective value) { objectives.add(value); return this; }
        public Builder attribute(String key, String value) { attributes.put(key, value); return this; }

        public MobSpawnRequest build() {
            return new MobSpawnRequest(definitionId, location, level, tier, affixes, role, squadId, sourcePlugin, team, persistent, objectives, attributes);
        }
    }
}
