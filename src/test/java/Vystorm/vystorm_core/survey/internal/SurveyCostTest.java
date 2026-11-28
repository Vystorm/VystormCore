package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** The parts that decide what a survey costs on a server: classification, the class cache and the end of a job. */
class SurveyCostTest {
    private static final Material[] ALL = Material.values();

    /** A snapshot of the cell classes of a fixture chunk, as block types: one block type per class. */
    private static ChunkSnapshot snapshot(ChunkClasses classes, Material[] byClass) {
        ChunkSnapshot s = mock(ChunkSnapshot.class);
        int minY = classes.minY();
        for (int sec = 0; sec < classes.sectionCount(); sec++) {
            boolean empty = true;
            for (int k = 0; k < 4096 && empty; k++) empty = classes.at(k & 15, (sec << 4) | (k >>> 8), (k >>> 4) & 15) == CellClass.AIR;
            when(s.isSectionEmpty(sec)).thenReturn(empty);
        }
        when(s.getBlockType(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0), y = inv.getArgument(1), z = inv.getArgument(2);
            return byClass[classes.at(x, y - minY, z)];
        });
        return s;
    }

    @Test
    void givenRealChunks_whenClassifiedThroughTheTypeTable_thenEveryCellEqualsThePerCellPath() {
        // one block type per class, the table maps the type back to its class
        Material[] byClass = new Material[8];
        byte[] table = new byte[ALL.length];
        Material[] types = {Material.STONE, Material.AIR, Material.WATER, Material.GLASS, Material.OAK_DOOR};
        int[] classes = {CellClass.OPAQUE, CellClass.AIR, CellClass.FLUID, CellClass.SEE_THROUGH, CellClass.DOOR_CELL};
        for (int i = 0; i < types.length; i++) {
            byClass[classes[i]] = types[i];
            table[types[i].ordinal()] = (byte) classes[i];
        }
        BlockClasses blocks = new BlockClasses(table);
        for (SurveyFixtures.Fixture f : SurveyFixtures.all()) {
            ChunkClasses centre = f.chunks()[4];
            ChunkClasses back = blocks.classify(snapshot(centre, byClass), centre.minY(), centre.height());
            for (int yi = 0; yi < centre.height(); yi++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        assertEquals(blocks.of(byClass[centre.at(x, yi, z)]), back.at(x, yi, z), f.name() + " " + x + "," + yi + "," + z);
            assertEquals(centre.open().hash(), back.open().hash(), f.name());
        }
    }

    @Test
    void givenACache_whenFullOrOld_thenTheLeastRecentlyUsedAndTheExpiredGoAndUpdatesKeepTheAge() {
        AtomicLong now = new AtomicLong(1000);
        ClassCache cache = new ClassCache(now::get);
        List<ChunkClasses> chunks = new ArrayList<>();
        for (SurveyFixtures.Fixture f : SurveyFixtures.all()) chunks.add(f.chunks()[4]);
        cache.limits(3, Long.MAX_VALUE, 10_000);
        for (int i = 0; i < 4; i++) cache.put(i, chunks.get(i));
        assertNull(cache.get(0), "the oldest of four went with a limit of three");
        assertSame(chunks.get(1), cache.get(1));
        cache.put(4, chunks.get(4));
        assertNull(cache.get(2), "2 was used least recently");
        now.addAndGet(5_000);
        cache.update(1, chunks.get(5));
        now.addAndGet(6_000);
        assertNull(cache.get(1), "an update keeps the age: 11 s > 10 s");
        cache.update(99, chunks.get(0));
        assertFalse(cache.contains(99), "update never adds");
        // byte budget
        ClassCache small = new ClassCache(now::get);
        long one = chunks.get(0).footprint();
        small.limits(100, one * 2 + 1, 60_000);
        small.put(10, chunks.get(0));
        small.put(11, chunks.get(0));
        small.put(12, chunks.get(0));
        assertTrue(small.bytes() <= one * 2 + 1);
        assertEquals(2, small.size());
        assertTrue(cache.hits() > 0 && cache.misses() > 0);
    }

    @Test
    void givenStaleChunks_whenAJobEnds_thenOnlyItsOwnAreIsSurveyedAgainInRingOrderAtMostFiveTimes() {
        RingPlan plan = new RingPlan(0, 0, 4);
        for (long i = 0; i < plan.count(); i++) {
            int[] c = plan.chunkAt(i);
            assertEquals(i, plan.indexOf(c[0], c[1]));
        }
        assertEquals(-1, plan.indexOf(5, 0));
        Set<Long> stale = new HashSet<>(List.of(RoomJoin.key(3, 3), RoomJoin.key(0, 1), RoomJoin.key(9, 9), RoomJoin.key(-4, 0)));
        List<Long> pass = StalePass.next(stale, plan, 0);
        assertEquals(List.of(RoomJoin.key(0, 1), RoomJoin.key(3, 3), RoomJoin.key(-4, 0)), pass, "ring order, the outside one left out");
        assertEquals(3, StalePass.count(stale, plan));
        assertEquals(5, StalePass.MAX_PASSES, "the owner was told: five end passes");
        assertEquals(pass, StalePass.next(stale, plan, 4), "the fifth pass still runs");
        assertEquals(List.of(), StalePass.next(stale, plan, StalePass.MAX_PASSES));
        assertEquals(List.of(), StalePass.next(Set.of(RoomJoin.key(9, 9)), plan, 0));
        Random r = new Random(3);
        RingPlan big = new RingPlan(-20, 7, 30);
        for (int i = 0; i < 2000; i++) {
            long index = (long) (r.nextDouble() * big.count());
            int[] c = big.chunkAt(index);
            assertEquals(index, big.indexOf(c[0], c[1]));
        }
    }
}
