package Vystorm.vystorm_core.survey.internal;

/**
 * The cells a survey of one chunk looks at: the chunk itself plus {@code margin} blocks of its eight neighbours on
 * every side, one class byte per cell. Width {@code W = 16 + 2 * margin}; index {@code (y * W + z) * W + x} with y
 * relative to the bottom of the world; the chunk itself starts at x = z = margin.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SurveyGrid {
    final int margin;
    final int width;
    final int height;
    final int minY;
    final byte[] cells;

    public SurveyGrid(int margin, int minY, int height, byte[] cells) {
        if (margin < 0 || margin > 16) throw new IllegalArgumentException("margin " + margin);
        if (height <= 0 || (height & 15) != 0) throw new IllegalArgumentException("height " + height);
        this.margin = margin;
        this.width = 16 + 2 * margin;
        this.height = height;
        this.minY = minY;
        if (cells.length != width * width * height) throw new IllegalArgumentException("cells " + cells.length);
        this.cells = cells;
    }

    /**
     * Assembles the grid from the classes of the chunk and its neighbours: {@code chunks[(dz + 1) * 3 + (dx + 1)]} is
     * the chunk at offset dx, dz (centre at index 4). All nine must be present and of the same height.
     */
    public static SurveyGrid assemble(int margin, ChunkClasses[] chunks) {
        if (chunks.length != 9) throw new IllegalArgumentException("nine chunks");
        ChunkClasses centre = chunks[4];
        int h = centre.height(), w = 16 + 2 * margin;
        byte[] cells = new byte[w * w * h];
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                ChunkClasses c = chunks[(dz + 1) * 3 + dx + 1];
                if (c == null || c.height() != h || c.minY() != centre.minY())
                    throw new IllegalArgumentException("chunk " + dx + "," + dz + " missing or of another height");
                // local x range of this chunk inside the grid
                int fromX = dx < 0 ? 16 - margin : 0, toX = dx > 0 ? margin : 16;
                int fromZ = dz < 0 ? 16 - margin : 0, toZ = dz > 0 ? margin : 16;
                if (fromX >= toX || fromZ >= toZ) continue;
                int gx = dx < 0 ? 0 : dx == 0 ? margin : margin + 16;
                int gz = dz < 0 ? 0 : dz == 0 ? margin : margin + 16;
                for (int y = 0; y < h; y++)
                    for (int z = fromZ; z < toZ; z++)
                        c.row(y, z, fromX, toX, cells, (y * w + gz + z - fromZ) * w + gx);
            }
        return new SurveyGrid(margin, centre.minY(), h, cells);
    }

    /** The same chunk with a smaller margin. */
    public SurveyGrid crop(int newMargin) {
        if (newMargin > margin || newMargin < 0) throw new IllegalArgumentException("margin " + newMargin);
        int w = 16 + 2 * newMargin, off = margin - newMargin;
        byte[] out = new byte[w * w * height];
        for (int y = 0; y < height; y++)
            for (int z = 0; z < w; z++)
                System.arraycopy(cells, (y * width + z + off) * width + off, out, (y * w + z) * w, w);
        return new SurveyGrid(newMargin, minY, height, out);
    }

    /** Class of a grid cell. */
    public byte at(int gx, int yIndex, int gz) { return cells[(yIndex * width + gz) * width + gx]; }

    /** The classes of the chunk itself. */
    public ChunkClasses centre() {
        byte[] out = new byte[256 * height];
        for (int y = 0; y < height; y++)
            for (int z = 0; z < 16; z++)
                System.arraycopy(cells, (y * width + z + margin) * width + margin, out, (y * 16 + z) * 16, 16);
        return ChunkClasses.of(minY, height, out);
    }

    public int margin() { return margin; }

    public int height() { return height; }

    public int minY() { return minY; }
}
