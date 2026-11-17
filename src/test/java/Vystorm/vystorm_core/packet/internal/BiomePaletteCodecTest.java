package Vystorm.vystorm_core.packet.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BiomePaletteCodecTest {
    @Test
    void spatialOverridesChangeOnlySelectedQuartCellsAcrossSections() {
        byte[] input = concat(singleton(5), singleton(9));
        byte[] result = BiomePaletteCodec.remapCells(input, 7, (cell, id) -> cell == 63 || cell == 64 ? 12 : id);
        int[] decoded = new int[128];
        BiomePaletteCodec.remapCells(result, 7, (cell, id) -> { decoded[cell] = id; return id; });
        int[] expected = new int[128];
        Arrays.fill(expected, 0, 64, 5); Arrays.fill(expected, 64, 128, 9);
        expected[63] = 12; expected[64] = 12;
        assertArrayEquals(expected, decoded);
        assertArrayEquals(concat(singleton(5), singleton(9)), input);
    }

    @Test
    void remapsSingleValuePalette() {
        byte[] input = singleton(5);

        byte[] result = BiomePaletteCodec.remapBiomes(input, 7, 8, id -> id + 1);

        assertArrayEquals(singleton(6), result);
    }

    @Test
    void remapsIndirectPaletteAndDeduplicatesMappedEntries() {
        int[] cells = new int[64];
        for (int i = 0; i < cells.length; i++) cells[i] = i % 2;
        byte[] input = indirect(new int[]{10, 11}, cells, 1);

        byte[] result = BiomePaletteCodec.remapBiomes(input, 7, 8, id -> 42);

        assertArrayEquals(singleton(42), result);
    }

    @Test
    void widensDirectPaletteFromSevenToEightBitsAtPackedWordBoundary() {
        int[] cells = new int[64];
        for (int i = 0; i < cells.length; i++) cells[i] = 119 + (i % 9);
        byte[] input = direct(cells, 7);

        byte[] result = BiomePaletteCodec.remapBiomes(input, 7, 8, id -> id + 1);

        assertArrayEquals(direct(Arrays.stream(cells).map(id -> id + 1).toArray(), 8), result);
    }

    @Test
    void chunkRemapKeepsCountersAndBlockStateContainerByteForByte() {
        byte[] blockStates = blockStateContainer();
        byte[] biome = indirect(new int[]{1, 2, 3}, sequence(64, i -> i % 3), 2);
        byte[] section = concat(new byte[]{0x12, 0x34, 0, 9}, blockStates, biome);

        byte[] result = BiomePaletteCodec.remapChunk(section, 7, 8, id -> id + 1);

        assertArrayEquals(Arrays.copyOfRange(section, 0, 4 + blockStates.length),
                Arrays.copyOfRange(result, 0, 4 + blockStates.length));
        byte[] expectedBiome = indirect(new int[]{2, 3, 4}, sequence(64, i -> i % 3), 2);
        assertArrayEquals(expectedBiome, Arrays.copyOfRange(result, 4 + blockStates.length, result.length));
    }

    @Test
    void extractsBiomeContainersFromFullChunkWithoutChangingTheirBytes() {
        byte[] blockStates = blockStateContainer();
        byte[] biomeOne = indirect(new int[]{4, 5}, sequence(64, i -> i % 2), 1);
        byte[] biomeTwo = direct(sequence(64, i -> 100 + i % 9), 7);
        byte[] fullChunk = concat(new byte[]{0, 1, 0, 2}, blockStates, biomeOne,
                new byte[]{0, 3, 0, 4}, blockStates, biomeTwo);

        byte[] result = BiomePaletteCodec.extractBiomes(fullChunk, 7);

        assertArrayEquals(concat(biomeOne, biomeTwo), result);
    }

    @Test
    void rejectsTruncatedAndOutOfRangePaletteData() {
        assertThrows(IllegalArgumentException.class,
                () -> BiomePaletteCodec.remapBiomes(new byte[]{7, 0}, 7, 8, id -> id));

        byte[] badIndex = indirect(new int[]{1}, sequence(64, ignored -> 1), 1);
        assertThrows(IllegalArgumentException.class,
                () -> BiomePaletteCodec.remapBiomes(badIndex, 7, 8, id -> id));
    }

    private static byte[] singleton(int id) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        varInt(out, id);
        return out.toByteArray();
    }

    private static byte[] indirect(int[] palette, int[] cells, int bits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bits);
        varInt(out, palette.length);
        for (int id : palette) varInt(out, id);
        int[] indexes = new int[cells.length];
        for (int i = 0; i < cells.length; i++) indexes[i] = cells[i];
        packed(out, indexes, bits);
        return out.toByteArray();
    }

    private static byte[] direct(int[] cells, int bits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bits);
        packed(out, cells, bits);
        return out.toByteArray();
    }

    private static byte[] blockStateContainer() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(4);
        varInt(out, 1);
        varInt(out, 0);
        packed(out, new int[4096], 4);
        return out.toByteArray();
    }

    private static void packed(ByteArrayOutputStream out, int[] values, int bits) {
        int perLong = 64 / bits;
        for (int base = 0; base < values.length; base += perLong) {
            long word = 0;
            for (int i = 0; i < perLong && base + i < values.length; i++)
                word |= ((long) values[base + i]) << (i * bits);
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (word >>> shift) & 255);
        }
    }

    private static int[] sequence(int length, java.util.function.IntUnaryOperator value) {
        int[] result = new int[length];
        for (int i = 0; i < length; i++) result[i] = value.applyAsInt(i);
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
