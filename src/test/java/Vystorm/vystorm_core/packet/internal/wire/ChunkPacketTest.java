package Vystorm.vystorm_core.packet.internal.wire;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.packet.internal.wire.Cells;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ChunkPacketTest {
    final WireTables t = WireFixtures.tables();
    static final int MIN_Y = -64;

    @Test
    void givenAChunkPacket_whenParsed_thenEveryPartIsFound() {
        int[][] ids = WireFixtures.world(5, 7);
        byte[] b = WireFixtures.chunk(-3, 12, ids, t, MIN_Y);
        ChunkPacket p = ChunkPacket.parse(b, 2, false);
        assertEquals(-3, p.x);
        assertEquals(12, p.z);
        assertEquals(2, p.heightmaps.size());
        assertArrayEquals(SectionCodec.encode(ids, t), p.sectionBuffer());
        assertEquals(3, p.blockEntities.size());
        assertEquals(4, p.blockEntities.get(0).z());
        assertEquals(2 + MIN_Y + 48, p.blockEntities.get(0).y());
        assertEquals(2 * (5 + 2), p.light.arrays.size());
        assertEquals(-1, p.light.arrays.get(0).section(), "light section 0 is below the world");
        int[] tops = p.tops(MIN_Y, 5 * 16);
        assertEquals(MIN_Y + 43, tops[0], "the higher of the two heightmaps");
    }

    @Test
    void givenAMask_whenRewritten_thenHiddenCellsBlockEntitiesAndLightAreGoneAndTheRestIsUnchanged() {
        int[][] ids = WireFixtures.world(5, 8);
        byte[] b = WireFixtures.chunk(1, 2, ids, t, MIN_Y);
        ChunkPacket p = ChunkPacket.parse(b, 2, false);
        long[][] shown = {Cells.NONE, Cells.ALL, new long[64], Cells.NONE, Cells.ALL};
        for (int k = 0; k < 4096; k++) if ((k >> 8) >= 8) Cells.set(shown[2], k); // upper half of section 2 shown
        int[] fill = {2, 1, 1, 2, 1};
        ChunkPacket.Rewrite r = p.rewrite(shown, fill, t, MIN_Y);
        assertArrayEquals(new byte[] {(byte) 0x80, 1}, Arrays.copyOf(r.bytes, 2), "packet id kept");
        ChunkPacket q = ChunkPacket.parse(r.bytes, 2, false);
        assertEquals(1, q.x);
        assertEquals(2, q.z);
        SectionCodec.Decoded d = SectionCodec.decode(q.sectionBuffer(), 5);
        for (int s = 0; s < 5; s++)
            for (int k = 0; k < 4096; k++)
                assertEquals(Cells.bit(shown[s], k) ? ids[s][k] : fill[s], d.ids[s][k], "section " + s + " cell " + k);
        // block entities: (3, -14, 4) is section 3 = hidden; (5, -64, 5) section 0 hidden; (15, -1, 15) section 3 hidden
        assertEquals(3, r.blockEntitiesRemoved);
        assertEquals(0, q.blockEntities.size());
        // light: hidden cells 0, shown cells as before
        for (LightData.Array a : q.light.arrays) {
            LightData.Array before = p.light.arrays.get(q.light.arrays.indexOf(a));
            for (int k = 0; k < 4096; k++) {
                int now = LightData.nibble(r.bytes, a.offset(), k), was = LightData.nibble(b, before.offset(), k);
                boolean inWorld = a.section() >= 0 && a.section() < 5;
                if (!inWorld || Cells.bit(shown[a.section()], k)) assertEquals(was, now, "light " + a + " cell " + k);
                else assertEquals(0, now, "light of hidden cell " + k + " in " + a);
            }
        }
        assertEquals(2 * 3, r.lightArraysZeroed);
        assertEquals(2, r.sections.sectionsHidden);
    }

    @Test
    void givenAShownBlockEntity_whenRewritten_thenItsBytesStayExactlyTheSame() {
        int[][] ids = WireFixtures.world(5, 9);
        byte[] b = WireFixtures.chunk(0, 0, ids, t, MIN_Y);
        ChunkPacket p = ChunkPacket.parse(b, 2, false);
        long[][] all = {Cells.ALL, Cells.ALL, Cells.ALL, Cells.ALL, Cells.ALL};
        ChunkPacket.Rewrite r = p.rewrite(all, new int[] {1, 1, 1, 1, 1}, t, MIN_Y);
        assertArrayEquals(b, r.bytes, "an all-shown mask changes nothing");
        assertEquals(0, r.blockEntitiesRemoved);
    }

    @Test
    void givenThe263BitsetForm_whenParsedAsBytes_thenTheSameLightArraysAreFound_andAsLongsItIsMalformed() {
        int[][] ids = WireFixtures.world(5, 11);
        byte[] b = WireFixtures.chunk(4, 4, ids, t, MIN_Y, true);
        ChunkPacket p = ChunkPacket.parse(b, 2, true);
        assertEquals(2 * 7, p.light.arrays.size());
        assertEquals(-1, p.light.arrays.get(0).section());
        assertThrows(Bytes.Malformed.class, () -> ChunkPacket.parse(b, 2, false));
        long[][] shown = {Cells.NONE, Cells.ALL, Cells.ALL, Cells.ALL, Cells.ALL};
        ChunkPacket.Rewrite r = p.rewrite(shown, new int[] {2, 1, 1, 1, 1}, t, MIN_Y);
        ChunkPacket q = ChunkPacket.parse(r.bytes, 2, true);
        for (LightData.Array a : q.light.arrays) if (a.section() == 0) for (int k = 0; k < 4096; k++) assertEquals(0, LightData.nibble(r.bytes, a.offset(), k));
    }

    @Test
    void givenExtraOrMissingBytes_whenParsed_thenMalformed() {
        byte[] b = WireFixtures.chunk(0, 0, WireFixtures.world(5, 10), t, MIN_Y);
        assertThrows(Bytes.Malformed.class, () -> ChunkPacket.parse(Arrays.copyOf(b, b.length + 1), 2, false));
        assertThrows(Bytes.Malformed.class, () -> ChunkPacket.parse(Arrays.copyOf(b, b.length - 1), 2, false));
    }

    @Test
    void givenALightUpdate_whenZeroed_thenOnlyHiddenCellsChange() {
        Bytes.Writer w = new Bytes.Writer(30000);
        w.varint(42);
        w.varint(-5);
        w.varint(7);
        WireFixtures.light(w, 3);
        byte[] b = w.done();
        SmallPackets.LightUpdate u = SmallPackets.LightUpdate.parse(b, 1, false);
        assertEquals(-5, u.x());
        assertEquals(7, u.z());
        long[][] all = {Cells.ALL, Cells.ALL, Cells.ALL};
        assertSame(b, u.zero(b, all));
        byte[] z = u.zero(b, new long[][] {Cells.ALL, Cells.NONE, Cells.ALL});
        for (LightData.Array a : u.light().arrays)
            for (int k = 0; k < 4096; k++)
                assertEquals(a.section() == 1 ? 0 : LightData.nibble(b, a.offset(), k), LightData.nibble(z, a.offset(), k));
    }

    @Test
    void givenASectionUpdate_whenTrimmed_thenHiddenEntriesAreGone() {
        Bytes.Writer w = new Bytes.Writer(100);
        w.varint(9);
        long pos = ((long) (-2 & 0x3FFFFF) << 42) | ((long) (5 & 0x3FFFFF) << 20) | (-3 & 0xFFFFF);
        w.s64(pos);
        w.varint(3);
        w.varlong(1L << 12 | 1 << 8 | 2 << 4 | 3); // x 1 z 2 y 3
        w.varlong(7L << 12 | 4 << 8 | 4 << 4 | 4);
        w.varlong(300L << 12 | 15 << 8 | 15 << 4 | 15);
        byte[] b = w.done();
        SmallPackets.SectionUpdate u = SmallPackets.SectionUpdate.parse(b, 1);
        assertEquals(-2, u.sx());
        assertEquals(-3, u.sy());
        assertEquals(5, u.sz());
        assertEquals(Cells.index(1, 3, 2), SmallPackets.SectionUpdate.cell(u.entries()[0]));
        byte[] trimmed = u.trim(b, k -> k == Cells.index(4, 4, 4));
        SmallPackets.SectionUpdate v = SmallPackets.SectionUpdate.parse(trimmed, 1);
        assertEquals(2, v.entries().length);
        assertEquals(300L << 12 | 15 << 8 | 15 << 4 | 15, v.entries()[1]);
        assertSame(b, u.trim(b, k -> false));
        assertNull(u.trim(b, k -> true));
    }

    @Test
    void givenNbtOfEveryType_whenSkipped_thenTheReaderStopsRightAfterIt() {
        Bytes.Writer w = new Bytes.Writer(200);
        WireFixtures.nbt(w);
        w.u8(99);
        Bytes.Reader r = new Bytes.Reader(w.done(), 0);
        Nbt.skipRoot(r);
        assertEquals(99, r.u8());
        assertThrows(Bytes.Malformed.class, () -> Nbt.skipRoot(new Bytes.Reader(new byte[] {10, 13, 0, 1, 65}, 0)));
    }
}
