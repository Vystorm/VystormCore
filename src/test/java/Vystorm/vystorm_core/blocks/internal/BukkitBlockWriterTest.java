package Vystorm.vystorm_core.blocks.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.blocks.ChunkCells;
import Vystorm.vystorm_core.blocks.StateTable;
import Vystorm.vystorm_core.blocks.TestBlocks;
import Vystorm.vystorm_core.blocks.TestWorld;
import Vystorm.vystorm_core.blocks.WriteSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class BukkitBlockWriterTest {
    private final StateTable states = new SharedStateTable(TestBlocks::data, TestBlocks::solid);
    private final int stone = states.id(TestBlocks.data(Material.STONE));
    private final int dirt = states.id(TestBlocks.data(Material.DIRT));
    private final int glass = states.id(TestBlocks.data(Material.GLASS));

    private static final class Recorder implements WriteSink {
        final List<String> calls = new ArrayList<>();
        @Override public void written(int i) { calls.add("written(" + i + ")"); }
        @Override public void mismatch(int i, int currentId) { calls.add("mismatch(" + i + "," + currentId + ")"); }
    }

    /** Cells (0..2, 64, 0) in chunk 0/0: air to stone each. */
    private ChunkCells threeCells() {
        return ChunkCells.of(0, 0, new int[]{0, 1, 2}, new int[]{64, 64, 64}, new int[]{0, 0, 0},
                new int[]{StateTable.AIR, StateTable.AIR, StateTable.AIR}, new int[]{stone, stone, stone});
    }

    @Test
    void givenCellOneChangedSinceTheSnapshot_whenWritten_thenItIsSkippedAndReportedWithItsCurrentState() {
        TestWorld world = new TestWorld(states);
        world.set(1, 64, 0, dirt);
        Recorder sink = new Recorder();
        int next = new BukkitBlockWriter(states, System::nanoTime).write(world.world(), threeCells(), 0, Long.MAX_VALUE, sink);
        assertEquals(3, next);
        assertEquals(List.of("written(0)", "mismatch(1," + dirt + ")", "written(2)"), sink.calls);
        assertEquals(stone, world.get(0, 64, 0));
        assertEquals(dirt, world.get(1, 64, 0));
        assertEquals(stone, world.get(2, 64, 0));
        assertEquals(2, world.writes());
        assertTrue(world.writesWithoutPhysics(), "every write used setBlockData(data, false)");
    }

    @Test
    void givenDeadlineAlreadyPassed_whenWritten_thenNothingIsWrittenAndFromIsReturned() {
        TestWorld world = new TestWorld(states);
        Recorder sink = new Recorder();
        int next = new BukkitBlockWriter(states, () -> 100L).write(world.world(), threeCells(), 1, 100L, sink);
        assertEquals(1, next);
        assertTrue(sink.calls.isEmpty());
        assertEquals(0, world.writes());
        assertEquals(0, world.reads());
    }

    @Test
    void givenClockThatPassesTheDeadlineAfterTwoCells_whenWritten_thenTwoCellsAreHandled() {
        TestWorld world = new TestWorld(states);
        AtomicLong now = new AtomicLong();
        Recorder sink = new Recorder();
        // the clock advances by 10 on every read; the writer reads it once before each cell
        int next = new BukkitBlockWriter(states, () -> now.getAndAdd(10)).write(world.world(), threeCells(), 0, 20L, sink);
        assertEquals(2, next);
        assertEquals(List.of("written(0)", "written(1)"), sink.calls);
        assertEquals(StateTable.AIR, world.get(2, 64, 0));
        // the caller continues from the returned index in the next tick
        int rest = new BukkitBlockWriter(states, () -> 0L).write(world.world(), threeCells(), next, 1L, sink);
        assertEquals(3, rest);
        assertEquals(stone, world.get(2, 64, 0));
    }

    @Test
    void givenWriter_whenAsked_thenItIsTheBukkitWriterAndNotDirect() {
        BukkitBlockWriter writer = new BukkitBlockWriter(states, System::nanoTime);
        assertEquals("bukkit", writer.id());
        assertFalse(writer.direct());
    }

    @Test
    void givenMixedAfterStates_whenWritten_thenEachCellGetsItsOwnState() {
        TestWorld world = new TestWorld(states);
        ChunkCells cells = ChunkCells.of(1, -1, new int[]{16, 17}, new int[]{0, 0}, new int[]{-16, -15},
                new int[]{StateTable.AIR, StateTable.AIR}, new int[]{glass, dirt});
        Recorder sink = new Recorder();
        new BukkitBlockWriter(states, System::nanoTime).write(world.world(), cells, 0, Long.MAX_VALUE, sink);
        assertEquals(glass, world.get(16, 0, -16));
        assertEquals(dirt, world.get(17, 0, -15));
    }
}
