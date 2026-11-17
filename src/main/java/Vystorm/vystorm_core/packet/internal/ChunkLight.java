package Vystorm.vystorm_core.packet.internal;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Sky light of a client-only chunk, computed from its blocks alone (the server has no light for a chunk it never
 * loaded). Per column: full sky light (15) above the highest block, minus the opacity of every block below
 * (transparent blocks like water and leaves 1, solid blocks end the column). Sections above the highest block get
 * no data, so the client treats them as open sky; sections below the lowest lit block are sent as empty (dark).
 * Pure and thread safe.
 */
public final class ChunkLight {
    private ChunkLight() {}

    /** sky light layers: {@code mask} bits per light section (section index + 1), {@code empty} bits, data in mask order */
    public record Sky(BitSet mask, BitSet empty, List<byte[]> data) {}

    /**
     * @param blocks   palette index per block, index {@code ((y - minY) * 16 + z) * 16 + x}, 0 = air
     * @param opacity  light opacity per palette index (0 air, 1 transparent, 15 opaque)
     * @param sections chunk sections (height / 16)
     */
    public static Sky sky(short[] blocks, int[] opacity, int sections) {
        int height = sections * 16;
        byte[] level = new byte[height * 256];
        int lowestLit = height, highestBlock = -1;
        for (int c = 0; c < 256; c++) {
            int top = -1;
            for (int y = height - 1; y >= 0; y--) if (blocks[y * 256 + c] != 0) { top = y; break; }
            highestBlock = Math.max(highestBlock, top);
            int light = 15;
            for (int y = height - 1; y >= 0; y--) {
                if (y <= top) {
                    int o = opacity[blocks[y * 256 + c]];
                    light = o >= 15 ? 0 : Math.max(0, light - o);
                }
                if (light == 0) break;
                level[y * 256 + c] = (byte) light;
                lowestLit = Math.min(lowestLit, y);
            }
        }
        BitSet mask = new BitSet(), empty = new BitSet();
        List<byte[]> data = new ArrayList<>();
        if (highestBlock < 0) return new Sky(mask, empty, data);
        // light sections: index 0 is the section below the world, index s + 1 is chunk section s
        int topSection = highestBlock < 0 ? -1 : highestBlock >> 4;
        int lowSection = lowestLit >= height ? 0 : lowestLit >> 4;
        for (int s = 0; s < lowSection; s++) empty.set(s + 1);
        empty.set(0);
        for (int s = lowSection; s <= Math.min(sections - 1, topSection + 1); s++) {
            byte[] nibbles = new byte[2048];
            boolean any = false;
            for (int i = 0; i < 4096; i++) {
                int v = level[s * 4096 + i] & 15;
                if (v == 0) continue;
                any = true;
                nibbles[i >> 1] |= (byte) ((i & 1) == 0 ? v : v << 4);
            }
            if (!any) { empty.set(s + 1); continue; }
            mask.set(s + 1);
            data.add(nibbles);
        }
        return new Sky(mask, empty, data);
    }

    /** light level at a section-local block of a nibble array (tests) */
    public static int at(byte[] nibbles, int x, int y, int z) {
        int i = (y << 8) | (z << 4) | x;
        int b = nibbles[i >> 1];
        return (i & 1) == 0 ? b & 15 : (b >> 4) & 15;
    }
}
