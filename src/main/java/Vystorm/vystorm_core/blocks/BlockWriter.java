package Vystorm.vystorm_core.blocks;

import org.bukkit.World;

/**
 * Writes the cells of one chunk without per-block events and without physics, checking each cell first.
 * {@code api.blocks().writer(world)} hands out the writer for a world, {@code api.blocks().bukkitWriter()} always the
 * Bukkit one. The caller fires one {@link BlocksEditedEvent} per chunk afterwards ({@code api.blocks().announce}).
 *
 * @since 0.69.0
 */
public interface BlockWriter {
    /** {@code "bukkit"} for the Bukkit writer. */
    String id();

    /** True for a writer that changes chunk sections directly; false for the Bukkit writer. */
    boolean direct();

    /**
     * Server thread only. Writes cells {@code [from, cells.size())} of one chunk until {@code System.nanoTime()}
     * reaches {@code deadlineNanos}; the time is checked before each cell, so a deadline that already passed writes
     * nothing. A cell is written only when the world still holds {@code cells.before(i)}; otherwise
     * {@code sink.mismatch(i, currentId)} is called and the cell is skipped (the caller re-evaluates it and calls
     * write again for it). The chunk must be loaded (hold it with {@code api.blocks().chunks().load}).
     *
     * @return the index of the first cell not handled ({@code cells.size()} when all were handled)
     */
    int write(World world, ChunkCells cells, int from, long deadlineNanos, WriteSink sink);
}
