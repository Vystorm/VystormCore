package Vystorm.vystorm_core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class BlockWritesTestingTest {
    private final World world = mock(World.class);
    private final Plugin plugin = mock(Plugin.class);

    BlockWritesTestingTest() {
        when(world.getName()).thenReturn("w");
        when(plugin.getName()).thenReturn("EditPlugin");
    }

    @Test
    void givenDetachedWriter_whenACellChangedSinceTheSnapshot_thenItWritesTheOthersAndReportsTheMismatch() {
        BlockWritesTesting.Detached detached = BlockWritesTesting.detached(TestBlocks::data);
        StateTable states = detached.states();
        int stone = states.id(TestBlocks.data(Material.STONE)), dirt = states.id(TestBlocks.data(Material.DIRT));
        detached.set(1, 64, 0, dirt);
        ChunkCells cells = ChunkCells.of(0, 0, new int[]{0, 1, 2}, new int[]{64, 64, 64}, new int[]{0, 0, 0},
                new int[]{StateTable.AIR, StateTable.AIR, StateTable.AIR}, new int[]{stone, stone, stone});
        List<String> calls = new ArrayList<>();
        int next = detached.writer().write(world, cells, 0, Long.MAX_VALUE, new WriteSink() {
            @Override public void written(int i) { calls.add("written(" + i + ")"); }
            @Override public void mismatch(int i, int currentId) { calls.add("mismatch(" + i + "," + currentId + ")"); }
        });
        assertEquals(3, next);
        assertEquals(List.of("written(0)", "mismatch(1," + dirt + ")", "written(2)"), calls);
        assertEquals(stone, detached.get(0, 64, 0));
        assertEquals(dirt, detached.get(1, 64, 0));
        assertEquals(stone, detached.get(2, 64, 0));
        assertEquals(2, detached.writes());
        assertEquals("bukkit", detached.writer().id());
        assertFalse(detached.writer().direct());
    }

    @Test
    void givenDetachedClock_whenTheDeadlinePasses_thenTheWriterStopsLikeTheBukkitWriter() {
        BlockWritesTesting.Detached detached = BlockWritesTesting.detached(TestBlocks::data);
        int stone = detached.states().id(TestBlocks.data(Material.STONE));
        AtomicLong now = new AtomicLong();
        detached.clock(() -> now.getAndAdd(10));
        ChunkCells cells = ChunkCells.of(0, 0, new int[]{0, 1, 2}, new int[]{0, 0, 0}, new int[]{0, 0, 0},
                new int[]{0, 0, 0}, new int[]{stone, stone, stone});
        int next = detached.writer().write(world, cells, 0, 20L, new WriteSink() {
            @Override public void written(int i) {}
            @Override public void mismatch(int i, int currentId) {}
        });
        assertEquals(2, next);
        assertEquals(StateTable.AIR, detached.get(2, 0, 0));
    }

    @Test
    void givenDetachedManager_whenUsed_thenItHandsOutTheDetachedPartsAndRecordsAnnouncements() {
        BlockWritesTesting.Detached detached = BlockWritesTesting.detached(TestBlocks::data);
        BlockWritesInterface blocks = detached.blocks();
        assertSame(detached.states(), blocks.states());
        assertSame(detached.writer(), blocks.writer(world));
        assertSame(detached.writer(), blocks.bukkitWriter());
        EditedChunk chunk = EditedChunk.builder(UUID.randomUUID(), null, EditCause.EDIT, world, 0, 0).states(detached.states())
                .add(1, 2, 3, StateTable.AIR, detached.states().id(TestBlocks.data(Material.GLASS))).build();
        BlocksEditedEvent event = blocks.announce(plugin, chunk);
        assertEquals(List.of(event), detached.announced());
        assertSame(chunk, event.chunk());

        ChunkHold hold = blocks.chunks().load(plugin, world, 4, 5, true).join();
        assertEquals(4, hold.chunkX());
        assertEquals(1, detached.openHolds());
        hold.close();
        hold.close();
        assertEquals(0, detached.openHolds());
        assertEquals(List.of("4,5"), detached.loaded());
        assertTrue(blocks.chunks().asynchronous());
    }

    @Test
    void givenDetachedWithoutParser_whenATextIsAsked_thenItSaysAParserIsNeeded() {
        BlockWritesTesting.Detached detached = BlockWritesTesting.detached();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> detached.states().id("minecraft:stone"));
        assertTrue(failure.getMessage().contains("detached(parser)"), failure.getMessage());
        assertTrue(detached.states().air(detached.states().id(TestBlocks.data(Material.CAVE_AIR))));
        assertTrue(detached.states().solid(detached.states().id(TestBlocks.data(Material.STONE))));
        assertFalse(detached.states().solid(detached.states().id(TestBlocks.data(Material.WATER))));
    }
}
