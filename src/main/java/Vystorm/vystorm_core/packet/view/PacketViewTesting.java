package Vystorm.vystorm_core.packet.view;

import Vystorm.vystorm_core.packet.internal.view.LayerRegistry;
import Vystorm.vystorm_core.packet.internal.view.PacketViewRuntime;
import Vystorm.vystorm_core.packet.internal.view.ViewCounters;
import Vystorm.vystorm_core.packet.internal.view.ViewEngine;
import Vystorm.vystorm_core.packet.internal.view.ViewKind;
import Vystorm.vystorm_core.packet.internal.view.ViewRouter;
import Vystorm.vystorm_core.packet.internal.view.ViewerSlot;
import Vystorm.vystorm_core.packet.internal.wire.WireTables;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Test and proof helpers of the packet view (not for production code):
 * <ul>
 *   <li>{@link #bench}: the byte rewrite of encoded chunk, light and section packets through layers, without a
 *       server, for unit tests of a layer;</li>
 *   <li>{@link #virtualViewer} and {@link #filter}: a viewer without a connection on a running server whose packets run
 *       through the registered layers exactly as a player's do (server-side proofs; no hold, no handler).</li>
 * </ul>
 *
 * @since 0.73.0
 */
public final class PacketViewTesting {
    private PacketViewTesting() {}

    /** A viewer without a connection (server thread; the view must run here). Its client starts in {@code world}. */
    public static ViewerRef virtualViewer(UUID id, String name, World world, Consumer<Object> sink) {
        return PacketViewRuntime.get().virtual(id, name, world, sink);
    }

    public static void removeVirtual(ViewerRef viewer) { PacketViewRuntime.get().removeVirtual(viewer); }

    /**
     * Runs a packet object for a virtual viewer through the registered layers: null when it is dropped, the packet when
     * it passes unchanged (or a replacement packet), or for chunk, light and section packets the body bytes (without
     * packet id) the client would get (encoded by the server's codec, then rewritten). Server thread.
     */
    public static Object filter(ViewerRef viewer, Object packet) throws ReflectiveOperationException {
        return PacketViewRuntime.get().filterVirtual(viewer, packet);
    }

    /** A server-free rewrite bench (any thread, one thread at a time). */
    public static Bench bench(WireStates states, boolean bitsetBytes) { return new Bench(states, bitsetBytes); }

    /** Layers, worlds and viewers of a server-free byte rewrite (MASK layers). */
    public static final class Bench {
        private final LayerRegistry layers = new LayerRegistry(e -> { });
        private final ViewEngine engine;
        private final ViewRouter router;
        private final Map<UUID, ViewerSlot> viewers = new HashMap<>();
        private int nextSlot;

        Bench(WireStates states, boolean bitsetBytes) {
            boolean[] air = new boolean[states.size()], fluid = new boolean[states.size()];
            for (int i = 0; i < air.length; i++) { air[i] = states.air(i); fluid[i] = states.fluid(i); }
            engine = new ViewEngine(new WireTables(air, fluid), new ViewCounters(), bitsetBytes);
            router = new ViewRouter(null, engine, layers);
        }

        /** A world and its height. */
        public Bench world(UUID world, int minY, int sections) { engine.addWorld(null, world, minY, sections); return this; }

        /** Adds a MASK layer ({@code owner} may be null). */
        public ViewLayer layer(Plugin owner, LayerSpec spec, ChunkLayer layer) {
            if (spec.kind() != LayerSpec.Kind.MASK) throw new UnsupportedOperationException("MASK layers only in 0.73.0");
            return layers.add(spec, owner, layer);
        }

        /** A viewer whose client is in {@code world} (the world must be added first). */
        public ViewerRef viewer(UUID id, String name, UUID world) {
            ViewEngine.Dim d = engine.world(world);
            if (d == null) throw new IllegalArgumentException("unknown world " + world);
            ViewerRef ref = new ViewerRef(id, name, nextSlot++);
            viewers.put(id, new ViewerSlot(ref, null, world, d.minY(), d.sections(), p -> { }));
            return ref;
        }

        /** The client entered another world (as a respawn packet would tell the layers). */
        public void clientWorld(ViewerRef viewer, UUID world) {
            ViewerSlot v = slot(viewer);
            ViewEngine.Dim d = engine.world(world);
            v.state.world(world, d.minY(), d.sections());
            for (LayerRegistry.Entry e : layers.all()) e.layer.clientWorld(viewer, world, d.minY(), d.sections());
        }

        /** The client forgot a chunk (as an unload packet would tell the layers). */
        public void forget(ViewerRef viewer, int chunkX, int chunkZ) {
            ViewerSlot v = slot(viewer);
            UUID w = v.state.world();
            v.state.forget(chunkX, chunkZ);
            for (LayerRegistry.Entry e : layers.all()) e.layer.forgotten(viewer, w, chunkX, chunkZ);
        }

        private ViewerSlot slot(ViewerRef viewer) {
            ViewerSlot v = viewers.get(viewer.id());
            if (v == null) throw new IllegalArgumentException("unknown viewer " + viewer);
            return v;
        }

        /**
         * The byte side on one encoded packet (packet id first): {@code kind} is "chunk", "light" or "section";
         * {@code world} null = the viewer's world. The same array when nothing changes, null when it is dropped.
         */
        public byte[] rewrite(ViewerRef viewer, String kind, byte[] whole, UUID world) {
            ViewerSlot v = slot(viewer);
            UUID w = world != null ? world : v.state.world();
            ViewKind k = ViewKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
            byte[] out = router.rewrite(v, k, whole, w);
            if (out != null) router.delivered(v, k, whole, w);
            return out;
        }

        /** Chunk keys the viewer's client holds after the packets so far. */
        public java.util.Set<Long> held(ViewerRef viewer) { return java.util.Set.copyOf(slot(viewer).state.layer().chunks()); }

        /** Counters of the bench ({@code chunk.rewritten}, {@code dropped.layerError}, {@code unreadable.chunk} ...). */
        public long counter(String name) { Long n = engine.stats.counters().get(name); return n == null ? 0 : n; }
    }
}
