package Vystorm.vystorm_core.packet.internal.wire;

import java.util.Random;

/** Hand-built packet bodies in the 26.x layout for the wire tests. Ids: 0 air, 1 stone, 2 deepslate, 3 water, 4.. other. */
public final class WireFixtures {
    public static final int AIR = 0, STONE = 1, DEEPSLATE = 2, WATER = 3, ORE = 7, CHEST = 9;
    static final int STATES = 1000;

    public static WireTables tables() {
        boolean[] air = new boolean[STATES], fluid = new boolean[STATES];
        air[AIR] = true;
        fluid[WATER] = true;
        fluid[42] = true; // a waterlogged block: not air, holds a fluid
        return new WireTables(air, fluid);
    }

    /** Section ids: layer pattern with stone, air, an ore, water; section 1 has many different ids (direct). */
    public static int[][] world(int sections, long seed) {
        Random r = new Random(seed);
        int[][] ids = new int[sections][4096];
        for (int s = 0; s < sections; s++)
            for (int k = 0; k < 4096; k++) {
                int y = k >> 8;
                ids[s][k] = s == 0 ? STONE : s == 1 ? 4 + r.nextInt(500) : s == 2 ? (y < 8 ? STONE : AIR) : s == 3 ? (r.nextInt(5) == 0 ? WATER : r.nextInt(7) == 0 ? ORE : DEEPSLATE) : AIR;
            }
        return ids;
    }

    /** One NBT compound with nested tags of every type. */
    static void nbt(Bytes.Writer w) {
        w.u8(10);
        tag(w, 1, "b"); w.u8(5);
        tag(w, 2, "s"); w.s16(300);
        tag(w, 3, "i"); w.s32(7);
        tag(w, 4, "l"); w.s64(9);
        tag(w, 5, "f"); w.s32(0);
        tag(w, 6, "d"); w.s64(0);
        tag(w, 7, "ba"); w.s32(3); w.u8(1); w.u8(2); w.u8(3);
        tag(w, 8, "str"); utf(w, "minecraft:chest");
        tag(w, 9, "list"); w.u8(10); w.s32(2);
        for (int i = 0; i < 2; i++) { tag(w, 8, "id"); utf(w, "minecraft:diamond"); tag(w, 1, "Count"); w.u8(64); w.u8(0); }
        tag(w, 11, "ia"); w.s32(2); w.s32(1); w.s32(2);
        tag(w, 12, "la"); w.s32(1); w.s64(5);
        tag(w, 10, "inner"); tag(w, 9, "empty"); w.u8(0); w.s32(0); w.u8(0);
        w.u8(0);
    }

    static void tag(Bytes.Writer w, int type, String name) { w.u8(type); utf(w, name); }

    static void utf(Bytes.Writer w, String s) { byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8); w.s16(b.length); w.bytes(b, 0, b.length); }

    /** Light arrays for light sections 0..sections+1 (all set), each nibble = (k + section) % 16. */
    static void light(Bytes.Writer w, int sections) { light(w, sections, false); }

    /** bytes = the 26.3 form of the bitsets (BitSet.toByteArray with a varint length), else longs (26.2). */
    static void light(Bytes.Writer w, int sections, boolean bytes) {
        int n = sections + 2;
        long bits = (1L << n) - 1;
        for (int i = 0; i < 2; i++) {
            if (bytes) { int nb = (n + 7) / 8; w.varint(nb); for (int k = 0; k < nb; k++) w.u8((int) (bits >>> (8 * k))); }
            else { w.varint(1); w.s64(bits); }
        }
        w.varint(0);
        w.varint(0);
        for (int layer = 0; layer < 2; layer++) {
            w.varint(n);
            for (int s = 0; s < n; s++) {
                w.varint(2048);
                for (int i = 0; i < 2048; i++) w.u8(((2 * i + s) & 15) | (((2 * i + 1 + s) & 15) << 4));
            }
        }
    }

    /** A chunk packet with a packet id prefix of two bytes (id 0x80 0x01 = varint 128). */
    public static byte[] chunk(int cx, int cz, int[][] ids, WireTables t, int minY) { return chunk(cx, cz, ids, t, minY, false); }

    public static byte[] chunk(int cx, int cz, int[][] ids, WireTables t, int minY, boolean bitsetBytes) {
        Bytes.Writer w = new Bytes.Writer(70000);
        w.varint(128);
        w.s32(cx);
        w.s32(cz);
        w.varint(2);
        int height = ids.length * 16, bits = 32 - Integer.numberOfLeadingZeros(height), vpl = 64 / bits, longs = (256 + vpl - 1) / vpl;
        for (int type = 0; type < 2; type++) {
            w.varint(type == 0 ? 1 : 4);
            w.varint(longs);
            for (int li = 0; li < longs; li++) {
                long word = 0;
                for (int j = 0; j < vpl && li * vpl + j < 256; j++) word |= (long) (40 + type * 3) << (j * bits);
                w.s64(word);
            }
        }
        byte[] sec = SectionCodec.encode(ids, t);
        w.varint(sec.length);
        w.bytes(sec, 0, sec.length);
        w.varint(3);
        int[][] bes = {{3, 2 + minY + 48, 4}, {5, 5 + minY, 5}, {15, minY + 63, 15}};
        for (int[] be : bes) {
            w.u8((be[0] & 15) << 4 | (be[2] & 15));
            w.s16(be[1]);
            w.varint(be == bes[2] ? 7 : 1);
            if (be == bes[1]) w.u8(0); else nbt(w);
        }
        light(w, ids.length, bitsetBytes);
        return w.done();
    }
}
