package Vystorm.vystorm_core.blocks.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.blocks.StateTable;
import Vystorm.vystorm_core.blocks.TestBlocks;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

class SharedStateTableTest {
    private static SharedStateTable table() { return new SharedStateTable(text -> TestBlocks.data(text), TestBlocks::solid); }

    @Test
    void givenSolidRule_whenTheSameStateIsAskedOften_thenTheRuleRanOnceForIt() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SharedStateTable states = new SharedStateTable(TestBlocks::data, m -> { calls.incrementAndGet(); return TestBlocks.solid(m); });
        for (int i = 0; i < 50; i++) assertTrue(states.solid(states.id(TestBlocks.data(Material.STONE))));
        assertEquals(1, calls.get());
    }

    @Test
    void givenNewTable_whenAirAsked_thenIdZeroAndAir() {
        SharedStateTable states = table();
        assertEquals(StateTable.AIR, states.id(TestBlocks.data(Material.AIR)));
        assertEquals(0, states.id("minecraft:air"));
        assertTrue(states.air(0));
        assertEquals(Material.AIR, states.material(0));
        assertEquals("minecraft:air", states.text(0));
        assertEquals(1, states.size());
    }

    @Test
    void givenTwoBlockDataWithTheSameText_whenAdded_thenTheSameIdTwice() {
        SharedStateTable states = table();
        BlockData first = TestBlocks.data("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", Material.OAK_STAIRS);
        BlockData second = TestBlocks.data("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", Material.OAK_STAIRS);
        assertNotSame(first, second);
        int id = states.id(first);
        assertEquals(id, states.id(second));
        assertEquals(id, states.id(first));
        assertEquals(2, states.size());
    }

    @Test
    void givenSixteenThreads_whenEachAddsAThousandStates_thenSixteenThousandDistinctIdsThatRoundTrip() throws Exception {
        SharedStateTable states = table();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        Map ids = new Map();
        List<Future<?>> done = new ArrayList<>();
        try {
            for (int t = 0; t < 16; t++) {
                int thread = t;
                done.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 1000; i++) {
                        String text = "minecraft:note_block[instrument=t" + thread + ",note=" + i + "]";
                        ids.put(text, states.id(TestBlocks.data(text, Material.NOTE_BLOCK)));
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) f.get();
        } finally { pool.shutdownNow(); }
        assertEquals(16000, ids.values.size());
        Set<Integer> distinct = new HashSet<>(ids.values.values());
        assertEquals(16000, distinct.size());
        assertFalse(distinct.contains(0));
        ids.values.forEach((text, id) -> assertEquals(text, states.text(id)));
        assertEquals(16001, states.size());
    }

    private static final class Map { final ConcurrentHashMap<String, Integer> values = new ConcurrentHashMap<>(); void put(String k, int v) { values.put(k, v); } }

    @Test
    void givenStoneAirAndCaveAir_whenFlagsAsked_thenSolidAndAirAreCached() {
        SharedStateTable states = table();
        int stone = states.id(TestBlocks.data(Material.STONE));
        assertTrue(states.solid(stone));
        assertFalse(states.air(stone));
        assertFalse(states.solid(states.id(TestBlocks.data(Material.AIR))));
        assertTrue(states.air(states.id(TestBlocks.data(Material.CAVE_AIR))));
        assertTrue(states.air(states.id(TestBlocks.data(Material.VOID_AIR))));
        assertEquals(Material.STONE, states.material(stone));
    }

    @Test
    void givenBedAndChest_whenFlagsAsked_thenPoiAndBlockEntityFromTheMaterial() {
        SharedStateTable states = table();
        assertTrue(states.poi(states.id(TestBlocks.data(Material.RED_BED))));
        assertTrue(states.poi(states.id(TestBlocks.data(Material.LECTERN))));
        assertFalse(states.poi(states.id(TestBlocks.data(Material.STONE))));
        assertTrue(states.hasBlockEntity(states.id(TestBlocks.data(Material.CHEST))));
        assertFalse(states.hasBlockEntity(states.id(TestBlocks.data(Material.DIRT))));
    }

    @Test
    void givenAddedState_whenDataAsked_thenTheCachedInstanceAndNotTheCallersObject() {
        SharedStateTable states = table();
        BlockData mine = TestBlocks.data(Material.DIRT);
        int id = states.id(mine);
        BlockData cached = states.data(id);
        assertSame(cached, states.data(id));
        assertNotSame(mine, cached);
        assertEquals("minecraft:dirt", cached.getAsString());
    }

    @Test
    void givenText_whenIdByString_thenParsedOnceAndBadTextRejected() {
        SharedStateTable states = new SharedStateTable(text -> {
            if (text.contains("nonsense")) throw new IllegalArgumentException("Could not parse data: " + text);
            return TestBlocks.data(text, Material.STONE);
        }, TestBlocks::solid); // Material.isSolid needs the server registry: a failed registry init breaks later tests
        int id = states.id("minecraft:stone");
        assertEquals(id, states.id("minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> states.id("minecraft:nonsense"));
        assertThrows(IllegalArgumentException.class, () -> states.text(99));
        assertThrows(IllegalArgumentException.class, () -> states.data(-1));
    }

    @Test
    void givenAirNeverSeen_whenDataOfIdZeroAsked_thenParsedLazily() {
        SharedStateTable states = table();
        BlockData air = states.data(StateTable.AIR);
        assertEquals("minecraft:air", air.getAsString());
        assertSame(air, states.data(0));
    }
}
