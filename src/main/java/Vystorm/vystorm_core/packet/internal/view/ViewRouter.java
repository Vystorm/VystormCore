package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.packet.internal.wire.Bytes;
import Vystorm.vystorm_core.packet.internal.wire.ChunkPacket;
import Vystorm.vystorm_core.packet.internal.wire.SmallPackets;
import Vystorm.vystorm_core.packet.view.ChunkLayer;
import Vystorm.vystorm_core.packet.view.ObjectPacket;
import Vystorm.vystorm_core.packet.view.Readiness;
import Vystorm.vystorm_core.packet.view.ViewChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * The packet view's rules for one server (the generic half of VystormAntiXray's {@code platform.Filter}; the masking
 * decisions are the layers'). {@link #decide} runs on the packet object right before the encoder: it follows the
 * client's chunks, asks the layers about object packets and tags chunk, light and section packets for the byte side.
 * {@link #rewrite} runs on the encoded bytes of those packets: one parse, every interested layer in order on one
 * {@link ChunkEditImpl}, one encode. Netty threads.
 *
 * <p>Fail rules (MASK layers, as AntiXray 0.1.2 did): a packet an interested MASK layer cannot have rewritten (bytes
 * that cannot be read, an exception in a layer, a world of unknown height) is not sent; a chunk is asked again from the
 * server thread (at most twice), unreadable bytes make the self-test run again. A packet whose judgement fails is
 * dropped. Nothing a MASK layer is interested in passes unread.
 *
 * <p>Internal: not part of the public API.
 */
public final class ViewRouter implements ViewRules {
    private final ViewNms nms;
    private final ViewEngine engine;
    private final LayerRegistry layers;

    public ViewRouter(ViewNms nms, ViewEngine engine, LayerRegistry layers) {
        this.nms = nms;
        this.engine = engine;
        this.layers = layers;
    }

    public ViewEngine engine() { return engine; }

    @Override public boolean filtering(ViewerSlot v) { return engine.broken() == null && !layers.isEmpty(); }

    @Override public ViewKind kindOf(int id) {
        return id < 0 ? null : id == nms.chunkId ? ViewKind.CHUNK : id == nms.lightId ? ViewKind.LIGHT : id == nms.sectionId ? ViewKind.SECTION : null;
    }

    // ---- object side ----------------------------------------------------------------------------------------------

    @Override public Object decide(ViewerSlot v, Object msg, UUID world) {
        if (engine.broken() != null) return msg;
        Class<?> c = msg.getClass();
        if (c == nms.forgetChunk) {
            int[] p = nms.forgetPos(msg);
            UUID w = v.state.world();
            v.state.forget(p[0], p[1]);
            for (LayerRegistry.Entry e : layers.all()) {
                try { e.layer.forgotten(v.ref, w, p[0], p[1]); }
                catch (RuntimeException | LinkageError failure) { layerFailed(e, "forgotten", failure); }
            }
            return msg;
        }
        if (layers.isEmpty()) return msg;
        if (v.takeUnmasked(msg)) {
            engine.stats.inc("passed.unmasked");
            // the light update of a sendReal covers whole sections: it is still judged, so other hidden cells stay dark
            if (c == nms.lightPacket) return ViewKind.LIGHT;
            return c == nms.chunkPacket || c == nms.sectionPacket ? UNMASKED : msg;
        }
        if (c == nms.chunkPacket) return ViewKind.CHUNK;
        if (c == nms.lightPacket) return ViewKind.LIGHT;
        if (c == nms.sectionPacket) return ViewKind.SECTION;
        UUID w = world != null ? world : v.state.world();
        if (c == nms.blockUpdate) { int[] p = nms.blockUpdatePos(msg); return hidden(v, w, p) ? drop("blockUpdate") : msg; }
        if (c == nms.blockEntityData) return object(v, w, msg, ObjectPacket.Kind.BLOCK_ENTITY_DATA, nms.blockEntityPos(msg), 0, "blockEntityData");
        if (c == nms.blockEvent) return object(v, w, msg, ObjectPacket.Kind.BLOCK_EVENT, nms.blockEventPos(msg), 0, "blockEvent");
        if (c == nms.blockDestruction) return object(v, w, msg, ObjectPacket.Kind.BLOCK_DESTRUCTION, nms.blockDestructionPos(msg), 0, "blockDestruction");
        if (c == nms.levelEvent) { int[] p = nms.levelEventPos(msg); return p == null ? msg : object(v, w, msg, ObjectPacket.Kind.LEVEL_EVENT, p, 0, "levelEvent"); }
        if (c == nms.sound) { double[] d = nms.soundPos(msg); return object(v, w, msg, new ObjectPacket(ObjectPacket.Kind.SOUND, d[0], d[1], d[2], 0, null), "sound"); }
        if (c == nms.particles) { double[] d = nms.particlePos(msg); return object(v, w, msg, new ObjectPacket(ObjectPacket.Kind.PARTICLES, d[0], d[1], d[2], 0, null), "particles"); }
        if (c == nms.addEntity) { double[] d = nms.addEntityPos(msg); return object(v, w, msg, new ObjectPacket(ObjectPacket.Kind.ADD_ENTITY, d[0], d[1], d[2], nms.addEntityId(msg), null), "addEntity"); }
        if (c == nms.playerInfoRemove) {
            ObjectPacket o = new ObjectPacket(ObjectPacket.Kind.PLAYER_INFO_REMOVE, 0, 0, 0, 0, nms.profileIds(msg));
            for (LayerRegistry.Entry e : layers.forWorld(w)) {
                if (!e.mask() || !e.spec.objects()) continue;
                if (e.layer.drop(v.ref, o)) { e.dropped.increment(); return drop("playerInfoRemove"); }
            }
            return msg;
        }
        if (c == nms.bundlePacket) {
            // only when the server's unbundler sits behind the view (it is moved back right after): the bundled packets
            // are judged one by one, chunk, light and section packets in it are rewritten by their bytes
            engine.stats.inc("bundles.judged");
            List<Object> keep = keep(nms.subPackets(msg), sub -> decide(v, sub, w));
            if (keep == null) return msg;
            if (keep.isEmpty()) return DROP;
            return nms.bundle(keep);
        }
        return msg;
    }

    /** The packets of a bundle that pass (packets themselves, also those to rewrite); null when none is dropped. */
    public static List<Object> keep(List<Object> subs, Function<Object, Object> decide) {
        List<Object> out = new ArrayList<>(subs.size());
        boolean changed = false;
        for (Object s : subs) {
            Object d = decide.apply(s);
            if (d == DROP) { changed = true; continue; }
            if (d instanceof ViewKind || d == s || d == UNMASKED) out.add(s);
            else { out.add(d); changed = true; }
        }
        return changed ? out : null;
    }

    private Object drop(String what) {
        engine.stats.inc("dropped." + what);
        return DROP;
    }

    /** Whether an interested MASK layer hides this cell (an exception inside a layer counts as hidden: fail-closed). */
    private boolean hidden(ViewerSlot v, UUID w, int[] p) {
        for (LayerRegistry.Entry e : layers.forWorld(w)) {
            if (!e.mask()) continue;
            if (!e.layer.interested(v.ref, w, p[0] >> 4, p[2] >> 4)) continue;
            if (e.layer.hides(v.ref, w, p[0], p[1], p[2])) { e.dropped.increment(); return true; }
        }
        return false;
    }

    private Object object(ViewerSlot v, UUID w, Object msg, ObjectPacket.Kind kind, int[] p, int entity, String what) {
        return object(v, w, msg, new ObjectPacket(kind, p[0], p[1], p[2], entity, null), what);
    }

    private Object object(ViewerSlot v, UUID w, Object msg, ObjectPacket o, String what) {
        boolean atBlock = o.kind() == ObjectPacket.Kind.BLOCK_ENTITY_DATA || o.kind() == ObjectPacket.Kind.BLOCK_EVENT
                || o.kind() == ObjectPacket.Kind.BLOCK_DESTRUCTION || o.kind() == ObjectPacket.Kind.LEVEL_EVENT;
        for (LayerRegistry.Entry e : layers.forWorld(w)) {
            if (!e.mask()) continue;
            if (!e.spec.objects() && !atBlock) continue;
            if (!e.layer.interested(v.ref, w, o.blockX() >> 4, o.blockZ() >> 4)) continue;
            // a mask that does not judge object packets still keeps the block packets of the cells it hides
            boolean dropIt = e.spec.objects() ? e.layer.drop(v.ref, o) : e.layer.hides(v.ref, w, o.blockX(), o.blockY(), o.blockZ());
            if (dropIt) { e.dropped.increment(); return drop(what); }
        }
        return msg;
    }

    @Override public UUID worldOf(ViewerSlot v, Object msg) {
        Class<?> c = msg.getClass();
        if (c != nms.respawnPacket && c != nms.loginPacket) return null;
        Object key = nms.dimensionOf(msg);
        ViewEngine.Dim d = engine.dimension(key);
        if (d != null) return d.world();
        // a world Core could not map at load: found by its key's name, else (last resort) the player's world
        org.bukkit.World byKey = nms.worldByKey(key);
        if (byKey != null) return byKey.getUID();
        return v.player == null ? null : v.player.getWorld().getUID();
    }

    @Override public void passing(ViewerSlot v, Object msg) {
        Class<?> c = msg.getClass();
        if (c == nms.startConfiguration) { // the client drops its world when it goes back to configuration
            v.state.clear();
            for (LayerRegistry.Entry e : layers.all()) {
                try { e.layer.clientReset(v.ref); } catch (RuntimeException | LinkageError failure) { layerFailed(e, "clientReset", failure); }
            }
            return;
        }
        if (c != nms.respawnPacket && c != nms.loginPacket) return;
        UUID w = worldOf(v, msg);
        if (w == null || w.equals(v.state.world())) return;
        v.retried.clear();
        ViewEngine.Dim d = engine.dimension(nms.dimensionOf(msg));
        if (d == null) d = engine.world(w);
        if (d == null) {
            org.bukkit.World bw = org.bukkit.Bukkit.getWorld(w);
            if (bw != null) d = new ViewEngine.Dim(w, bw.getMinHeight(), (bw.getMaxHeight() - bw.getMinHeight()) >> 4);
        }
        if (d == null) return;
        v.state.world(w, d.minY(), d.sections());
        for (LayerRegistry.Entry e : layers.all()) {
            try { e.layer.clientWorld(v.ref, w, d.minY(), d.sections()); }
            catch (RuntimeException | LinkageError failure) { layerFailed(e, "clientWorld", failure); }
        }
    }

    @Override public boolean isBundle(Object msg) { return msg.getClass() == nms.bundlePacket; }

    @Override public boolean pipelineEvent(Object msg) { return msg.getClass() == nms.bundlePacket || !nms.packetBase.isInstance(msg); }

    // ---- holds ---------------------------------------------------------------------------------------------------

    @Override public long hold(ViewerSlot v, Object msg, UUID world) {
        if (engine.broken() != null || layers.isEmpty() || msg.getClass() != nms.chunkPacket) return 0;
        UUID w = world != null ? world : v.state.world();
        int[] p = nms.chunkPacketPos(msg);
        long ns = 0;
        for (LayerRegistry.Entry e : layers.forWorld(w)) {
            long h = e.holdNanos();
            if (h <= 0 || !e.layer.interested(v.ref, w, p[0], p[1]) || e.layer.ready(v.ref, w, p[0], p[1]) != Readiness.HOLD) continue;
            ns = Math.max(ns, h);
            e.holds.increment();
            try { e.layer.held(v.ref, w, p[0], p[1]); } catch (RuntimeException | LinkageError failure) { layerFailed(e, "held", failure); }
        }
        return ns;
    }

    @Override public boolean mustHold(ViewerSlot v, Object msg, UUID world) {
        if (engine.broken() != null || layers.isEmpty() || msg.getClass() != nms.chunkPacket) return false;
        UUID w = world != null ? world : v.state.world();
        int[] p = nms.chunkPacketPos(msg);
        for (LayerRegistry.Entry e : layers.forWorld(w))
            if (e.holdNanos() > 0 && e.layer.interested(v.ref, w, p[0], p[1]) && e.layer.ready(v.ref, w, p[0], p[1]) == Readiness.HOLD) return true;
        return false;
    }

    // ---- byte side -------------------------------------------------------------------------------------------------

    @Override public void delivered(ViewerSlot v, ViewKind kind, byte[] whole, UUID world) {
        if (kind != ViewKind.CHUNK) return;
        int[] p = chunkPosOf(whole);
        if (p != null && world != null && world.equals(v.state.world())) v.state.sent(p[0], p[1]);
    }

    /**
     * New bytes for an encoded packet of the given kind and world; {@code whole} starts with the packet id. Returns the
     * same array when nothing changes and null when the packet is to be dropped.
     */
    @Override public byte[] rewrite(ViewerSlot v, ViewKind kind, byte[] whole, UUID world) {
        if (engine.broken() != null) return whole;
        UUID w = world != null ? world : v.state.world();
        LayerRegistry.Entry[] chain = layers.forWorld(w);
        if (chain.length == 0) return whole;
        int from;
        try {
            Bytes.Reader r = new Bytes.Reader(whole, 0);
            r.varint();
            from = r.p;
        } catch (RuntimeException bad) { return unreadable(v, kind, whole, w, chain, null, bad); }
        return switch (kind) {
            case CHUNK -> chunk(v, whole, from, w, chain);
            case LIGHT -> light(v, whole, from, w, chain);
            case SECTION -> section(v, whole, from, w, chain);
        };
    }

    /** The interested layers of a chunk; an exception in {@code interested} counts as interested (and failing) for a MASK layer. */
    private List<LayerRegistry.Entry> interested(ViewerSlot v, UUID w, LayerRegistry.Entry[] chain, int cx, int cz, boolean[] failed) {
        List<LayerRegistry.Entry> out = null;
        for (LayerRegistry.Entry e : chain) {
            boolean in;
            try { in = e.layer.interested(v.ref, w, cx, cz); }
            catch (RuntimeException | LinkageError failure) {
                layerFailed(e, "interested", failure);
                in = e.mask();
                if (e.mask()) failed[0] = true;
            }
            if (!in) continue;
            if (out == null) out = new ArrayList<>(chain.length);
            out.add(e);
        }
        return out;
    }

    private static boolean anyMask(List<LayerRegistry.Entry> list) {
        if (list != null) for (LayerRegistry.Entry e : list) if (e.mask()) return true;
        return false;
    }

    /** A packet that could not be read: dropped when an interested MASK layer could be concerned, else passed. */
    private byte[] unreadable(ViewerSlot v, ViewKind kind, byte[] whole, UUID w, LayerRegistry.Entry[] chain, int[] pos, RuntimeException bad) {
        List<LayerRegistry.Entry> in = pos == null ? interested(v, w, chain, Integer.MIN_VALUE, Integer.MIN_VALUE, new boolean[1])
                : interested(v, w, chain, pos[0], pos[1], new boolean[1]);
        if (!anyMask(in)) return whole; // nothing hidden there: the real packet
        String k = kind.name().toLowerCase(Locale.ROOT);
        engine.stats.inc("unreadable." + k);
        boolean logged = engine.unreadable(v, k, w, kind == ViewKind.CHUNK ? chunkPosOf(whole) : null, String.valueOf(bad.getMessage() != null ? bad.getMessage() : bad), true);
        if (logged) ViewSelfTest.dump("unreadable-" + k, whole);
        return null;
    }

    /** Chunk x and z from the first bytes of an encoded chunk packet, null when it is too short. */
    static int[] chunkPosOf(byte[] whole) {
        try {
            Bytes.Reader r = new Bytes.Reader(whole, 0);
            r.varint();
            return new int[] {r.s32(), r.s32()};
        } catch (RuntimeException tooShort) { return null; }
    }

    private byte[] chunk(ViewerSlot v, byte[] b, int from, UUID w, LayerRegistry.Entry[] chain) {
        long t0 = System.nanoTime();
        int[] pos = chunkPosOf(b);
        if (pos == null) return unreadable(v, ViewKind.CHUNK, b, w, chain, null, new Bytes.Malformed("chunk packet too short"));
        boolean[] failed = {false};
        List<LayerRegistry.Entry> in = interested(v, w, chain, pos[0], pos[1], failed);
        if (in == null) return b;
        boolean mask = anyMask(in);
        if (failed[0]) return layerDropped(v, w, pos, "interested");
        ViewEngine.Dim d = engine.world(w);
        if (d == null) {
            if (!mask) return b;
            engine.warn("height " + w, "Packet view: a chunk of a world Core does not know (" + w + ") was not sent to " + v.name);
            engine.stats.inc("dropped.unknownWorld");
            return null;
        }
        ChunkPacket p;
        try { p = ChunkPacket.parse(b, from, engine.bitsetBytes); }
        catch (RuntimeException bad) { return mask ? unreadable(v, ViewKind.CHUNK, b, w, chain, pos, bad) : b; }
        ChunkEditImpl edit = new ChunkEditImpl(p, w, d.minY(), d.sections(), engine.tables);
        for (LayerRegistry.Entry e : in) {
            edit.mask(e.mask());
            long a0 = System.nanoTime();
            try { e.layer.apply(v.ref, edit); }
            catch (Bytes.Malformed bad) {
                // a layer read a section that is not there (another height, broken bytes): the packet's fault
                layerFailed(e, "apply", bad);
                if (e.mask()) return unreadable(v, ViewKind.CHUNK, b, w, chain, pos, bad);
                return b;
            } catch (RuntimeException | LinkageError failure) {
                layerFailed(e, "apply", failure);
                if (e.mask()) return layerDropped(v, w, pos, "apply");
                return b; // CONTENT layers come with 0.74.0; until then nothing else is in the chain
            } finally {
                e.applyNanos.add(System.nanoTime() - a0);
            }
            e.chunks.increment();
            e.cells.add(edit.takeWritten());
        }
        byte[] out;
        try { out = edit.encode(); }
        catch (RuntimeException bad) { return mask ? unreadable(v, ViewKind.CHUNK, b, w, chain, pos, bad) : b; }
        if (!v.retried.isEmpty()) v.retried.remove(ViewChunks.key(pos[0], pos[1])); // read fine (again)
        engine.stats.inc("chunk.rewritten");
        engine.stats.add("chunk.bytesBefore", b.length);
        engine.stats.add("chunk.bytesAfter", out.length);
        engine.stats.add("chunk.cellsHidden", edit.cellsHidden);
        engine.stats.add("chunk.blockEntitiesRemoved", edit.blockEntitiesRemoved);
        engine.stats.add("chunk.lightArraysZeroed", edit.lightArraysZeroed);
        String thread = Thread.currentThread().getName().toLowerCase(Locale.ROOT);
        engine.stats.sample(thread.contains("netty") || thread.contains("epoll") || thread.contains("nio") ? "chunk.nanosNetty" : "chunk.nanosOther", System.nanoTime() - t0);
        return out;
    }

    /** A MASK layer failed on a chunk: not sent, asked again (at most twice), no self-test (the bytes were fine). */
    private byte[] layerDropped(ViewerSlot v, UUID w, int[] pos, String where) {
        engine.stats.inc("dropped.layerError");
        engine.unreadable(v, "chunk", w, pos, "a layer failed in " + where, false);
        return null;
    }

    private void layerFailed(LayerRegistry.Entry e, String where, Throwable failure) {
        e.errors.increment();
        engine.stats.inc("layer.errors");
        engine.warn("layer " + e.spec.id() + " " + where, "Packet view: layer " + e.spec.id() + " of " + (e.owner == null ? "?" : e.owner.getName()) + " failed in " + where + ": " + failure);
    }

    /**
     * The hidden cells of one section over every interested MASK layer: null = none, {@link ChunkLayer#UNKNOWN} = some
     * layer cannot tell (the packet is dropped). An exception counts as UNKNOWN.
     */
    private long[] hiddenCells(ViewerSlot v, UUID w, List<LayerRegistry.Entry> in, int cx, int sy, int cz) {
        long[] union = null;
        for (LayerRegistry.Entry e : in) {
            if (!e.mask()) continue;
            long[] h;
            try { h = e.layer.hiddenCells(v.ref, w, cx, sy, cz); }
            catch (RuntimeException | LinkageError failure) { layerFailed(e, "hiddenCells", failure); return ChunkLayer.UNKNOWN; }
            if (h == ChunkLayer.UNKNOWN) return ChunkLayer.UNKNOWN;
            if (h == null) continue;
            if (h.length < 64) { layerFailed(e, "hiddenCells", new IllegalStateException("hiddenCells returned " + h.length + " longs, not 64")); return ChunkLayer.UNKNOWN; }
            if (union == null) union = h.clone();
            else for (int i = 0; i < 64; i++) union[i] |= h[i];
        }
        return union;
    }

    private byte[] light(ViewerSlot v, byte[] b, int from, UUID w, LayerRegistry.Entry[] chain) {
        SmallPackets.LightUpdate u;
        try { u = SmallPackets.LightUpdate.parse(b, from, engine.bitsetBytes); }
        catch (RuntimeException bad) { return unreadable(v, ViewKind.LIGHT, b, w, chain, null, bad); }
        boolean[] failed = {false};
        List<LayerRegistry.Entry> in = interested(v, w, chain, u.x(), u.z(), failed);
        if (in == null || !anyMask(in)) return b;
        if (failed[0]) { engine.stats.inc("dropped.layerError"); return null; }
        ViewEngine.Dim d = engine.world(w);
        if (d == null) { engine.stats.inc("dropped.unknownWorld"); return null; }
        long[][] shown = new long[d.sections()][];
        int base = d.minY() >> 4;
        for (int s = 0; s < d.sections(); s++) {
            long[] h = hiddenCells(v, w, in, u.x(), base + s, u.z());
            if (h == ChunkLayer.UNKNOWN) { engine.stats.inc("dropped.light"); return null; }
            if (h == null) continue;
            long[] m = new long[64];
            for (int i = 0; i < 64; i++) m[i] = ~h[i];
            shown[s] = m;
        }
        byte[] out = u.zero(b, shown);
        if (out != b) engine.stats.inc("light.zeroed");
        return out;
    }

    private byte[] section(ViewerSlot v, byte[] b, int from, UUID w, LayerRegistry.Entry[] chain) {
        SmallPackets.SectionUpdate u;
        try { u = SmallPackets.SectionUpdate.parse(b, from); }
        catch (RuntimeException bad) { return unreadable(v, ViewKind.SECTION, b, w, chain, null, bad); }
        boolean[] failed = {false};
        List<LayerRegistry.Entry> in = interested(v, w, chain, u.sx(), u.sz(), failed);
        if (in == null || !anyMask(in)) return b;
        if (failed[0]) { engine.stats.inc("dropped.layerError"); return null; }
        long[] h = hiddenCells(v, w, in, u.sx(), u.sy(), u.sz());
        if (h == ChunkLayer.UNKNOWN) { engine.stats.inc("dropped.section"); return null; }
        if (h == null) return b;
        byte[] out = u.trim(b, k -> (h[k >>> 6] & (1L << (k & 63))) != 0);
        if (out != b) engine.stats.inc(out == null ? "dropped.section" : "section.trimmed");
        return out;
    }
}
