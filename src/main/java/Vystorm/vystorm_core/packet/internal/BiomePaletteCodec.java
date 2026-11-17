package Vystorm.vystorm_core.packet.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import java.util.function.IntBinaryOperator;

/** Rewrites biome IDs in the 26.2 paletted-container wire format. */
public final class BiomePaletteCodec {
    private static final int BIOME_ENTRY_COUNT = 64;
    private static final int BLOCK_ENTRY_COUNT = 4096;

    private BiomePaletteCodec() {}

    /** Maps (linear quart-cell index, original biome ID), X then Z then Y, bottom section first. */
    public static byte[] remapCells(byte[] input, int globalBits, IntBinaryOperator map) {
        Objects.requireNonNull(map);
        int[] cell = {0};
        return remapBiomes(input, globalBits, globalBits, id -> map.applyAsInt(cell[0]++, id));
    }

    /** Rewrites consecutive biome containers, as used by ClientboundChunksBiomesPacket. */
    public static byte[] remapBiomes(byte[] input, int sourceGlobalBits, int targetGlobalBits,
                                     IntUnaryOperator map) {
        validate(input, sourceGlobalBits, targetGlobalBits, map);
        Cursor cursor = new Cursor(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        int sections = 0;
        while (cursor.remaining() > 0) {
            writeBiomeContainer(cursor, output, sourceGlobalBits, targetGlobalBits, map);
            sections++;
        }
        if (sections == 0) throw malformed("no biome sections");
        return output.toByteArray();
    }

    /** Rewrites section biomes while copying counters and block-state containers byte-for-byte. */
    public static byte[] remapChunk(byte[] input, int sourceGlobalBits, int targetGlobalBits,
                                    IntUnaryOperator map) {
        validate(input, sourceGlobalBits, targetGlobalBits, map);
        Cursor cursor = new Cursor(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        int sections = 0;
        while (cursor.remaining() > 0) {
            cursor.copyTo(output, 4); // non-empty block/fluid counts
            copyPaletteContainer(cursor, output, BLOCK_ENTRY_COUNT, 8);
            writeBiomeContainer(cursor, output, sourceGlobalBits, targetGlobalBits, map);
            sections++;
        }
        if (sections == 0) throw malformed("no chunk sections");
        return output.toByteArray();
    }

    /** Extracts the original section biome containers from a full chunk payload. */
    public static byte[] extractBiomes(byte[] fullChunk, int globalBits) {
        validate(fullChunk, globalBits, globalBits, ignored -> ignored);
        Cursor cursor = new Cursor(fullChunk);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int sections = 0;
        while (cursor.remaining() > 0) {
            cursor.skip(4); // section counters
            skipPaletteContainer(cursor, BLOCK_ENTRY_COUNT, 8);
            int start = cursor.position();
            validateBiomeContainer(cursor, globalBits);
            cursor.copyRangeTo(output, start, cursor.position());
            sections++;
        }
        if (sections == 0) throw malformed("no chunk sections");
        return output.toByteArray();
    }

    private static void validate(byte[] input, int sourceBits, int targetBits, IntUnaryOperator map) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(map, "map");
        if (sourceBits < 4 || sourceBits > 31 || targetBits < sourceBits || targetBits > 31)
            throw new IllegalArgumentException("global biome bit widths must satisfy 4 <= source <= target <= 31");
    }

    private static void writeBiomeContainer(Cursor in, ByteArrayOutputStream out, int sourceBits,
                                            int targetBits, IntUnaryOperator map) {
        int bits = in.readUnsignedByte();
        int[] palette = readPalette(in, bits, 3, sourceBits, BIOME_ENTRY_COUNT);
        int storageBits = bits == 0 ? 0 : bits <= 3 ? bits : sourceBits;
        int[] packed = readValues(in, BIOME_ENTRY_COUNT, storageBits);
        int[] values = new int[BIOME_ENTRY_COUNT];
        for (int i = 0; i < values.length; i++) {
            int id;
            if (bits == 0) id = palette[0];
            else if (bits <= 3) {
                if (packed[i] >= palette.length) throw malformed("biome palette index out of range");
                id = palette[packed[i]];
            } else id = packed[i];
            int mapped = map.applyAsInt(id);
            if (mapped < 0 || (long)mapped >= (1L << targetBits)) throw malformed("mapped biome ID is outside target registry width");
            values[i] = mapped;
        }
        writeBiomeValues(out, values, targetBits);
    }

    private static void validateBiomeContainer(Cursor in, int globalBits) {
        int bits = in.readUnsignedByte();
        int[] palette = readPalette(in, bits, 3, globalBits, BIOME_ENTRY_COUNT);
        int[] packed = readValues(in, BIOME_ENTRY_COUNT, bits == 0 ? 0 : bits <= 3 ? bits : globalBits);
        if (bits > 0 && bits <= 3) {
            for (int index : packed) {
                if (index >= palette.length) throw malformed("biome palette index out of range");
            }
        }
    }

    private static void writeBiomeValues(ByteArrayOutputStream out, int[] values, int globalBits) {
        Map<Integer, Integer> paletteIds = new LinkedHashMap<>();
        for (int id : values) paletteIds.computeIfAbsent(id, ignored -> paletteIds.size());
        if (paletteIds.size() == 1) {
            out.write(0);
            writeVarInt(out, paletteIds.keySet().iterator().next());
            return;
        }
        if (paletteIds.size() <= 8) {
            int bits = ceilLog2(paletteIds.size());
            out.write(bits);
            writeVarInt(out, paletteIds.size());
            for (int id : paletteIds.keySet()) writeVarInt(out, id);
            int[] paletteValues = new int[values.length];
            for (int i = 0; i < values.length; i++) paletteValues[i] = paletteIds.get(values[i]);
            writePacked(out, paletteValues, bits);
        } else {
            out.write(globalBits);
            writePacked(out, values, globalBits);
        }
    }

    private static void copyPaletteContainer(Cursor in, ByteArrayOutputStream out, int count, int localMaxBits) {
        int start = in.position();
        skipPaletteContainer(in, count, localMaxBits);
        in.copyRangeTo(out, start, in.position());
    }

    private static void skipPaletteContainer(Cursor in, int count, int localMaxBits) {
        int bits = in.readUnsignedByte();
        if (bits > 32) throw malformed("block palette width exceeds storage limits");
        int storageBits = bits;
        if (bits == 0) {
            in.readVarInt(); // singleton value
            storageBits = 0;
        } else if (bits <= localMaxBits) {
            int paletteSize = in.readVarInt();
            if (paletteSize < 1 || paletteSize > (1 << bits)) throw malformed("invalid block palette size");
            for (int i = 0; i < paletteSize; i++) in.readVarInt();
        }
        in.skip(storageLongCount(count, storageBits) * Long.BYTES);
    }

    private static int[] readPalette(Cursor in, int bits, int localMaxBits, int globalBits, int count) {
        if (bits == 0) return new int[]{in.readVarInt()};
        if (bits >= globalBits) {
            if (bits != globalBits) throw malformed("direct biome width does not match source registry width");
            return null;
        }
        if (bits > localMaxBits) throw malformed("invalid biome palette width");
        int size = in.readVarInt();
        if (size < 1 || size > (1 << bits) || size > count) throw malformed("invalid biome palette size");
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) palette[i] = in.readVarInt();
        return palette;
    }

    private static int[] readValues(Cursor in, int count, int bits) {
        int[] values = new int[count];
        if (bits == 0) return values;
        int perLong = 64 / bits;
        int longCount = storageLongCount(count, bits);
        long[] words = new long[longCount];
        for (int i = 0; i < longCount; i++) words[i] = in.readLong();
        long mask = (1L << bits) - 1;
        for (int i = 0; i < count; i++) values[i] = (int) ((words[i / perLong] >>> ((i % perLong) * bits)) & mask);
        return values;
    }

    private static void writePacked(ByteArrayOutputStream out, int[] values, int bits) {
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        int longs = storageLongCount(values.length, bits);
        for (int word = 0; word < longs; word++) {
            long packed = 0;
            for (int slot = 0; slot < perLong; slot++) {
                int index = word * perLong + slot;
                if (index >= values.length) break;
                long value = values[index];
                if (value < 0 || (value & ~mask) != 0) throw malformed("biome ID does not fit target global width");
                packed |= value << (slot * bits);
            }
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (packed >>> shift) & 0xff);
        }
    }

    private static int storageLongCount(int count, int bits) {
        return bits == 0 ? 0 : (count + (64 / bits) - 1) / (64 / bits);
    }

    private static int ceilLog2(int value) { return 32 - Integer.numberOfLeadingZeros(value - 1); }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        if (value < 0) throw malformed("negative VarInt");
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static IllegalArgumentException malformed(String message) { return new IllegalArgumentException("Malformed biome palette data: " + message); }

    private static final class Cursor {
        private final byte[] bytes;
        private int position;
        private Cursor(byte[] bytes) { this.bytes = bytes; }
        int remaining() { return bytes.length - position; }
        int position() { return position; }
        int readUnsignedByte() { require(1); return bytes[position++] & 0xff; }
        int readVarInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int next = readUnsignedByte();
                if (shift == 28 && (next & 0xf8) != 0) throw malformed("VarInt overflow");
                value |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    if (value < 0) throw malformed("negative VarInt");
                    return value;
                }
            }
            throw malformed("VarInt exceeds five bytes");
        }
        long readLong() {
            require(Long.BYTES);
            long value = 0;
            for (int i = 0; i < Long.BYTES; i++) value = (value << 8) | readUnsignedByte();
            return value;
        }
        void copyTo(ByteArrayOutputStream out, int length) {
            require(length);
            out.write(bytes, position, length);
            position += length;
        }
        void copyRangeTo(ByteArrayOutputStream out, int start, int end) {
            if (start < 0 || end < start || end > bytes.length) throw malformed("invalid captured range");
            out.write(bytes, start, end - start);
        }
        void skip(int length) { require(length); position += length; }
        private void require(int length) {
            if (length < 0 || remaining() < length) throw malformed("truncated payload");
        }
    }
}
