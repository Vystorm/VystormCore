package Vystorm.vystorm_core.mob;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Abfrage nach Definitionen oder laufenden Mobs über Tags statt konkreter IDs, z. B.
 * {@code MobQuery.builder().faction("ORC").role("RANGED").environment("FOREST").threat(20, 35).tiers(NORMAL, ELITE)}.
 * Leere Felder schränken nicht ein.
 */
public record MobQuery(Set<String> ids, Set<String> allTags, Set<String> anyTags, String faction, String role, String environment,
                       double minThreat, double maxThreat, MobTier minTier, MobTier maxTier, UUID worldId,
                       double x, double y, double z, double radius, int limit) {
    public static final MobQuery ALL = builder().build();

    public MobQuery {
        ids = normalize(ids);
        allTags = normalize(allTags);
        anyTags = normalize(anyTags);
        faction = upper(faction);
        role = upper(role);
        environment = upper(environment);
        minTier = minTier == null ? MobTier.NORMAL : minTier;
        maxTier = maxTier == null ? MobTier.WORLD_BOSS : maxTier;
        if (limit <= 0) limit = Integer.MAX_VALUE;
    }

    public boolean matches(MobDefinitionView view) {
        if (!ids.isEmpty() && !ids.contains(view.id().toUpperCase(Locale.ROOT))) return false;
        Set<String> tags = normalize(view.tags());
        if (!tags.containsAll(allTags)) return false;
        if (!anyTags.isEmpty() && anyTags.stream().noneMatch(tags::contains)) return false;
        if (faction != null && !faction.equals(upper(view.faction())) && !tags.contains(faction)) return false;
        if (role != null && !normalize(view.roles()).contains(role) && !role.equals(upper(view.archetype()))) return false;
        if (environment != null && !view.environments().isEmpty() && !normalize(view.environments()).contains(environment)) return false;
        if (view.baseThreat() < minThreat || view.baseThreat() > maxThreat) return false;
        return view.tier().ordinal() >= minTier.ordinal() && view.tier().ordinal() <= maxTier.ordinal();
    }

    public boolean spatial() { return worldId != null && radius > 0; }

    public static Builder builder() { return new Builder(); }

    private static Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(value.trim().toUpperCase(Locale.ROOT));
        return Set.copyOf(result);
    }

    private static String upper(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }

    public static final class Builder {
        private final Set<String> ids = new HashSet<>(), allTags = new HashSet<>(), anyTags = new HashSet<>();
        private String faction, role, environment;
        private double minThreat = Double.NEGATIVE_INFINITY, maxThreat = Double.POSITIVE_INFINITY;
        private MobTier minTier, maxTier;
        private UUID worldId;
        private double x, y, z, radius;
        private int limit;

        public Builder id(String id) { ids.add(id); return this; }
        public Builder tag(String tag) { allTags.add(tag); return this; }
        public Builder anyTag(String tag) { anyTags.add(tag); return this; }
        public Builder faction(String value) { faction = value; return this; }
        public Builder role(String value) { role = value; return this; }
        public Builder environment(String value) { environment = value; return this; }
        public Builder threat(double min, double max) { minThreat = min; maxThreat = max; return this; }
        public Builder tiers(MobTier min, MobTier max) { minTier = min; maxTier = max; return this; }
        public Builder near(UUID world, double x, double y, double z, double radius) { worldId = world; this.x = x; this.y = y; this.z = z; this.radius = radius; return this; }
        public Builder limit(int value) { limit = value; return this; }

        public MobQuery build() {
            return new MobQuery(ids, allTags, anyTags, faction, role, environment, minThreat, maxThreat, minTier, maxTier, worldId, x, y, z, radius, limit);
        }
    }
}
