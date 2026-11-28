package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import Vystorm.vystorm_core.survey.CellKind;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The Java survey against the Node reference of the study ({@code survey.js}, rule R2 N=8, halls of radius 3 in the
 * Nether, sky from below in the End) on real chunks: three per dimension, compared cell for cell.
 */
class SurveyEngineFixtureTest {
    @BeforeAll
    static void workerThread() { SurveyThreads.install(() -> false); }

    @AfterAll
    static void reset() { SurveyThreads.install(null); }

    @TestFactory
    Stream<DynamicTest> givenRealChunks_whenSurveyed_thenKindsAndRoomsMatchTheNodeReference() {
        List<DynamicTest> tests = new ArrayList<>();
        for (SurveyFixtures.Fixture f : SurveyFixtures.all()) {
            tests.add(dynamicTest(f.name() + " full margin", () -> compare(f, f.fullGrid())));
            tests.add(dynamicTest(f.name() + " rule margin " + f.rule().margin(), () -> compare(f, f.fullGrid().crop(f.rule().margin()))));
            tests.add(dynamicTest(f.name() + " assembled from chunk classes",
                    () -> compare(f, SurveyGrid.assemble(f.rule().margin(), f.chunks()))));
        }
        return tests.stream();
    }

    private static void compare(SurveyFixtures.Fixture f, SurveyGrid grid) {
        ChunkSurvey s = SurveyEngine.survey(grid, f.rule(), SurveyFixtures.WORLD, f.chunkX(), f.chunkZ(), 42L);
        assertEquals(f.rooms(), s.rooms().size(), "room count of " + f.name());
        int kindDiff = 0, labelDiff = 0;
        CellKind[] kinds = CellKind.values();
        for (int yi = 0; yi < f.height(); yi++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int c = (yi * 16 + z) * 16 + x;
                    if (s.kindAt(x, yi + f.minY(), z) != kinds[f.kinds()[c]]) kindDiff++;
                    if (s.roomAt(x, yi + f.minY(), z) != f.labels()[c]) labelDiff++;
                }
        assertEquals(0, kindDiff, "cells with another kind than Node in " + f.name());
        assertEquals(0, labelDiff, "cells with another room label than Node in " + f.name());
        assertTrue(s.consistent(), "labels rebuilt from the seeds match the room table");
    }
}
