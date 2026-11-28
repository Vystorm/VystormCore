package Vystorm.vystorm_core.packet.internal.blocks;

/**
 * Packing of cells, sections and chunks into longs, the way the game packs them: a cell like {@code BlockPos.asLong}
 * (x 26 bits, z 26 bits, y 12 bits), a section like {@code SectionPos.asLong} (x 22, z 22, y 20 bits), a chunk like
 * {@code Chunk.getChunkKey} (x in the low, z in the high 32 bits). The local index of a cell in its section is
 * {@code y << 8 | z << 4 | x} (0..4095), as in a section-blocks-update packet.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CellKeys {
    private CellKeys() {}

    public static long cell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    public static int cellX(long cell) { return (int) (cell >> 38); }

    public static int cellY(long cell) { return (int) (cell << 52 >> 52); }

    public static int cellZ(long cell) { return (int) (cell << 26 >> 38); }

    public static long section(int sectionX, int sectionY, int sectionZ) {
        return ((long) (sectionX & 0x3FFFFF) << 42) | ((long) (sectionZ & 0x3FFFFF) << 20) | (sectionY & 0xFFFFFL);
    }

    /** Section of a block position. */
    public static long sectionOf(int x, int y, int z) { return section(x >> 4, y >> 4, z >> 4); }

    public static int sectionX(long section) { return (int) (section >> 42); }

    public static int sectionY(long section) { return (int) (section << 44 >> 44); }

    public static int sectionZ(long section) { return (int) (section << 22 >> 42); }

    /** Index of a cell inside its section: {@code y << 8 | z << 4 | x}, each 0..15. */
    public static int local(int x, int y, int z) { return (y & 15) << 8 | (z & 15) << 4 | (x & 15); }

    /** Block x of a local index in a section. */
    public static int blockX(long section, int local) { return sectionX(section) << 4 | (local & 15); }

    public static int blockY(long section, int local) { return sectionY(section) << 4 | (local >> 8 & 15); }

    public static int blockZ(long section, int local) { return sectionZ(section) << 4 | (local >> 4 & 15); }

    public static long chunk(int chunkX, int chunkZ) { return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32); }
}
