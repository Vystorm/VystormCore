package Vystorm.vystorm_core.region;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Eine Region: Art ({@code city}, {@code arena}, {@code market}, {@code dungeon}, {@code faction}, …) und ID bilden den
 * Schlüssel {@code art:id}. Tags beschreiben die Region, Attribute tragen Werte (z. B. {@code owner}), Flags Regeln
 * (z. B. {@code pvp}, {@code monster-spawn}). Die Region mit der höchsten Priorität gewinnt bei Überlappung.
 */
public record Region(String kind, String id, String owner, int priority, RegionShape shape,
                     Set<String> tags, Map<String, String> attributes, Map<String, Boolean> flags) {
    public Region {
        if (kind == null || !kind.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException("invalid region kind: " + kind);
        if (id == null || id.isBlank() || id.length() > 128) throw new IllegalArgumentException("invalid region id");
        owner = owner == null ? "" : owner;
        Objects.requireNonNull(shape, "shape");
        tags = Set.copyOf(tags.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList());
        attributes = Map.copyOf(attributes);
        flags = Map.copyOf(flags);
    }

    /** Region mit Standard-Priorität ihrer Art. */
    public static Region of(String kind, String id, RegionShape shape) {
        return new Region(kind, id, "", RegionPriority.forKind(kind), shape, Set.of(), Map.of(), Map.of());
    }

    public String key() { return kind + ":" + id; }

    public Region withPriority(int value) { return new Region(kind, id, owner, value, shape, tags, attributes, flags); }
    public Region withOwner(String value) { return new Region(kind, id, value, priority, shape, tags, attributes, flags); }
    public Region withTags(Set<String> value) { return new Region(kind, id, owner, priority, shape, value, attributes, flags); }
    public Region withAttributes(Map<String, String> value) { return new Region(kind, id, owner, priority, shape, tags, value, flags); }
    public Region withFlags(Map<String, Boolean> value) { return new Region(kind, id, owner, priority, shape, tags, attributes, value); }

    public Optional<String> attribute(String name) { return Optional.ofNullable(attributes.get(name)); }
    public Optional<Boolean> flag(String name) { return Optional.ofNullable(flags.get(name)); }
    public boolean hasTag(String tag) { return tags.contains(tag.toLowerCase(Locale.ROOT)); }
}
