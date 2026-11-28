package Vystorm.vystorm_core.blocks;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * The cells of one chunk that a bulk edit wrote, carried by {@link BlocksEditedEvent}. Ids are ids of the shared
 * {@link StateTable} ({@code api.blocks().states()}); loops should use them, {@link #before(int)} and
 * {@link #after(int)} make a copy on every call. Implementations do not change after the event was fired.
 *
 * @since 0.69.0
 */
public interface EditedChunk {
    /** Id of the edit; the same for every chunk of one edit, and the undo of an edit has its own id. */
    UUID editId();

    /** Player who started the edit, or null for console and plugin edits. */
    UUID actor();

    EditCause cause();

    World world();
    int chunkX();
    int chunkZ();

    /** Number of written cells. */
    int size();
    int x(int i);
    int y(int i);
    int z(int i);

    /** State of cell {@code i} before the write, a new copy on every call. */
    BlockData before(int i);

    /** State of cell {@code i} after the write, a new copy on every call. */
    BlockData after(int i);

    int beforeId(int i);
    int afterId(int i);

    /** True when at least one written cell has {@code minY <= y <= maxY}. */
    boolean touches(int minY, int maxY);

    /** A builder for the cells of one chunk; add each cell when the writer reports it written. */
    static Builder builder(UUID editId, UUID actor, EditCause cause, World world, int chunkX, int chunkZ) {
        return new Builder(editId, actor, cause, world, chunkX, chunkZ);
    }

    /**
     * Collects written cells of one chunk. Not thread-safe; {@link #build()} copies the cells, so a builder can be
     * reused after {@link #clear()}.
     *
     * @since 0.69.0
     */
    final class Builder {
        private final UUID editId, actor;
        private final EditCause cause;
        private final World world;
        private final int chunkX, chunkZ;
        private int[] x = new int[64], y = new int[64], z = new int[64], before = new int[64], after = new int[64];
        private int size;
        private StateTable states;

        private Builder(UUID editId, UUID actor, EditCause cause, World world, int chunkX, int chunkZ) {
            this.editId = Objects.requireNonNull(editId, "editId");
            this.actor = actor;
            this.cause = Objects.requireNonNull(cause, "cause");
            this.world = Objects.requireNonNull(world, "world");
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        /** The table the ids belong to; {@code api.blocks().states()} when not set. */
        public Builder states(StateTable table) { this.states = Objects.requireNonNull(table, "table"); return this; }

        public Builder add(int cellX, int cellY, int cellZ, int beforeId, int afterId) {
            if ((cellX >> 4) != chunkX || (cellZ >> 4) != chunkZ)
                throw new IllegalArgumentException("Cell " + cellX + ", " + cellZ + " is not in chunk " + chunkX + ", " + chunkZ);
            if (size == x.length) {
                int grown = size * 2;
                x = Arrays.copyOf(x, grown); y = Arrays.copyOf(y, grown); z = Arrays.copyOf(z, grown);
                before = Arrays.copyOf(before, grown); after = Arrays.copyOf(after, grown);
            }
            x[size] = cellX; y[size] = cellY; z[size] = cellZ; before[size] = beforeId; after[size] = afterId;
            size++;
            return this;
        }

        /** Adds cell {@code i} of a write; call it from {@link WriteSink#written(int)}. */
        public Builder add(ChunkCells cells, int i) { return add(cells.x(i), cells.y(i), cells.z(i), cells.before(i), cells.after(i)); }

        public int size() { return size; }

        public Builder clear() { size = 0; return this; }

        public EditedChunk build() {
            StateTable table = states == null ? BlockWrites.states() : states;
            return new Vystorm.vystorm_core.blocks.internal.EditedCells(editId, actor, cause, world, chunkX, chunkZ, Arrays.copyOf(x, size), Arrays.copyOf(y, size),
                    Arrays.copyOf(z, size), Arrays.copyOf(before, size), Arrays.copyOf(after, size), table);
        }
    }

}
