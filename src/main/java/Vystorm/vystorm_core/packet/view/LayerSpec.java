package Vystorm.vystorm_core.packet.view;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a packet view layer is (immutable). Built with {@link #builder(String)}:
 * <pre>{@code
 * LayerSpec spec = LayerSpec.builder("antixray:mask").mask().perViewer().objects(true).maxHoldMillis(1000).build();
 * }</pre>
 *
 * @param id            namespaced and unique among the registered layers ({@code plugin:name}, lower case)
 * @param kind          {@link Kind#MASK} (hides real cells: security) or {@link Kind#CONTENT} (decorates; from 0.74.0)
 * @param scope         {@link Scope#SHARED} (the same for every viewer of a world) or {@link Scope#PER_VIEWER}
 * @param priority      order inside its kind: lower runs first (then the older registration)
 * @param worlds        worlds the layer works in; empty = every world
 * @param collision     whether the layer changes what a player can stand on or walk into (see the API docs)
 * @param maxHoldMillis how long a chunk may wait for {@link ChunkLayer#ready}; 0 = never holds; at most
 *                      {@link #MAX_HOLD_MILLIS}
 * @param objects       whether the layer judges object packets ({@link ChunkLayer#drop})
 * @since 0.73.0
 */
public record LayerSpec(String id, Kind kind, Scope scope, int priority, Set<UUID> worlds, boolean collision,
                        long maxHoldMillis, boolean objects) {
    /** The longest hold a layer may ask for. */
    public static final long MAX_HOLD_MILLIS = 3000;

    /** What a layer does. MASK layers always run after CONTENT layers, whatever the priority numbers. */
    public enum Kind { MASK, CONTENT }

    /** Whether the layer's answer is the same for every viewer of a world. */
    public enum Scope { SHARED, PER_VIEWER }

    public LayerSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        if (!validId(id)) throw new IllegalArgumentException("layer id must be 'plugin:name' in lower case (a-z, 0-9, _ - . /): " + id);
        worlds = worlds == null ? Set.of() : Set.copyOf(worlds);
        if (maxHoldMillis < 0 || maxHoldMillis > MAX_HOLD_MILLIS) throw new IllegalArgumentException("maxHoldMillis must be 0.." + MAX_HOLD_MILLIS + ": " + maxHoldMillis);
    }

    static boolean validId(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0 || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || c == '/' || c == ':')) return false;
        }
        return id.equals(id.toLowerCase(Locale.ROOT));
    }

    /** Whether the layer works in this world. */
    public boolean covers(UUID world) { return worlds.isEmpty() || worlds.contains(world); }

    /** A builder with kind MASK, scope PER_VIEWER, priority 0, every world, no collision change, no hold, no object packets. */
    public static Builder builder(String id) { return new Builder(id); }

    /** Builder of a {@link LayerSpec}. */
    public static final class Builder {
        private final String id;
        private Kind kind = Kind.MASK;
        private Scope scope = Scope.PER_VIEWER;
        private int priority;
        private final Set<UUID> worlds = new LinkedHashSet<>();
        private boolean collision, objects;
        private long hold;

        private Builder(String id) { this.id = id; }

        public Builder kind(Kind kind) { this.kind = Objects.requireNonNull(kind); return this; }

        public Builder mask() { return kind(Kind.MASK); }

        public Builder content() { return kind(Kind.CONTENT); }

        public Builder scope(Scope scope) { this.scope = Objects.requireNonNull(scope); return this; }

        public Builder perViewer() { return scope(Scope.PER_VIEWER); }

        public Builder shared() { return scope(Scope.SHARED); }

        public Builder priority(int priority) { this.priority = priority; return this; }

        /** Limits the layer to these worlds (call again to add more). */
        public Builder worlds(Collection<UUID> worlds) { this.worlds.addAll(worlds); return this; }

        public Builder world(UUID world) { this.worlds.add(Objects.requireNonNull(world)); return this; }

        public Builder collision(boolean changesCollision) { this.collision = changesCollision; return this; }

        public Builder maxHoldMillis(long millis) { this.hold = millis; return this; }

        public Builder objects(boolean judgesObjectPackets) { this.objects = judgesObjectPackets; return this; }

        public LayerSpec build() { return new LayerSpec(id, kind, scope, priority, worlds, collision, hold, objects); }
    }
}
