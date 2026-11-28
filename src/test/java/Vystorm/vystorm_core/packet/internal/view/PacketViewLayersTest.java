package Vystorm.vystorm_core.packet.internal.view;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.packet.internal.wire.Bytes;
import Vystorm.vystorm_core.packet.internal.wire.Cells;
import Vystorm.vystorm_core.packet.internal.wire.ChunkPacket;
import Vystorm.vystorm_core.packet.internal.wire.LightData;
import Vystorm.vystorm_core.packet.internal.wire.SectionCodec;
import Vystorm.vystorm_core.packet.internal.wire.SmallPackets;
import Vystorm.vystorm_core.packet.internal.wire.WireFixtures;
import Vystorm.vystorm_core.packet.internal.wire.WireTables;
import Vystorm.vystorm_core.packet.view.ChunkEdit;
import Vystorm.vystorm_core.packet.view.ChunkLayer;
import Vystorm.vystorm_core.packet.view.LayerSpec;
import Vystorm.vystorm_core.packet.view.PacketViewTesting;
import Vystorm.vystorm_core.packet.view.SectionEdit;
import Vystorm.vystorm_core.packet.view.ViewChunks;
import Vystorm.vystorm_core.packet.view.ViewerRef;
import Vystorm.vystorm_core.packet.view.WireStates;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The layer chain on real 26.x chunk bytes ({@link WireFixtures}), without a server: a MASK layer that writes exactly its
 * hidden cells sends the same bytes as AntiXray's reference rewrite; sections no layer touched keep their bytes and are
 * never decoded; layers run CONTENT before MASK by priority and age; a later mask cannot show what an earlier one hid;
 * the fail-closed rules; what the client holds after chunks, forgets and world changes.
 */
class PacketViewLayersTest {
    static final UUID OVERWORLD = UUID.nameUUIDFromBytes("overworld".getBytes()), NETHER = UUID.nameUUIDFromBytes("nether".getBytes());
    static final WireTables T = WireFixtures.tables();
    static final int MIN_Y = -64, SECTIONS = 24;

    static WireStates states() {
        boolean[] air = new boolean[T.size()], fluid = new boolean[T.size()];
        for (int i = 0; i < air.length; i++) { air[i] = T.air(i); fluid[i] = T.fluid(i); }
        return new WireStates(air, fluid);
    }

    static int[] fill() {
        int[] f = new int[SECTIONS];
        for (int s = 0; s < SECTIONS; s++) f[s] = MIN_Y + s * 16 < 0 ? WireFixtures.DEEPSLATE : WireFixtures.STONE;
        return f;
    }

    /** Shown masks: section 0 all, 1 none, 2 checker, 3 random, 4 one hidden cell, the rest all shown or none by seed. */
    static long[][] shown(long seed) {
        Random r = new Random(seed);
        long[][] m = new long[SECTIONS][];
        for (int s = 0; s < SECTIONS; s++) {
            long[] a = new long[Cells.WORDS];
            switch (s % 6) {
                case 0 -> Arrays.fill(a, -1L);
                case 1 -> { }
                case 2 -> { for (int k = 0; k < 4096; k++) if (((k & 15) + (k >> 8) + ((k >> 4) & 15) & 1) == 0) Cells.set(a, k); }
                case 3 -> { for (int i = 0; i < 64; i++) a[i] = r.nextLong(); }
                case 4 -> { Arrays.fill(a, -1L); a[7] &= ~(1L << 9); }
                default -> { if (r.nextBoolean()) Arrays.fill(a, -1L); }
            }
            m[s] = Cells.share(a);
        }
        return m;
    }

    /** A MASK layer that hides the cells a fixed shown mask leaves out, as AntiXray writes them (fill per section). */
    static final class FixedMask implements ChunkLayer {
        final long[][] shown;
        final int[] fill;
        final List<String> calls = new ArrayList<>();

        FixedMask(long[][] shown, int[] fill) { this.shown = shown; this.fill = fill; }

        @Override public boolean interested(ViewerRef v, UUID w, int cx, int cz) { return OVERWORLD.equals(w); }

        @Override public void apply(ViewerRef v, ChunkEdit chunk) {
            for (int s = 0; s < chunk.sections(); s++) {
                long[] m = shown[s];
                if (m == null || Cells.isAll(m)) continue;
                long[] hidden = new long[Cells.WORDS];
                for (int i = 0; i < Cells.WORDS; i++) hidden[i] = ~m[i];
                chunk.section(s).fill(hidden, fill[s]);
            }
        }

        @Override public long[] hiddenCells(ViewerRef v, UUID w, int cx, int sy, int cz) {
            int s = sy - (MIN_Y >> 4);
            if (s < 0 || s >= SECTIONS || shown[s] == null || Cells.isAll(shown[s])) return null;
            long[] h = new long[64];
            for (int i = 0; i < 64; i++) h[i] = ~shown[s][i];
            return h;
        }

        @Override public void forgotten(ViewerRef v, UUID w, int cx, int cz) { calls.add("forgotten " + cx + " " + cz); }

        @Override public void clientWorld(ViewerRef v, UUID w, int minY, int sections) { calls.add("world " + (w.equals(NETHER) ? "nether" : "overworld") + " " + minY + " " + sections); }
    }

    static LayerSpec spec(String id) { return LayerSpec.builder(id).mask().objects(true).build(); }

    static PacketViewTesting.Bench bench(boolean bitsetBytes) {
        return PacketViewTesting.bench(states(), bitsetBytes).world(OVERWORLD, MIN_Y, SECTIONS).world(NETHER, 0, 16);
    }

    static byte[] chunk(int cx, int cz, long seed, boolean bitsetBytes) { return WireFixtures.chunk(cx, cz, WireFixtures.world(SECTIONS, seed), T, MIN_Y, bitsetBytes); }

    @Test
    void givenAMaskThatWritesItsHiddenCells_thenTheBytesEqualTheReferenceRewrite_withBothBitsetForms() {
        for (boolean bytes : new boolean[] {false, true})
            for (long seed = 1; seed <= 6; seed++) {
                long[][] shown = shown(seed);
                PacketViewTesting.Bench b = bench(bytes);
                b.layer(null, spec("test:mask"), new FixedMask(shown, fill()));
                ViewerRef v = b.viewer(UUID.randomUUID(), "v", OVERWORLD);
                byte[] whole = chunk(3, -4, seed, bytes);
                byte[] mine = b.rewrite(v, "chunk", whole, null);
                ChunkPacket.Rewrite ref = ChunkPacket.parse(whole, 2, bytes).rewrite(shown, fill(), T, MIN_Y);
                assertArrayEquals(ref.bytes, mine, "seed " + seed + (bytes ? " (26.3 bitsets)" : " (26.2 bitsets)"));
                assertTrue(ref.blockEntitiesRemoved > 0 || ref.lightArraysZeroed > 0, "the fixture exercises the block entity and light rules");
            }
    }

    @Test
    void givenNoInterestedLayer_thenTheVerySameArrayPasses_andNothingIsParsed() {
        PacketViewTesting.Bench b = bench(false);
        b.layer(null, spec("test:mask"), new FixedMask(shown(1), fill()));
        ViewerRef v = b.viewer(UUID.randomUUID(), "v", NETHER);
        byte[] garbage = {46, 0, 0, 0, 1, 0, 0, 0, 2, 99, 99};
        assertSame(garbage, b.rewrite(v, "chunk", garbage, NETHER), "the layer is not interested in the nether: unread, unchanged");
    }

    @Test
    void givenALayerThatTouchesOneSection_thenOnlyThatSectionIsDecodedAndEncoded() {
        int[][] ids = WireFixtures.world(SECTIONS, 5);
        byte[] whole = WireFixtures.chunk(0, 0, ids, T, MIN_Y);
        ChunkPacket p = ChunkPacket.parse(whole, 2, false);
        ChunkEditImpl e = new ChunkEditImpl(p, OVERWORLD, MIN_Y, SECTIONS, T);
        e.mask(true);
        SectionEdit s5 = e.section(5);
        s5.set(0, WireFixtures.STONE);
        for (int i = 0; i < SECTIONS; i++) if (i != 5) assertTrue(e.section(i).untouched(), "section " + i + " untouched");
        byte[] out = e.encode();
        assertEquals(1, e.sectionsRewritten);
        SectionCodec.Decoded d = SectionCodec.decode(ChunkPacket.parse(out, 2, false).sectionBuffer(), SECTIONS);
        for (int s = 0; s < SECTIONS; s++) for (int k = 0; k < 4096; k++) {
            int want = s == 5 && k == 0 ? WireFixtures.STONE : ids[s][k];
            assertEquals(want, d.ids[s][k], "section " + s + " cell " + k);
        }
        // the untouched sections are byte for byte the server's
        byte[] before = p.sectionBuffer(), after = ChunkPacket.parse(out, 2, false).sectionBuffer();
        assertArrayEquals(Arrays.copyOfRange(before, 0, 20), Arrays.copyOfRange(after, 0, 20), "section 0 kept its bytes");
        assertEquals(WireFixtures.STONE, s5.get(0));
        assertEquals(ids[5][0], s5.real(0), "the real state is still known after a write");
    }

    @Test
    void givenNothingWritten_thenTheSameArrayComesBack_butAWrongHeightIsStillRefused() {
        byte[] whole = chunk(0, 0, 2, false);
        ChunkEditImpl e = new ChunkEditImpl(ChunkPacket.parse(whole, 2, false), OVERWORLD, MIN_Y, SECTIONS, T);
        assertSame(whole, e.encode());
        ChunkEditImpl nether = new ChunkEditImpl(ChunkPacket.parse(whole, 2, false), NETHER, 0, 16, T);
        assertThrows(Bytes.Malformed.class, nether::encode, "24 sections read as 16: never sent unchanged as if it fitted");
    }

    @Test
    void givenLayersOfBothKinds_thenContentRunsBeforeMask_eachByPriorityThenAge() {
        LayerRegistry r = new LayerRegistry(e -> { });
        ChunkLayer noop = new ChunkLayer() {
            @Override public boolean interested(ViewerRef v, UUID w, int cx, int cz) { return true; }
            @Override public void apply(ViewerRef v, ChunkEdit c) { }
        };
        r.add(LayerSpec.builder("a:mask-late").mask().priority(10).build(), null, noop);
        r.add(LayerSpec.builder("a:content-high").content().priority(50).build(), null, noop);
        r.add(LayerSpec.builder("a:mask-early").mask().priority(-100).build(), null, noop);
        r.add(LayerSpec.builder("a:content-low").content().priority(-5).build(), null, noop);
        r.add(LayerSpec.builder("a:mask-early-2").mask().priority(-100).build(), null, noop);
        r.add(LayerSpec.builder("a:nether-only").mask().world(NETHER).build(), null, noop);
        List<String> order = new ArrayList<>();
        for (LayerRegistry.Entry e : r.all()) order.add(e.spec().id());
        assertEquals(List.of("a:content-low", "a:content-high", "a:mask-early", "a:mask-early-2", "a:nether-only", "a:mask-late"), order);
        assertEquals(5, r.forWorld(OVERWORLD).length, "a layer of one world is left out elsewhere");
        assertThrows(IllegalStateException.class, () -> r.add(LayerSpec.builder("a:mask-late").build(), null, noop), "ids are unique");
        assertThrows(IllegalArgumentException.class, () -> LayerSpec.builder("NoNamespace").build());
        assertThrows(IllegalArgumentException.class, () -> LayerSpec.builder("a:b").maxHoldMillis(LayerSpec.MAX_HOLD_MILLIS + 1).build());
    }

    @Test
    void givenTwoMasks_thenTheSecondCannotShowWhatTheFirstHid_andBothHideTogether() {
        int[][] ids = WireFixtures.world(SECTIONS, 7);
        PacketViewTesting.Bench b = bench(false);
        long[][] firstShown = new long[SECTIONS][];
        Arrays.fill(firstShown, Cells.ALL);
        long[] half = new long[64];
        Arrays.fill(half, 0, 32, -1L); // the lower half of section 3 shown, the upper half hidden
        firstShown[3] = half;
        b.layer(null, LayerSpec.builder("a:first").mask().priority(0).build(), new FixedMask(firstShown, fill()));
        // the second mask tries to write the real states back into every cell of section 3, and hides section 4
        b.layer(null, LayerSpec.builder("b:second").mask().priority(1).build(), new ChunkLayer() {
            @Override public boolean interested(ViewerRef v, UUID w, int cx, int cz) { return true; }
            @Override public void apply(ViewerRef v, ChunkEdit c) {
                SectionEdit s = c.section(3);
                for (int k = 0; k < 4096; k++) s.set(k, s.real(k));
                c.section(4).fill(Cells.ALL, WireFixtures.STONE);
            }
        });
        ViewerRef v = b.viewer(UUID.randomUUID(), "v", OVERWORLD);
        byte[] out = b.rewrite(v, "chunk", WireFixtures.chunk(0, 0, ids, T, MIN_Y), null);
        ChunkPacket p = ChunkPacket.parse(out, 2, false);
        SectionCodec.Decoded d = SectionCodec.decode(p.sectionBuffer(), SECTIONS);
        for (int k = 2048; k < 4096; k++) assertEquals(fill()[3], d.ids[3][k], "cell " + k + " stays the first mask's fill");
        for (int k = 0; k < 2048; k++) assertEquals(ids[3][k], d.ids[3][k], "the second mask wrote the real state into the cells the first left shown");
        for (int k = 0; k < 4096; k++) assertEquals(WireFixtures.STONE, d.ids[4][k]);
        // light: every written cell counts as hidden, so all of sections 3 and 4 are dark
        for (LightData.Array a : p.light.arrays) {
            if (a.section() == 3) for (int k = 0; k < 4096; k++) assertEquals(0, LightData.nibble(out, a.offset(), k));
            if (a.section() == 4) for (int k = 0; k < 4096; k++) assertEquals(0, LightData.nibble(out, a.offset(), k));
        }
        // WireFixtures has block entities at y -14 and -1 (both in section 3, written by a mask: removed, also where the
        // second mask wrote the real state) and -59 (section 0, shown: kept)
        assertEquals(1, p.blockEntities.size());
        assertEquals(-59, p.blockEntities.get(0).y());
    }

    @Test
    void givenFailures_thenAnInterestedMaskDropsThePacket_andAnUninterestedOneLetsItPass() {
        PacketViewTesting.Bench b = bench(false);
        b.layer(null, spec("a:boom"), new ChunkLayer() {
            @Override public boolean interested(ViewerRef v, UUID w, int cx, int cz) { if (cx == 9) throw new IllegalStateException("interested"); return cx != 8; }
            @Override public void apply(ViewerRef v, ChunkEdit c) { if (c.chunkX() == 7) throw new IllegalStateException("apply"); c.section(0).set(0, WireFixtures.STONE); }
            @Override public long[] hiddenCells(ViewerRef v, UUID w, int cx, int sy, int cz) { return cx == 6 ? UNKNOWN : null; }
        });
        ViewerRef v = b.viewer(UUID.randomUUID(), "v", OVERWORLD);
        assertNull(b.rewrite(v, "chunk", chunk(7, 0, 1, false), null), "apply threw: not sent");
        assertEquals(1, b.counter("dropped.layerError"));
        assertNull(b.rewrite(v, "chunk", chunk(9, 0, 1, false), null), "interested threw: not sent");
        byte[] notInterested = chunk(8, 0, 1, false);
        assertSame(notInterested, b.rewrite(v, "chunk", notInterested, null));
        byte[] garbage = {46, 0, 0, 0, 1, 0, 0, 0, 2, 99, 99};
        assertNull(b.rewrite(v, "chunk", garbage, null), "unreadable bytes in a chunk a mask wants: not sent");
        assertEquals(1, b.counter("unreadable.chunk"));
        byte[] shortOne = {46, 0, 0};
        assertNull(b.rewrite(v, "light", shortOne, null), "an unreadable light update is not sent when a mask wants the world");
        assertNotNull(b.rewrite(v, "chunk", chunk(1, 0, 1, false), null), "a good chunk goes out");
        // light updates: UNKNOWN drops, nothing hidden passes the same array
        byte[] light6 = lightUpdate(6, 0), light5 = lightUpdate(5, 0);
        assertNull(b.rewrite(v, "light", light6, null));
        assertSame(light5, b.rewrite(v, "light", light5, null));
    }

    @Test
    void givenLightAndSectionUpdates_thenHiddenCellsAreZeroedAndTrimmed() {
        long[][] shown = new long[SECTIONS][];
        Arrays.fill(shown, Cells.ALL);
        long[] m = Cells.ALL.clone();
        m[0] = 0; // cells 0..63 of section 2 hidden
        shown[2] = m;
        PacketViewTesting.Bench b = bench(false);
        b.layer(null, spec("a:mask"), new FixedMask(shown, fill()));
        ViewerRef v = b.viewer(UUID.randomUUID(), "v", OVERWORLD);
        byte[] lu = lightUpdate(0, 0);
        byte[] out = b.rewrite(v, "light", lu, null);
        assertNotSame(lu, out);
        SmallPackets.LightUpdate u = SmallPackets.LightUpdate.parse(out, 2, false);
        for (LightData.Array a : u.light().arrays) {
            if (a.section() != 2) continue;
            for (int k = 0; k < 64; k++) assertEquals(0, LightData.nibble(out, a.offset(), k));
            assertEquals(LightData.nibble(lu, a.offset(), 100), LightData.nibble(out, a.offset(), 100));
        }
        // a section update of section 2 (absolute section y -2) with cells 5 (hidden), 70 and 300 (shown)
        byte[] su = sectionUpdate(0, -2, 0, 5, 70, 300);
        byte[] trimmed = b.rewrite(v, "section", su, null);
        assertEquals(2, SmallPackets.SectionUpdate.parse(trimmed, 2).entries().length);
        assertNull(b.rewrite(v, "section", sectionUpdate(0, -2, 0, 5, 6), null), "every entry hidden: the packet is not sent");
        byte[] other = sectionUpdate(0, -1, 0, 5);
        assertSame(other, b.rewrite(v, "section", other, null), "nothing hidden in that section");
    }

    @Test
    void givenChunksForgetsAndAWorldChange_thenTheHeldChunksFollowTheClient_andLayersHearItInOrder() {
        PacketViewTesting.Bench b = bench(false);
        FixedMask mask = new FixedMask(shown(1), fill());
        b.layer(null, spec("a:mask"), mask);
        ViewerRef v = b.viewer(UUID.randomUUID(), "v", OVERWORLD);
        b.rewrite(v, "chunk", chunk(1, 2, 1, false), null);
        b.rewrite(v, "chunk", chunk(3, 4, 1, false), null);
        assertEquals(java.util.Set.of(ViewChunks.key(1, 2), ViewChunks.key(3, 4)), b.held(v));
        b.forget(v, 1, 2);
        assertEquals(java.util.Set.of(ViewChunks.key(3, 4)), b.held(v));
        b.clientWorld(v, NETHER);
        assertTrue(b.held(v).isEmpty(), "a new world: nothing held");
        // a chunk of the old world that goes out after the switch is masked with its own world, not recorded for the new one
        assertNotNull(b.rewrite(v, "chunk", chunk(5, 5, 1, false), OVERWORLD));
        assertTrue(b.held(v).isEmpty());
        assertEquals(List.of("forgotten 1 2", "world nether 0 16"), mask.calls);
    }

    static byte[] lightUpdate(int cx, int cz) {
        Bytes.Writer w = new Bytes.Writer(200000);
        w.varint(130);
        w.varint(cx);
        w.varint(cz);
        int n = SECTIONS + 2;
        long bits = (1L << n) - 1;
        for (int i = 0; i < 2; i++) { w.varint(1); w.s64(bits); }
        w.varint(0);
        w.varint(0);
        for (int layer = 0; layer < 2; layer++) {
            w.varint(n);
            for (int s = 0; s < n; s++) { w.varint(2048); for (int i = 0; i < 2048; i++) w.u8(0x77); }
        }
        return w.done();
    }

    static byte[] sectionUpdate(int sx, int sy, int sz, int... cells) {
        Bytes.Writer w = new Bytes.Writer(64);
        w.varint(131);
        w.s64(((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF));
        w.varint(cells.length);
        for (int k : cells) {
            int x = k & 15, z = (k >> 4) & 15, y = k >> 8;
            w.varlong((long) WireFixtures.ORE << 12 | x << 8 | z << 4 | y);
        }
        return w.done();
    }
}
