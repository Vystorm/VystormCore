package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.survey.JobOptions;
import Vystorm.vystorm_core.survey.OpenChunk;
import Vystorm.vystorm_core.survey.SurveyJob;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RingPlanTest {
    @Test
    void givenARadius_whenEveryIndexIsListed_thenEachChunkComesOnceRingByRing() {
        RingPlan plan = RingPlan.ofBlocks(100, -40, 80);
        assertEquals(6, plan.centerChunkX());
        assertEquals(-3, plan.centerChunkZ());
        assertEquals(5, plan.chunkRadius());
        assertEquals(121, plan.count());
        Set<Long> seen = new HashSet<>();
        int lastRing = 0;
        for (long i = 0; i < plan.count(); i++) {
            int[] c = plan.chunkAt(i);
            int ring = Math.max(Math.abs(c[0] - 6), Math.abs(c[1] + 3));
            assertTrue(ring >= lastRing, "rings only grow");
            assertEquals(RingPlan.ringOf(i), ring);
            lastRing = ring;
            assertTrue(seen.add(RoomJoin.key(c[0], c[1])), "chunk twice: " + c[0] + "," + c[1]);
            if (i > 0) {
                int[] prev = plan.chunkAt(i - 1);
                if (RingPlan.ringOf(i - 1) == ring)
                    assertEquals(1, Math.abs(prev[0] - c[0]) + Math.abs(prev[1] - c[1]), "neighbours inside a ring");
            }
        }
        assertArrayEquals(new int[] {6, -3}, plan.chunkAt(0));
        assertArrayEquals(new int[] {5, -4}, plan.chunkAt(1));
        assertNull(plan.chunkAt(plan.count()));
        assertEquals(1, new RingPlan(0, 0, 0).count());
        assertThrows(IllegalArgumentException.class, () -> new RingPlan(0, 0, -1));
    }

    @Test
    void givenALargeIndex_whenTheRingIsComputed_thenItMatchesTheSquares() {
        for (long r = 1; r < 3000; r += 37) {
            assertEquals(r, RingPlan.ringOf((2 * r - 1) * (2 * r - 1)));
            assertEquals(r, RingPlan.ringOf((2 * r + 1) * (2 * r + 1) - 1));
        }
    }

    @Test
    void givenAJobState_whenWrittenAndRead_thenEveryFieldComesBackAndBrokenFilesAreRefused() {
        UUID world = UUID.randomUUID();
        JobState s = new JobState(world, -300, 1200, 2000, JobOptions.defaults().withWorkers(3).withMillisPerTick(2.5),
                SurveyJob.State.PAUSED, 777, 12, 99_000);
        JobState back = JobState.parse(s.toText());
        assertEquals(world, back.worldId);
        assertEquals(-300, back.centerX);
        assertEquals(1200, back.centerZ);
        assertEquals(2000, back.radius);
        assertEquals(s.options, back.options);
        assertEquals(SurveyJob.State.PAUSED, back.state);
        assertEquals(777, back.next);
        assertEquals(12, back.skipped);
        assertEquals(99_000, back.millis);
        assertThrows(IllegalArgumentException.class, () -> JobState.parse("format=1\n"));
        assertThrows(IllegalArgumentException.class, () -> JobState.parse(s.toText().replace("next=777", "next=x")));
        assertThrows(IllegalArgumentException.class, () -> JobState.parse(s.toText().replace("format=1", "format=9")));
        assertThrows(IllegalArgumentException.class, () -> JobState.parse(s.toText().replace("generate=true", "generate=maybe")));
    }

    @Test
    void givenOpenCells_whenChanged_thenANewSnapshotIsMadeAndTheOldStays() {
        long[][] words = new long[4][OpenChunk.WORDS];
        java.util.Arrays.fill(words[3], -1L);
        words[1][5] = 0b1011;
        OpenChunk a = OpenChunk.of(-1, words);
        assertTrue(a.allSolid(-1));
        assertTrue(a.allOpen(2));
        assertTrue(!a.isOpen(0, 0, 0));
        int y = 1; // section 0 (index 1), word 5 bit 0 = cell 320: y 1, z 4, x 0
        assertTrue(a.isOpen(0, y, 4));
        assertTrue(a.isOpen(1, y, 4));
        OpenChunk b = a.with(0, y, 4, false);
        assertTrue(a.isOpen(0, y, 4), "the old snapshot is unchanged");
        assertTrue(!b.isOpen(0, y, 4));
        assertTrue(a.hash() != b.hash());
        assertEquals(a.openCount() - 1, b.openCount());
        assertEquals(a.hash(), OpenChunk.of(-1, words).hash());
        words[1][5] = 0; // the copy in a is not affected
        assertTrue(a.isOpen(0, y, 4));
    }
}
