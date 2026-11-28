package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.packet.internal.wire.Bytes;
import Vystorm.vystorm_core.packet.internal.wire.Cells;
import Vystorm.vystorm_core.packet.internal.wire.ChunkPacket;
import Vystorm.vystorm_core.packet.internal.wire.LightData;
import Vystorm.vystorm_core.packet.internal.wire.SectionCodec;
import Vystorm.vystorm_core.packet.internal.wire.SmallPackets;
import Vystorm.vystorm_core.packet.internal.wire.WireTables;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import org.bukkit.World;

/**
 * The proof that the byte formats the packet view rewrites are exactly what this server writes and reads (moved from
 * VystormAntiXray's {@code platform.SelfTest}): a real loaded chunk is encoded by the server's own codec, rewritten with
 * a checker mask through the same {@link ChunkEditImpl} the layers use (and compared byte for byte with the plain
 * reference rewrite), decoded by the server's own codec and its sections read by the server's own section reader; the
 * same for a light update and a section update. Server thread. Throws with the first difference; returns a one-line
 * summary. Internal: not part of the public API.
 */
public final class ViewSelfTest {
    private ViewSelfTest() {}

    /** Where the bytes of a packet that did not parse are written for a bug report (null: nowhere). */
    public static volatile java.nio.file.Path dumpDir;

    /** The outcome: a summary line and how this server writes bitsets. */
    public record Result(String summary, boolean bitsetBytes) {}

    public static Result run(ViewNms nms, WireTables t, World w, int cx, int cz, int[] fill) throws Exception { return run(nms, t, w, cx, cz, fill, null); }

    /** {@code form}: the bitset form to prove (true bytes, false longs), or null to find it (at start). */
    public static Result run(ViewNms nms, WireTables t, World w, int cx, int cz, int[] fill, Boolean form) throws Exception {
        long t0 = System.nanoTime();
        // 0. the byte side tells chunk, light and section packets by their play packet id: the three must differ and
        // be above every configuration packet id, so no buffer of a connection back in configuration is taken for one
        int lowest = Math.min(nms.chunkId, Math.min(nms.lightId, nms.sectionId));
        if (nms.chunkId == nms.lightId || nms.chunkId == nms.sectionId || nms.lightId == nms.sectionId || lowest <= nms.maxConfigurationId)
            throw new IllegalStateException("packet ids chunk " + nms.chunkId + ", light " + nms.lightId + ", section " + nms.sectionId + " are not apart from the configuration packets (up to " + nms.maxConfigurationId + ")");
        int minY = w.getMinHeight(), sections = (w.getMaxHeight() - minY) >> 4;
        Object level = nms.level(w), lc = nms.chunkNow(level, cx, cz);
        if (lc == null) throw new IllegalStateException("chunk " + cx + " " + cz + " is not loaded");
        Class<?> sectionType = ViewNms.cls("net.minecraft.world.level.chunk.LevelChunkSection");
        Method getState = sectionType.getMethod("getBlockState", int.class, int.class, int.class);
        Method copy = sectionType.getMethod("copy");
        Method read = sectionType.getMethod("read", ViewNms.cls("net.minecraft.network.FriendlyByteBuf"));

        // 1. the server's chunk packet, parsed by this plugin: position, every block state, the header counts
        byte[] body = nms.encode(nms.chunkPacket(level, lc));
        byte[] whole = withId(body);
        // 26.2 writes bitsets as longs, 26.3 as bytes: the form that parses to the exact end and passes every check below wins
        ChunkPacket p;
        boolean bitsetBytes;
        if (form != null) {
            try { p = ChunkPacket.parse(whole, 1, form); bitsetBytes = form; }
            catch (Bytes.Malformed bad) { dump("chunk", whole); throw bad; }
        } else try { p = ChunkPacket.parse(whole, 1, false); bitsetBytes = false; }
        catch (Bytes.Malformed longs) {
            try { p = ChunkPacket.parse(whole, 1, true); bitsetBytes = true; }
            catch (Bytes.Malformed bad) { dump("chunk", whole); throw bad; }
        }
        if (p.x != cx || p.z != cz) throw new IllegalStateException("chunk position read as " + p.x + " " + p.z);
        SectionCodec.Decoded d = SectionCodec.decode(p.sectionBuffer(), sections);
        if (d.trailing != 0) throw new IllegalStateException(d.trailing + " bytes after " + sections + " sections");
        int states = 0;
        for (int s = 0; s < sections; s++) {
            Object sec = nms.section(lc, s);
            int nonAir = 0, fluid = 0;
            for (int k = 0; k < 4096; k++) {
                int id = d.ids[s][k];
                if (id != nms.stateId(getState.invoke(sec, k & 15, k >> 8, (k >> 4) & 15))) throw new IllegalStateException("section " + s + " cell " + k + " read as id " + id);
                if (!t.air(id)) nonAir++;
                if (t.fluid(id)) fluid++;
                states++;
            }
            if (nonAir != d.blockCount[s] || fluid != d.fluidCount[s]) throw new IllegalStateException("section " + s + " counts " + d.blockCount[s] + "/" + d.fluidCount[s] + ", recount " + nonAir + "/" + fluid);
        }

        // 2. rewrite with a checker mask, decode with the server's codec, read with the server's section reader
        long[][] mask = new long[sections][];
        for (int s = 0; s < sections; s++) {
            long[] m = new long[Cells.WORDS];
            for (int k = 0; k < 4096; k++) if (((k & 15) + (k >> 8) + ((k >> 4) & 15) & 1) == 0) Cells.set(m, k);
            mask[s] = s == 0 ? Cells.NONE : s == sections - 1 ? Cells.ALL : m;
        }
        long r0 = System.nanoTime();
        ChunkEditImpl edit = new ChunkEditImpl(p, w.getUID(), minY, sections, t);
        for (int s = 0; s < sections; s++) {
            if (Cells.isAll(mask[s])) continue;
            long[] hidden = new long[Cells.WORDS];
            for (int i = 0; i < Cells.WORDS; i++) hidden[i] = ~mask[s][i];
            edit.section(s).fill(hidden, fill[s]);
        }
        byte[] viaEdit = edit.encode();
        long rewriteNs = System.nanoTime() - r0;
        ChunkPacket.Rewrite rw = p.rewrite(mask, fill, t, minY);
        if (!java.util.Arrays.equals(viaEdit, rw.bytes)) { dump("chunk-edit", viaEdit); dump("chunk-reference", rw.bytes); throw new IllegalStateException("the layer path wrote other bytes than the reference rewrite"); }
        Object back = nms.decode(nms.chunkPacket, withoutId(rw.bytes));
        Object data = ViewNms.method(back.getClass(), "getChunkData", "chunkData").invoke(back);
        Object rb = data.getClass().getMethod("getReadBuffer").invoke(data);
        int wrong = 0;
        for (int s = 0; s < sections; s++) {
            Object real = nms.section(lc, s), got = copy.invoke(real);
            read.invoke(got, rb);
            for (int k = 0; k < 4096; k++) {
                int x = k & 15, y = k >> 8, z = (k >> 4) & 15;
                int want = Cells.bit(mask[s], k) ? nms.stateId(getState.invoke(real, x, y, z)) : fill[s];
                if (nms.stateId(getState.invoke(got, x, y, z)) != want) wrong++;
            }
        }
        if (wrong != 0) throw new IllegalStateException(wrong + " cells read back wrong by the server's section reader");
        int left = ((io.netty.buffer.ByteBuf) rb).readableBytes();
        if (left != 0) throw new IllegalStateException(left + " bytes left after the server read every section");
        Object light = ViewNms.method(back.getClass(), "getLightData", "lightData").invoke(back);
        ChunkPacket q = ChunkPacket.parse(rw.bytes, 1, bitsetBytes);
        compareLight(light, q.light, rw.bytes);
        for (LightData.Array a : q.light.arrays) {
            if (a.section() < 0 || a.section() >= sections) continue;
            for (int k = 0; k < 4096; k++)
                if (!Cells.bit(mask[a.section()], k) && LightData.nibble(rw.bytes, a.offset(), k) != 0) throw new IllegalStateException("light left in a hidden cell");
        }

        // 3. a light update packet
        BitSet all = new BitSet();
        all.set(0, sections + 2);
        byte[] lu = withId(nms.encode(nms.lightUpdate(level, cx, cz, all, all)));
        SmallPackets.LightUpdate u = SmallPackets.LightUpdate.parse(lu, 1, bitsetBytes);
        if (u.x() != cx || u.z() != cz) throw new IllegalStateException("light update position read as " + u.x() + " " + u.z());
        byte[] luz = u.zero(lu, mask);
        Object luBack = nms.decode(nms.lightPacket, withoutId(luz));
        compareLight(ViewNms.method(luBack.getClass(), "getLightData", "lightData").invoke(luBack), SmallPackets.LightUpdate.parse(luz, 1, bitsetBytes).light(), luz);

        // 4. a section blocks update packet: positions round trip, trimmed entries vanish for the server too
        int sec = sections / 2, sy = (minY >> 4) + sec;
        int[] cells = {0, 1, 17, 273, 4095};
        byte[] su = withId(nms.encode(nms.sectionUpdate(lc, cx, sy, cz, sec, cells)));
        SmallPackets.SectionUpdate s = SmallPackets.SectionUpdate.parse(su, 1);
        if (s.sx() != cx || s.sy() != sy || s.sz() != cz || s.entries().length != cells.length) throw new IllegalStateException("section update read as " + s);
        for (int i = 0; i < cells.length; i++) {
            boolean found = false;
            for (long e : s.entries()) if (SmallPackets.SectionUpdate.cell(e) == cells[i]) found = true;
            if (!found) throw new IllegalStateException("section update cell " + cells[i] + " not found");
        }
        byte[] trimmed = s.trim(su, k -> k == 17 || k == 4095);
        Object suBack = nms.decode(nms.sectionPacket, withoutId(trimmed));
        int[] n = {0};
        Method run = suBack.getClass().getMethod("runUpdates", BiConsumer.class);
        run.invoke(suBack, (BiConsumer<Object, Object>) (pos, state) -> n[0]++);
        if (n[0] != 3) throw new IllegalStateException("the server read " + n[0] + " entries of a trimmed section update, expected 3");

        return new Result(String.format(Locale.ROOT, "PASS chunk %d %d: %d states, %d sections, %d block entities, bitsets as %s; rewrite %.3f ms, %d -> %d bytes; light and section updates; packet ids %d/%d/%d; %.0f ms in all",
                cx, cz, states, sections, p.blockEntities.size(), bitsetBytes ? "bytes" : "longs", rewriteNs / 1e6, whole.length, rw.bytes.length,
                nms.chunkId, nms.lightId, nms.sectionId, (System.nanoTime() - t0) / 1e6), bitsetBytes);
    }

    /** The server's decoded light arrays equal the arrays this plugin wrote, in the same order. */
    @SuppressWarnings("unchecked")
    static void compareLight(Object serverLight, LightData mine, byte[] bytes) throws Exception {
        List<byte[]> sky = (List<byte[]>) ViewNms.method(serverLight.getClass(), "getSkyUpdates", "skyUpdates").invoke(serverLight);
        List<byte[]> block = (List<byte[]>) ViewNms.method(serverLight.getClass(), "getBlockUpdates", "blockUpdates").invoke(serverLight);
        int si = 0, bi = 0;
        for (LightData.Array a : mine.arrays) {
            byte[] server = a.sky() ? sky.get(si++) : block.get(bi++);
            for (int i = 0; i < 2048; i++) if (server[i] != bytes[a.offset() + i]) throw new IllegalStateException("light array of section " + a.section() + " differs at byte " + i);
        }
        if (si != sky.size() || bi != block.size()) throw new IllegalStateException("light arrays: server " + sky.size() + "/" + block.size() + ", plugin " + si + "/" + bi);
    }

    static void dump(String what, byte[] bytes) {
        java.nio.file.Path dir = dumpDir;
        if (dir == null) return;
        try { java.nio.file.Files.createDirectories(dir); java.nio.file.Files.write(dir.resolve("selftest-" + what + ".bin"), bytes); }
        catch (java.io.IOException ignored) { }
    }

    static byte[] withId(byte[] body) {
        byte[] out = new byte[body.length + 1];
        System.arraycopy(body, 0, out, 1, body.length);
        return out; // fake packet id 0 (one byte)
    }

    static byte[] withoutId(byte[] whole) {
        Bytes.Reader r = new Bytes.Reader(whole, 0);
        r.varint();
        return java.util.Arrays.copyOfRange(whole, r.p, whole.length);
    }
}
