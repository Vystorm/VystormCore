package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.OpenChunk;
import Vystorm.vystorm_core.survey.SurveyFace;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SurveyCodecTest {
    @BeforeAll
    static void workerThread() { SurveyThreads.install(() -> false); }

    @AfterAll
    static void reset() { SurveyThreads.install(null); }

    private static ChunkSurvey survey(SurveyFixtures.Fixture f, long stamp) {
        return SurveyEngine.survey(f.fullGrid().crop(f.rule().margin()), f.rule(), SurveyFixtures.WORLD, f.chunkX(), f.chunkZ(), stamp);
    }

    @Test
    void givenRealChunks_whenEncodedAndDecoded_thenTheRecordIsTheSameAndRebuildsTheSameCells() {
        for (SurveyFixtures.Fixture f : SurveyFixtures.all()) {
            ChunkSurvey s = survey(f, 0x1234_5678_9ABC_DEF0L);
            byte[] bytes = SurveyCodec.encode(s);
            ChunkSurvey back = assertDoesNotFail(bytes, f);
            assertEquals(s, back, f.name());
            assertFalse(back.cells().isPresent(), "cells are not stored");
            OpenChunk cells = s.cells().orElseThrow();
            ChunkSurvey bound = back.withCells(cells).orElseThrow();
            assertTrue(bound.consistent(), f.name());
            for (int yi = 0; yi < f.height(); yi += 3)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int y = yi + f.minY();
                        assertEquals(s.kindAt(x, y, z), bound.kindAt(x, y, z));
                        assertEquals(s.roomAt(x, y, z), bound.roomAt(x, y, z));
                    }
            assertArrayEquals(bytes, SurveyCodec.encode(back), "encoding is stable");
            assertEquals(0x1234_5678_9ABC_DEF0L, SurveyCodec.stampOf(bytes));
            assertEquals(f.rule().hash(), SurveyCodec.ruleHashOf(bytes));
        }
    }

    private static ChunkSurvey assertDoesNotFail(byte[] bytes, SurveyFixtures.Fixture f) {
        try {
            return SurveyCodec.decode(bytes, SurveyFixtures.WORLD, f.chunkX(), f.chunkZ());
        } catch (SurveyCodec.FormatException e) {
            throw new AssertionError(f.name() + ": " + e.getMessage(), e);
        }
    }

    @Test
    void givenAnyFlippedByte_whenDecoded_thenTheRecordIsRejected() {
        SurveyFixtures.Fixture f = SurveyFixtures.load("ow-43.241");
        byte[] bytes = SurveyCodec.encode(survey(f, 7));
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            byte[] broken = bytes.clone();
            int at = random.nextInt(broken.length);
            broken[at] ^= (byte) (1 + random.nextInt(255));
            assertThrows(SurveyCodec.FormatException.class, () -> SurveyCodec.decode(broken, SurveyFixtures.WORLD, 0, 0), "byte " + at);
        }
        assertThrows(SurveyCodec.FormatException.class, () -> SurveyCodec.decode(new byte[3], SurveyFixtures.WORLD, 0, 0));
        byte[] cut = java.util.Arrays.copyOf(bytes, bytes.length - 5);
        assertThrows(SurveyCodec.FormatException.class, () -> SurveyCodec.decode(cut, SurveyFixtures.WORLD, 0, 0));
    }

    @Test
    void givenChangedCells_whenBound_thenTheRecordIsStale() {
        SurveyFixtures.Fixture f = SurveyFixtures.load("ow-35.246");
        ChunkSurvey s = survey(f, 1);
        OpenChunk cells = s.cells().orElseThrow();
        OpenChunk changed = cells.with(3, 10, 4, !cells.isOpen(3, 10, 4));
        assertTrue(s.withCells(cells).isPresent());
        assertTrue(s.withCells(changed).isEmpty(), "another block = another open-cell hash");
        assertTrue(cells.with(3, 10, 4, cells.isOpen(3, 10, 4)) == cells, "no change, same instance");
    }

    @Test
    void givenRegionRecords_whenWrittenAndRead_thenEveryRecordComesBackAndDamageIsFound() throws Exception {
        RegionRecords region = new RegionRecords();
        for (SurveyFixtures.Fixture f : SurveyFixtures.all())
            region.put(RegionRecords.index(f.chunkX(), f.chunkZ()), SurveyCodec.encode(survey(f, f.chunkX())));
        assertTrue(region.dirty());
        byte[] file = region.encodeAndClean();
        assertFalse(region.dirty());
        RegionRecords back = RegionRecords.decode(file);
        Map<Integer, byte[]> a = region.snapshot(), b = back.snapshot();
        assertEquals(a.keySet(), b.keySet());
        for (var e : a.entrySet()) assertArrayEquals(e.getValue(), b.get(e.getKey()));
        for (int at : new int[] {0, 5, 9, file.length / 2, file.length - 1}) {
            byte[] broken = file.clone();
            broken[at] ^= 0x40;
            assertThrows(SurveyCodec.FormatException.class, () -> RegionRecords.decode(broken), "byte " + at);
        }
        assertEquals("r.1.7.vsurvey", RegionRecords.fileName(43, 241));
        assertEquals("r.-1.-1.vsurvey", RegionRecords.fileName(-4, -4));
        assertEquals(28 * 32 + 30, RegionRecords.index(-2, -4));
    }

    @Test
    void givenTwoRuns_whenEncoded_thenGapsAreRelativeAndRunsKeepTheirOrder() {
        SurveyFixtures.Fixture f = SurveyFixtures.load("ow--176.341");
        ChunkSurvey s = survey(f, 9);
        for (SurveyFace face : SurveyFace.values()) {
            int end = 0;
            for (var r : s.borders(face)) {
                assertTrue(r.start() >= end, "runs are ordered and do not overlap");
                end = r.end();
            }
        }
    }
}
