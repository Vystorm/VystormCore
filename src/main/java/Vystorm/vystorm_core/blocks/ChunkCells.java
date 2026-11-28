package Vystorm.vystorm_core.blocks;

import java.util.Objects;

/**
 * Flat view of the cells one write changes inside one chunk, in ascending cell order. Coordinates are world
 * coordinates; {@code before} and {@code after} are ids of {@link StateTable}. Implementations are read on the server
 * thread while {@link BlockWriter#write} runs and must not change during that call.
 *
 * @since 0.69.0
 */
public interface ChunkCells {
    int chunkX();
    int chunkZ();
    /** Number of cells. */
    int size();
    int x(int i);
    int y(int i);
    int z(int i);
    /** State the world must still hold before cell {@code i} is written. */
    int before(int i);
    /** State cell {@code i} gets. */
    int after(int i);

    /**
     * Cells backed by the given arrays (not copied; do not change them while a write runs). All arrays must have the
     * same length.
     */
    static ChunkCells of(int chunkX, int chunkZ, int[] x, int[] y, int[] z, int[] before, int[] after) {
        Objects.requireNonNull(x, "x"); Objects.requireNonNull(y, "y"); Objects.requireNonNull(z, "z");
        Objects.requireNonNull(before, "before"); Objects.requireNonNull(after, "after");
        int n = x.length;
        if (y.length != n || z.length != n || before.length != n || after.length != n)
            throw new IllegalArgumentException("all cell arrays need the same length");
        return new ChunkCells() {
            @Override public int chunkX() { return chunkX; }
            @Override public int chunkZ() { return chunkZ; }
            @Override public int size() { return n; }
            @Override public int x(int i) { return x[i]; }
            @Override public int y(int i) { return y[i]; }
            @Override public int z(int i) { return z[i]; }
            @Override public int before(int i) { return before[i]; }
            @Override public int after(int i) { return after[i]; }
        };
    }
}
