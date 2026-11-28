package Vystorm.vystorm_core.packet.internal.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * Wire cost of the old one-packet-per-cell path against section batches, and the cost of the bookkeeping. Bytes are
 * real encodings of the vanilla packet layout (checked in the 26.3 server: block update = packet id, block position as
 * long, state id as varint; section blocks update = packet id, section position as long, varint count, one varlong
 * {@code stateId << 12 | local} per cell), framed like the server's network pipeline with compression threshold 256
 * (frame length varint, data length varint, zlib for bodies of 256 bytes and more). State ids are drawn from a terrain
 * mix (air, stone, dirt, grass, water, deepslate-range ids), as a Portals preview of a landscape sends.
 */
class ClientBlocksBenchTest {
    static final int THRESHOLD = 256;
    static final int[] PALETTE = {0, 0, 0, 1, 1, 1, 1, 2, 10, 10, 9, 86, 86, 118, 4, 6, 27011, 27011, 27015, 15530};

    record Wire(long packets, long raw, long framed) {}

    @Test
    void givenPreviewSizesWhenEncodedThenSectionBatchesNeedFarFewerPacketsAndBytes() {
        List<String> lines = new ArrayList<>();
        for (int edge : new int[]{16, 32}) {
            long[] cells = box(edge, 8);
            int[] states = states(cells.length, 42);
            Wire old = singles(cells, states);
            Wire batched = sections(cells, states);
            lines.add(String.format(Locale.ROOT,
                    "%,d cells: old %,d packets, %,d bytes raw, %,d bytes on the wire | new %,d packets, %,d bytes raw, %,d bytes on the wire | %.1fx fewer bytes on the wire",
                    cells.length, old.packets(), old.raw(), old.framed(), batched.packets(), batched.raw(), batched.framed(),
                    old.framed() / (double) batched.framed()));
            assertTrue(batched.packets() * 100 < old.packets(), "at least 100x fewer packets");
            assertTrue(batched.framed() < old.framed(), "fewer bytes");
        }
        lines.forEach(System.out::println);
    }

    @Test
    void givenHundredViewersWithoutChangesWhenTickingThenTheBookkeepingCostsNearlyNothing() {
        ClientBlockStore store = new ClientBlockStore(1 << 20, 1 << 20);
        CountingEnv env = new CountingEnv();
        UUID world = UUID.randomUUID();
        long[] cells = box(16, 8);
        int[] states = states(cells.length, 7);
        for (int i = 0; i < 100; i++) {
            ViewerModel v = store.viewer(new UUID(1, i), world, -4, 19);
            BlockSetModel set = store.open("Portals", "preview", 0, false);
            store.attach(set, v);
            store.putAll(set, cells, states, cells.length);
        }
        store.tick(1, env);
        assertEquals(100L * cells.length, env.cells, "everything sent in the first tick");
        env.first = false;
        long t0 = System.nanoTime();
        int ticks = 200_000;
        for (int t = 2; t < ticks + 2; t++) store.tick(t, env);
        double perTick = (System.nanoTime() - t0) / (double) ticks;

        Random random = new Random(1);
        int probes = 2_000_000;
        int hits = 0;
        long t1 = System.nanoTime();
        for (int i = 0; i < probes; i++) if (store.watched(world, random.nextInt(4096) - 2048, random.nextInt(384) - 64, random.nextInt(4096) - 2048)) hits++;
        double perProbe = (System.nanoTime() - t1) / (double) probes;

        // A 32,768-cell set built into section batches (the server-thread work before Bukkit's packet building);
        // the last of five rounds, so the JIT has compiled the paths.
        CountingEnv bigEnv = null;
        double buildMs = 0;
        long[] bigCells = box(32, 8);
        int[] bigStates = states(bigCells.length, 3);
        for (int round = 0; round < 5; round++) {
            ClientBlockStore big = new ClientBlockStore(32_768, 32_768);
            ViewerModel v = big.viewer(UUID.randomUUID(), world, -4, 19);
            BlockSetModel set = big.open("Portals", "big", 0, false);
            big.attach(set, v);
            big.budget(set, 32_768);
            long t2 = System.nanoTime();
            big.putAll(set, bigCells, bigStates, bigCells.length);
            bigEnv = new CountingEnv();
            big.tick(1, bigEnv);
            buildMs = (System.nanoTime() - t2) / 1e6;
        }

        System.out.printf(Locale.ROOT, "idle tick with 100 viewers x %,d cells: %.0f ns per tick; block event probe: %.1f ns (%d hits); %,d cells into %d batches: %.1f ms%n",
                cells.length, perTick, perProbe, hits, bigEnv.cells, bigEnv.batches, buildMs);
        assertEquals(0, env.cellsAfterFirst, "no packets without changes");
        assertEquals(32_768, bigEnv.cells);
        assertTrue(perTick < 20_000, "idle tick too slow: " + perTick + " ns");
        assertTrue(perProbe < 2_000, "probe too slow: " + perProbe + " ns");
    }

    static final class CountingEnv implements ClientBlockStore.Env {
        long cells, batches, cellsAfterFirst;
        boolean first = true;
        @Override public boolean hasChunk(ViewerModel viewer, int chunkX, int chunkZ) { return true; }
        @Override public boolean inBox(ViewerModel viewer, int x, int y, int z) { return false; }
        @Override public boolean realSolid(ViewerModel viewer, int x, int y, int z) { return false; }
        @Override public boolean solid(int state) { return false; }
        @Override public boolean locate(ViewerModel viewer) { return true; }
        @Override public void send(ViewerModel viewer, long section, short[] locals, int[] states, int count) {
            cells += count;
            batches++;
            if (!first) cellsAfterFirst += count;
        }
    }

    // ------------------------------------------------------------------ wire model

    /** A cube of {@code edge}^3 cells starting at an offset inside a section, so it spans several sections. */
    static long[] box(int edge, int offset) {
        long[] cells = new long[edge * edge * edge];
        int n = 0;
        for (int y = 0; y < edge; y++) for (int z = 0; z < edge; z++) for (int x = 0; x < edge; x++)
            cells[n++] = CellKeys.cell(1024 + offset + x, 64 + offset + y, -512 + offset + z);
        return cells;
    }

    static int[] states(int n, long seed) {
        Random random = new Random(seed);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = PALETTE[random.nextInt(PALETTE.length)];
        return out;
    }

    static Wire singles(long[] cells, int[] states) {
        long raw = 0, framed = 0;
        for (int i = 0; i < cells.length; i++) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            varInt(body, 0x08); // block_update
            long8(body, cells[i]);
            varInt(body, states[i]);
            raw += body.size();
            framed += frame(body.toByteArray());
        }
        return new Wire(cells.length, raw, framed);
    }

    static Wire sections(long[] cells, int[] states) {
        java.util.TreeMap<Long, List<long[]>> bySection = new java.util.TreeMap<>();
        for (int i = 0; i < cells.length; i++) {
            long section = ClientBlockStore.sectionOfCell(cells[i]);
            bySection.computeIfAbsent(section, ignored -> new ArrayList<>()).add(new long[]{ClientBlockStore.localOfCell(cells[i]), states[i]});
        }
        long raw = 0, framed = 0;
        for (var e : bySection.entrySet()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            varInt(body, 0x54); // section_blocks_update
            long8(body, e.getKey());
            varInt(body, e.getValue().size());
            for (long[] c : e.getValue()) varLong(body, c[1] << 12 | c[0]);
            raw += body.size();
            framed += frame(body.toByteArray());
        }
        return new Wire(bySection.size(), raw, framed);
    }

    /** Frame length + data length (0 = not compressed) or zlib body, as the server's compression and prepender write. */
    static long frame(byte[] body) {
        int payload;
        if (body.length < THRESHOLD) payload = varIntSize(0) + body.length;
        else {
            Deflater deflater = new Deflater();
            deflater.setInput(body);
            deflater.finish();
            byte[] out = new byte[body.length + 64];
            int size = 0;
            while (!deflater.finished()) size += deflater.deflate(out, size, out.length - size);
            deflater.end();
            payload = varIntSize(body.length) + size;
        }
        return varIntSize(payload) + payload;
    }

    static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) { out.write((value & 0x7F) | 0x80); value >>>= 7; }
        out.write(value);
    }

    static void varLong(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) { out.write((int) (value & 0x7F) | 0x80); value >>>= 7; }
        out.write((int) value);
    }

    static void long8(ByteArrayOutputStream out, long value) { for (int i = 7; i >= 0; i--) out.write((int) (value >>> (i * 8))); }

    static int varIntSize(int value) { int n = 1; while ((value & ~0x7F) != 0) { n++; value >>>= 7; } return n; }
}
