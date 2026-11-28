package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.packet.view.ViewChunks;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What one viewer's client holds: its world (with height) and the chunks of that world it was sent and has not
 * forgotten. The world, its height and its chunks form one {@link Layer} that is swapped as a whole: a reader that took
 * a layer never sees the new world's height with the old world's chunks. Written on the viewer's Netty thread (in packet
 * order), read anywhere. Moved from VystormAntiXray's {@code mask.ViewerState} without the mask part (that stays with
 * the layer that owns it).
 *
 * <p>Internal: not part of the public API.
 */
public final class ClientWorldState {
    /** One world as the client holds it. Immutable apart from the chunk set. */
    public static final class Layer {
        private final UUID world;
        private final int minY, sections;
        private final Set<Long> chunks = ConcurrentHashMap.newKeySet();

        Layer(UUID world, int minY, int sections) {
            this.world = world;
            this.minY = minY;
            this.sections = sections;
        }

        public UUID world() { return world; }

        public int minY() { return minY; }

        public int sections() { return sections; }

        public Set<Long> chunks() { return chunks; }

        public boolean holds(int cx, int cz) { return chunks.contains(ViewChunks.key(cx, cz)); }
    }

    private final AtomicReference<Layer> layer = new AtomicReference<>();

    public ClientWorldState(UUID world, int minY, int sections) { world(world, minY, sections); }

    /** A new world: everything known about the old one is dropped (one atomic swap). */
    public void world(UUID world, int minY, int sections) { layer.set(new Layer(world, minY, sections)); }

    public Layer layer() { return layer.get(); }

    public UUID world() { return layer.get().world; }

    public int minY() { return layer.get().minY; }

    public int sections() { return layer.get().sections; }

    /** The client got this chunk of its current world. */
    public void sent(int cx, int cz) { layer.get().chunks.add(ViewChunks.key(cx, cz)); }

    /** The client dropped the chunk. */
    public void forget(int cx, int cz) { layer.get().chunks.remove(ViewChunks.key(cx, cz)); }

    /** Every chunk dropped, same world (compare-and-set: a world switch that happens meanwhile is never undone). */
    public void clear() { layer.updateAndGet(l -> new Layer(l.world, l.minY, l.sections)); }
}
