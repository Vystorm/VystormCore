package Vystorm.vystorm_core.blocks.internal;

import Vystorm.vystorm_core.blocks.BlockWriter;
import Vystorm.vystorm_core.blocks.ChunkCells;
import Vystorm.vystorm_core.blocks.StateTable;
import Vystorm.vystorm_core.blocks.WriteSink;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * The Bukkit writer: {@code world.getBlockAt}, compare {@code getBlockData()} with the expected state,
 * {@code setBlockData(data, false)}.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class BukkitBlockWriter implements BlockWriter {
    private final StateTable states;
    private final LongSupplier clock;

    /** @param clock {@code System::nanoTime} on a server */
    public BukkitBlockWriter(StateTable states, LongSupplier clock) {
        this.states = Objects.requireNonNull(states, "states");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String id() { return "bukkit"; }
    @Override public boolean direct() { return false; }

    @Override
    public int write(World world, ChunkCells cells, int from, long deadlineNanos, WriteSink sink) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(sink, "sink");
        int size = cells.size();
        if (from < 0 || from > size) throw new IndexOutOfBoundsException("from " + from + " outside 0.." + size);
        for (int i = from; i < size; i++) {
            if (clock.getAsLong() >= deadlineNanos) return i;
            Block block = world.getBlockAt(cells.x(i), cells.y(i), cells.z(i));
            BlockData current = block.getBlockData();
            // the server's block data compares by state: one equals instead of a table lookup on the common path
            if (!current.equals(states.data(cells.before(i)))) {
                int currentId = states.id(current);
                if (currentId != cells.before(i)) {
                    sink.mismatch(i, currentId);
                    continue;
                }
            }
            block.setBlockData(states.data(cells.after(i)), false);
            sink.written(i);
        }
        return size;
    }
}
