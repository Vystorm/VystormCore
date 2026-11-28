package Vystorm.vystorm_core.packet.internal.wire;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.packet.internal.wire.Cells;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SectionCodecTest {
    final WireTables t = WireFixtures.tables();

    @Test
    void givenSectionsOfEveryContainerForm_whenEncodedAndDecoded_thenIdsAndCountsRoundTrip() {
        int[][] ids = WireFixtures.world(5, 1);
        byte[] buf = SectionCodec.encode(ids, t);
        SectionCodec.Decoded d = SectionCodec.decode(buf, 5);
        for (int s = 0; s < 5; s++) assertArrayEquals(ids[s], d.ids[s], "section " + s);
        assertEquals(4096, d.blockCount[0]);
        assertEquals(0, d.blockCount[4], "an air section counts no blocks");
        assertEquals(2048, d.blockCount[2]);
        int water = (int) Arrays.stream(ids[3]).filter(i -> i == WireFixtures.WATER).count();
        assertEquals(water, d.fluidCount[3]);
        assertEquals(0, d.trailing);
        // section 0: counts (4), one value (bits 0 + varint 1), biomes (2) = 8 bytes; section 1: counts, then its bits byte
        assertEquals(t.directBits(), buf[12] & 255, "section 1 with 500 ids is written direct");
    }


    @Test
    void givenAMask_whenRewritten_thenHiddenCellsAreTheFillAndCountsMatchARecount() {
        int[][] ids = WireFixtures.world(5, 2);
        byte[] buf = SectionCodec.encode(ids, t);
        long[][] shown = new long[5][];
        shown[0] = Cells.ALL;
        shown[1] = new long[64];
        for (int k = 0; k < 4096; k += 3) Cells.set(shown[1], k);
        shown[2] = Cells.NONE;
        shown[3] = new long[64];
        for (int k = 0; k < 4096; k++) if ((k & 15) < 8) Cells.set(shown[3], k);
        shown[4] = null;
        int[] fill = {1, 1, 1, 2, 1};
        SectionCodec.Result r = SectionCodec.rewrite(buf, 5, shown, fill, t);
        SectionCodec.Decoded d = SectionCodec.decode(r.buffer, 5);
        for (int s = 0; s < 5; s++) {
            int nonAir = 0, fluid = 0;
            for (int k = 0; k < 4096; k++) {
                boolean visible = shown[s] == null || Cells.bit(shown[s], k);
                assertEquals(visible ? ids[s][k] : fill[s], d.ids[s][k], "section " + s + " cell " + k);
                if (!t.air(d.ids[s][k])) nonAir++;
                if (t.fluid(d.ids[s][k])) fluid++;
            }
            assertEquals(nonAir, d.blockCount[s], "block count of section " + s);
            assertEquals(fluid, d.fluidCount[s], "fluid count of section " + s);
        }
        assertEquals(2, r.sectionsKept);
        assertEquals(1, r.sectionsHidden);
        assertEquals(2, r.sectionsRewritten);
        assertEquals(0, d.trailing);
    }

    @Test
    void givenTrailingBytesAfterTheSections_whenRewritten_thenTheyAreKept() {
        int[][] ids = WireFixtures.world(2, 3);
        byte[] base = SectionCodec.encode(ids, t);
        byte[] buf = Arrays.copyOf(base, base.length + 3);
        buf[buf.length - 1] = 9;
        SectionCodec.Result r = SectionCodec.rewrite(buf, 2, new long[][] {Cells.NONE, Cells.NONE}, new int[] {1, 1}, t);
        assertEquals(9, r.buffer[r.buffer.length - 1]);
        assertEquals(3, SectionCodec.decode(r.buffer, 2).trailing);
    }

    @Test
    void givenABrokenBuffer_whenRewritten_thenItThrowsMalformedInsteadOfWritingGarbage() {
        byte[] buf = SectionCodec.encode(WireFixtures.world(2, 4), t);
        byte[] cut = Arrays.copyOf(buf, buf.length / 2);
        assertThrows(Bytes.Malformed.class, () -> SectionCodec.rewrite(cut, 2, new long[][] {Cells.NONE, Cells.NONE}, new int[] {1, 1}, t));
    }
}
