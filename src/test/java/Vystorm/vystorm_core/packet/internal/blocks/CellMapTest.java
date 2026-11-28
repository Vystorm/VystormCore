package Vystorm.vystorm_core.packet.internal.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CellMapTest {
    @Test
    void givenRandomPutsAndRemovesWhenComparedWithAHashMapThenTheyAgree() {
        Random random = new Random(42);
        CellMap map = new CellMap();
        Map<Integer, Integer> reference = new HashMap<>();
        for (int step = 0; step < 200_000; step++) {
            int local = random.nextInt(4096);
            if (random.nextInt(3) == 0) {
                Integer old = reference.remove(local);
                assertEquals(old == null ? CellMap.MISSING : old, map.remove(local));
            } else {
                int value = random.nextInt(30_000);
                Integer old = reference.put(local, value);
                assertEquals(old == null ? CellMap.MISSING : old, map.put(local, value));
            }
            assertEquals(reference.size(), map.size());
        }
        for (int local = 0; local < 4096; local++)
            assertEquals(reference.getOrDefault(local, CellMap.MISSING), map.get(local));
        int seen = 0;
        for (int slot = 0; slot < map.slots(); slot++) if (map.localAt(slot) >= 0) seen++;
        assertEquals(reference.size(), seen);
    }

    @Test
    void givenFullSectionWhenEveryCellIsSetThenAllAreFound() {
        CellMap map = new CellMap();
        for (int i = 0; i < 4096; i++) map.put(i, i * 3);
        for (int i = 0; i < 4096; i++) assertEquals(i * 3, map.get(i));
        for (int i = 0; i < 4096; i += 2) map.remove(i);
        for (int i = 0; i < 4096; i++) assertEquals(i % 2 == 0 ? CellMap.MISSING : i * 3, map.get(i));
        assertFalse(map.contains(0));
        assertTrue(map.contains(1));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "-1,-64,-1", "29999999,2031,-29999999", "-30000000,-2032,30000000", "15,15,15", "16,16,16"})
    void givenPositionWhenPackedThenUnpacksExactly(int x, int y, int z) {
        long cell = CellKeys.cell(x, y, z);
        assertEquals(x, CellKeys.cellX(cell));
        assertEquals(y, CellKeys.cellY(cell));
        assertEquals(z, CellKeys.cellZ(cell));
        long section = CellKeys.sectionOf(x, y, z);
        assertEquals(x >> 4, CellKeys.sectionX(section));
        assertEquals(y >> 4, CellKeys.sectionY(section));
        assertEquals(z >> 4, CellKeys.sectionZ(section));
        int local = CellKeys.local(x, y, z);
        assertEquals(x, CellKeys.blockX(section, local));
        assertEquals(y, CellKeys.blockY(section, local));
        assertEquals(z, CellKeys.blockZ(section, local));
    }

    @Test
    void givenRandomCountsWhenComparedWithAHashMapThenLongIntMapAgrees() {
        Random random = new Random(7);
        LongIntMap map = new LongIntMap();
        Map<Long, Integer> reference = new HashMap<>();
        for (int step = 0; step < 100_000; step++) {
            long key = CellKeys.section(random.nextInt(64) - 32, random.nextInt(24) - 4, random.nextInt(64) - 32);
            int delta = random.nextBoolean() ? 1 : -1;
            int next = Math.max(0, reference.getOrDefault(key, 0) + delta);
            if (next == 0) reference.remove(key); else reference.put(key, next);
            assertEquals(next, map.add(key, delta));
        }
        assertEquals(reference.size(), map.size());
        reference.forEach((k, v) -> assertEquals(v, map.get(k)));
        int[] keys = {0};
        map.forEachKey(k -> keys[0]++);
        assertEquals(reference.size(), keys[0]);
    }
}
