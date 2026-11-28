package Vystorm.vystorm_core.survey.internal;

/**
 * Order of a pre-compute job: the centre chunk first, then square rings of chunks outwards; inside a ring clockwise
 * from the north-west corner. The order is fixed, so a saved index resumes exactly.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public record RingPlan(int centerChunkX, int centerChunkZ, int chunkRadius) {
    public RingPlan {
        if (chunkRadius < 0 || chunkRadius > 100_000) throw new IllegalArgumentException("radius " + chunkRadius);
    }

    /** The plan of a block centre and a block radius. */
    public static RingPlan ofBlocks(int centerX, int centerZ, int radius) {
        return new RingPlan(centerX >> 4, centerZ >> 4, Math.max(0, (radius + 15) >> 4));
    }

    public long count() { return (2L * chunkRadius + 1) * (2L * chunkRadius + 1); }

    /** Ring of an index (0 = the centre). */
    public static int ringOf(long index) {
        if (index == 0) return 0;
        int r = (int) Math.ceil((Math.sqrt((double) index + 1) - 1) / 2);
        while ((2L * r + 1) * (2L * r + 1) <= index) r++;
        while (r > 0 && (2L * r - 1) * (2L * r - 1) > index) r--;
        return r;
    }

    /** Whether a chunk is part of the plan. */
    public boolean contains(int chunkX, int chunkZ) {
        return Math.abs((long) chunkX - centerChunkX) <= chunkRadius && Math.abs((long) chunkZ - centerChunkZ) <= chunkRadius;
    }

    /** Index of a chunk of the plan (the inverse of {@link #chunkAt}); -1 outside. */
    public long indexOf(int chunkX, int chunkZ) {
        if (!contains(chunkX, chunkZ)) return -1;
        int dx = chunkX - centerChunkX, dz = chunkZ - centerChunkZ, r = Math.max(Math.abs(dx), Math.abs(dz));
        if (r == 0) return 0;
        long base = (2L * r - 1) * (2L * r - 1);
        int side = 2 * r;
        if (dz == -r && dx < r) return base + (dx + r);
        if (dx == r && dz < r) return base + side + (dz + r);
        if (dz == r && dx > -r) return base + 2L * side + (r - dx);
        return base + 3L * side + (r - dz);
    }

    /** Chunk x and z of an index; null past the end. */
    public int[] chunkAt(long index) {
        if (index < 0 || index >= count()) return null;
        if (index == 0) return new int[] {centerChunkX, centerChunkZ};
        int r = ringOf(index);
        int i = (int) (index - (2L * r - 1) * (2L * r - 1));
        int side = 2 * r, s = i / side, o = i % side;
        return switch (s) {
            case 0 -> new int[] {centerChunkX - r + o, centerChunkZ - r};
            case 1 -> new int[] {centerChunkX + r, centerChunkZ - r + o};
            case 2 -> new int[] {centerChunkX + r - o, centerChunkZ + r};
            default -> new int[] {centerChunkX - r, centerChunkZ + r - o};
        };
    }
}
