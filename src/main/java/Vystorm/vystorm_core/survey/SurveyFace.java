package Vystorm.vystorm_core.survey;

/**
 * The four side faces of a chunk. Border runs of a face are ordered by {@code position = (y - minY) * 16 + t}, where
 * {@code t} is the block's z (WEST, EAST) or x (NORTH, SOUTH) inside the chunk.
 *
 * @since 0.68.0
 */
public enum SurveyFace {
    /** x = 0 of the chunk; the neighbour is chunk x - 1. */
    WEST(-1, 0),
    /** x = 15 of the chunk; the neighbour is chunk x + 1. */
    EAST(1, 0),
    /** z = 0 of the chunk; the neighbour is chunk z - 1. */
    NORTH(0, -1),
    /** z = 15 of the chunk; the neighbour is chunk z + 1. */
    SOUTH(0, 1);

    private final int dx;
    private final int dz;

    SurveyFace(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** Chunk x offset of the neighbour behind this face. */
    public int dx() { return dx; }

    /** Chunk z offset of the neighbour behind this face. */
    public int dz() { return dz; }

    /** The neighbour's face that touches this one. */
    public SurveyFace opposite() {
        return switch (this) {
            case WEST -> EAST;
            case EAST -> WEST;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
        };
    }
}
