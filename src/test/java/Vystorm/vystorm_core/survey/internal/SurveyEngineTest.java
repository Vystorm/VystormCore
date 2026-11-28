package Vystorm.vystorm_core.survey.internal;

import static Vystorm.vystorm_core.survey.internal.CellClass.AIR;
import static Vystorm.vystorm_core.survey.internal.CellClass.DOOR_CELL;
import static Vystorm.vystorm_core.survey.internal.CellClass.FLUID;
import static Vystorm.vystorm_core.survey.internal.CellClass.OPAQUE;
import static Vystorm.vystorm_core.survey.internal.CellClass.SEE_THROUGH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.survey.CellKind;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.RoomInfo;
import Vystorm.vystorm_core.survey.SurveyFace;
import Vystorm.vystorm_core.survey.SurveyRule;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SurveyEngineTest {
    private static final SurveyRule RULE = SurveyRule.OVERWORLD;

    @BeforeEach
    void workerThread() { SurveyThreads.install(() -> false); }

    @AfterEach
    void reset() { SurveyThreads.install(null); }

    /** Stone up to y 39, air above, over the 3 x 3 chunks around 0,0. */
    private static TestWorld ground() { return new TestWorld(0, 64).sky(-1, -1, 1, 1, 40); }

    @Test
    void givenAPocketInsideStone_whenSurveyed_thenItIsOneSealedEnclosedRoom() {
        TestWorld w = ground().box(6, 10, 6, 8, 12, 8, AIR);
        ChunkSurvey s = w.survey(0, 0, RULE);
        assertEquals(1, s.rooms().size());
        RoomInfo room = s.room(1);
        assertEquals(27, room.volume());
        assertTrue(room.sealed());
        assertFalse(room.mouth());
        assertFalse(room.fluid());
        assertEquals(CellKind.ENCLOSED, s.kindAt(7, 11, 7));
        assertEquals(1, s.roomAt(6, 10, 6));
        assertEquals(CellKind.SOLID, s.kindAt(7, 20, 7));
        assertEquals(CellKind.SURFACE, s.kindAt(7, 50, 7));
        assertEquals(0, s.roomAt(7, 50, 7));
        assertArrayEquals(new int[] {1}, s.roomsInSection(0));
        assertEquals((10 << 8) | (6 << 4) | 6, s.seed(1, 0));
        assertEquals(-1, s.seed(1, 1));
        for (SurveyFace f : SurveyFace.values()) assertTrue(s.borders(f).isEmpty());
        assertEquals(0, s.reachRuns().length, "the flat ground has no surface cells outside the sky column");
    }

    @Test
    void givenAShaftFromTheSurface_whenSurveyed_thenTheFirstReachCellsAreSurfaceAndTheRestIsARoomWithAMouth() {
        // shaft at x 7, z 7 from y 39 down to y 20: 20 cells; the sky column ends at y 40
        TestWorld w = ground().box(7, 20, 7, 7, 39, 7, AIR);
        ChunkSurvey s = w.survey(0, 0, RULE);
        // the shaft is under no opaque block, so it is part of the sky column: all surface
        assertEquals(0, s.rooms().size());
        // now a roof: a side tunnel at y 20 going east from the shaft bottom for 14 cells
        w.box(8, 20, 7, 14, 20, 7, AIR);
        s = w.survey(0, 0, RULE);
        assertEquals(CellKind.SURFACE, s.kindAt(8, 20, 7), "1 step from the sky column");
        assertEquals(CellKind.SURFACE, s.kindAt(14, 20, 7), "7 steps");
        w.box(15, 20, 7, 15, 20, 7, AIR).box(15, 20, 8, 15, 20, 9, AIR);
        s = w.survey(0, 0, RULE);
        assertEquals(CellKind.SURFACE, s.kindAt(15, 20, 7), "8 steps = reach");
        assertEquals(CellKind.ENCLOSED, s.kindAt(15, 20, 8), "9 steps");
        assertEquals(1, s.rooms().size());
        assertTrue(s.room(1).mouth(), "the room touches a surface cell");
        assertFalse(s.room(1).sealed());
        // the reach cells are stored as runs: 8 cells at y 20 x 8..15 z 7
        int[] runs = s.reachRuns();
        assertArrayEquals(new int[] {(20 * 16 + 7) * 16 + 8, 8}, runs);
    }

    @Test
    void givenAHouseWithADoor_whenDoorsStopReach_thenTheInsideStaysEnclosed() {
        // a closed stone box standing on the ground at y 40..44, inside air, a door in the west wall
        TestWorld w = ground().box(4, 40, 4, 10, 44, 10, OPAQUE).box(5, 41, 5, 9, 43, 9, AIR);
        w.set(4, 41, 7, DOOR_CELL).set(4, 42, 7, DOOR_CELL);
        ChunkSurvey stop = w.survey(0, 0, RULE);
        assertEquals(CellKind.ENCLOSED, stop.kindAt(7, 42, 7));
        assertEquals(CellKind.SURFACE, stop.kindAt(4, 41, 7), "the door itself is reached");
        assertEquals(1, stop.rooms().size());
        assertTrue(stop.room(1).mouth(), "the room touches the door, a surface cell");
        ChunkSurvey through = w.survey(0, 0, RULE.withDoorsStopReach(false));
        assertEquals(CellKind.SURFACE, through.kindAt(7, 42, 7), "without the door rule reach spreads inside");
        // glass never stops reach
        w.set(4, 41, 7, SEE_THROUGH).set(4, 42, 7, SEE_THROUGH);
        assertEquals(CellKind.SURFACE, w.survey(0, 0, RULE).kindAt(7, 42, 7));
        assertTrue(RULE.hash() != RULE.withDoorsStopReach(false).hash(), "the rule is part of the record");
    }

    @Test
    void givenACavernWithoutSky_whenHallsCount_thenTheCavernIsSurfaceAndAFarTunnelIsEnclosed() {
        TestWorld w = new TestWorld(0, 64).box(-16, 0, -16, 31, 63, 31, OPAQUE);
        w.box(2, 20, 2, 10, 28, 10, AIR); // 9 x 9 x 9 cavern
        w.box(11, 24, 6, 15, 24, 6, AIR).box(16, 24, 6, 29, 24, 6, AIR); // tunnel east, 19 cells
        SurveyRule nether = SurveyRule.NETHER;
        ChunkSurvey s = w.survey(0, 0, nether);
        assertEquals(CellKind.SURFACE, s.kindAt(6, 24, 6), "hall centre");
        // the hall core is the 3 x 3 x 3 block in the middle (x, z 5..7, y 23..25)
        assertEquals(CellKind.SURFACE, s.kindAt(3, 21, 3), "2 + 2 + 2 steps from the hall core");
        assertEquals(CellKind.ENCLOSED, s.kindAt(2, 20, 2), "the corner is 3 + 3 + 3 = 9 steps away");
        assertEquals(CellKind.SURFACE, s.kindAt(15, 24, 6), "tunnel 8 steps from the hall core (x 7 + 8 = 15)");
        ChunkSurvey east = w.survey(1, 0, nether);
        assertEquals(CellKind.ENCLOSED, east.kindAt(16, 24, 6), "9 steps");
        assertTrue(east.room(1).mouth());
        ChunkSurvey noHall = w.survey(0, 0, nether.withHallRadius(0));
        assertEquals(CellKind.ENCLOSED, noHall.kindAt(6, 24, 6), "without halls the cavern is a room");
    }

    @Test
    void givenRandomGrids_whenHallsAreFound_thenTheSlidingWindowEqualsTheNaiveBoxTest() {
        Random random = new Random(5);
        for (int round = 0; round < 6; round++) {
            TestWorld w = new TestWorld(0, 32).box(-16, 0, -16, 31, 31, 31, OPAQUE);
            // large random open blobs
            for (int b = 0; b < 25; b++) {
                int x = random.nextInt(40) - 12, y = random.nextInt(28), z = random.nextInt(40) - 12;
                w.box(x, y, z, x + 2 + random.nextInt(8), Math.min(31, y + 2 + random.nextInt(8)), z + 2 + random.nextInt(8), AIR);
            }
            int r = 1 + random.nextInt(3);
            SurveyRule rule = new SurveyRule(0, r, false, false); // reach 0: surface = sky column + hall cells only
            ChunkSurvey s = w.survey(0, 0, rule);
            for (int y = 0; y < 32; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        if (!CellClass.open(w.get(x, y, z))) continue;
                        boolean hall = true;
                        for (int dy = -r; dy <= r && hall; dy++)
                            for (int dz = -r; dz <= r && hall; dz++)
                                for (int dx = -r; dx <= r && hall; dx++) {
                                    int yy = y + dy;
                                    if (yy < 0 || yy >= 32 || !CellClass.open(w.get(x + dx, yy, z + dz))) hall = false;
                                }
                        boolean skyColumn = true;
                        for (int yy = y + 1; yy < 32 && skyColumn; yy++) if (!CellClass.open(w.get(x, yy, z))) skyColumn = false;
                        CellKind expected = hall || skyColumn ? CellKind.SURFACE : CellKind.ENCLOSED;
                        assertEquals(expected, s.kindAt(x, y, z), "round " + round + " r " + r + " at " + x + "," + y + "," + z);
                    }
        }
    }

    @Test
    void givenAFloatingIsland_whenVoidBelowCounts_thenTheAirBelowIsSurface() {
        TestWorld w = new TestWorld(0, 64).sky(-1, -1, 1, 1, 0).box(-16, 20, -16, 31, 30, 31, OPAQUE);
        assertEquals(CellKind.SURFACE, w.survey(0, 0, SurveyRule.END).kindAt(5, 5, 5));
        ChunkSurvey noVoid = w.survey(0, 0, SurveyRule.OVERWORLD);
        assertEquals(CellKind.ENCLOSED, noVoid.kindAt(5, 5, 5));
        assertTrue(w.survey(0, 0, SurveyRule.END).voidBelow());
    }

    @Test
    void givenFluidInARoom_whenSurveyed_thenTheRoomIsMarked() {
        TestWorld w = ground().box(3, 5, 3, 4, 6, 4, FLUID);
        ChunkSurvey s = w.survey(0, 0, RULE);
        assertTrue(s.room(1).fluid());
        assertEquals(8, s.room(1).volume());
    }

    @Test
    void givenTheSameCells_whenSurveyedTwice_thenTheRecordsAreIdentical() {
        Random random = new Random(11);
        TestWorld w = ground();
        for (int i = 0; i < 400; i++) w.box(random.nextInt(48) - 16, random.nextInt(40), random.nextInt(48) - 16,
                random.nextInt(48) - 16, random.nextInt(40), random.nextInt(48) - 16, random.nextInt(9) == 0 ? FLUID : AIR);
        ChunkSurvey a = w.survey(0, 0, RULE), b = w.survey(0, 0, RULE);
        assertEquals(a, b);
        assertArrayEquals(SurveyCodec.encode(a), SurveyCodec.encode(b));
        assertTrue(a.consistent());
        // a larger margin than needed changes nothing
        ChunkSurvey wide = SurveyEngine.survey(w.grid(0, 0, 16), RULE, TestWorld.ID, 0, 0, 1);
        assertEquals(a, wide);
    }

    @Test
    void givenTheServerThread_whenASurveyStarts_thenItIsRefused() {
        SurveyThreads.install(() -> true);
        TestWorld w = ground();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> w.survey(0, 0, RULE));
        assertTrue(e.getMessage().contains("server thread"));
    }

    @Test
    void givenATooSmallMargin_whenSurveyed_thenItIsRefused() {
        TestWorld w = ground();
        assertThrows(IllegalArgumentException.class, () -> SurveyEngine.survey(w.grid(0, 0, 4), RULE, TestWorld.ID, 0, 0, 1));
    }
}
