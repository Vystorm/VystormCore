package Vystorm.vystorm_core.packet.view;

/**
 * Chunk keys of the packet view ({@code heldChunks}, {@code resend}): x in the low 32 bits, z in the high 32 bits.
 *
 * @since 0.73.0
 */
public final class ViewChunks {
    private ViewChunks() {}

    public static long key(int chunkX, int chunkZ) { return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32); }

    public static int x(long key) { return (int) key; }

    public static int z(long key) { return (int) (key >> 32); }
}
