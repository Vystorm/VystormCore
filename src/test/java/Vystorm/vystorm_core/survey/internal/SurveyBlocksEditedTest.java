package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import Vystorm.vystorm_core.blocks.BlocksEditedEvent;
import Vystorm.vystorm_core.blocks.EditCause;
import Vystorm.vystorm_core.blocks.EditedChunk;
import Vystorm.vystorm_core.survey.OpenChunk;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

/**
 * The survey's answer to a bulk edit ({@code BlocksEditedEvent}): one pass over the written cells of the chunk, the
 * same rule as for single block changes (only cells that turn open or opaque count, neighbours within the margin).
 */
class SurveyBlocksEditedTest {
    private static final int MIN_Y = -64, HEIGHT = 384, MARGIN = 9;
    /** State ids of the fake table: 0 air, 1 stone, 2 dirt, 3 water, 4 glass. */
    private static final byte[] CLASS_BY_ID = {CellClass.AIR, CellClass.OPAQUE, CellClass.OPAQUE, CellClass.FLUID, CellClass.SEE_THROUGH};
    private static final SurveyEdits.IdClasses IDS = id -> CLASS_BY_ID[id];

    private final World world = mock(World.class);

    private EditedChunk.Builder builder(int cx, int cz) {
        return EditedChunk.builder(UUID.randomUUID(), null, EditCause.EDIT, world, cx, cz).states(mock(Vystorm.vystorm_core.blocks.StateTable.class));
    }

    /** A chunk that is solid below y 64 and air from y 64 up. */
    private static ChunkClasses ground() {
        ChunkClasses.Builder b = new ChunkClasses.Builder(MIN_Y, HEIGHT);
        for (int s = 0; s < HEIGHT >> 4; s++) b.uniform(s, (MIN_Y + (s << 4)) < 64 ? CellClass.OPAQUE : CellClass.AIR);
        return b.build();
    }

    private static List<int[]> marked(SurveyEdits.Result r, int cx, int cz) {
        List<int[]> out = new ArrayList<>();
        r.forEachChunk(cx, cz, (x, z) -> out.add(new int[] {x, z}));
        return out;
    }

    @Test
    void givenACellInTheMiddleTurnsOpen_whenTheChunkThreeMinusTwoIsEdited_thenExactlyThatChunkIsMarkedOnce() {
        // margin 9 reaches over both borders from every cell, so a narrow rule shows the single chunk
        ChunkClasses classes = ground();
        OpenChunk cells = classes.open();
        EditedChunk edit = builder(3, -2).add(3 * 16 + 7, 10, -2 * 16 + 7, 1, 0).build();
        SurveyEdits.Result r = SurveyEdits.apply(edit, IDS, cells, classes, MIN_Y, HEIGHT, 4);
        List<int[]> marked = marked(r, 3, -2);
        assertEquals(1, marked.size(), "one chunk, once");
        assertEquals(3, marked.get(0)[0]);
        assertEquals(-2, marked.get(0)[1]);
        assertEquals(1, r.changedCells());
        assertTrue(r.cells().isOpen(7, 10, 7), "the open cells follow the edit at once");
        assertFalse(cells.isOpen(7, 10, 7), "the old snapshot stays as it was");
        assertEquals(CellClass.AIR, r.classes().at(7, 10 - MIN_Y, 7));
    }

    @Test
    void givenTheSameCellsRepeated_whenEdited_thenEveryChunkIsMarkedOnlyOnce() {
        ChunkClasses classes = ground();
        EditedChunk.Builder b = builder(3, -2);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) b.add(48 + x, 0, -32 + z, 1, 0);
        SurveyEdits.Result r = SurveyEdits.apply(b.build(), IDS, classes.open(), classes, MIN_Y, HEIGHT, MARGIN);
        List<int[]> marked = marked(r, 3, -2);
        assertEquals(9, marked.size(), "the chunk and its eight neighbours, each once");
        assertEquals(256, r.changedCells());
    }

    @Test
    void givenCellsNearOneBorder_whenEdited_thenOnlyTheNeighboursWithinTheMarginAreMarked() {
        ChunkClasses classes = ground();
        // west border (local x 1), middle of z, narrow rule
        EditedChunk edit = builder(0, 0).add(1, 0, 8, 1, 0).build();
        List<int[]> marked = marked(SurveyEdits.apply(edit, IDS, classes.open(), classes, MIN_Y, HEIGHT, 4), 0, 0);
        assertEquals(2, marked.size());
        assertTrue(marked.stream().anyMatch(c -> c[0] == -1 && c[1] == 0), "the west neighbour");
        // north-east corner: the chunk, east, north and the corner chunk
        EditedChunk corner = builder(0, 0).add(15, 0, 0, 1, 0).build();
        List<int[]> four = marked(SurveyEdits.apply(corner, IDS, classes.open(), classes, MIN_Y, HEIGHT, 4), 0, 0);
        assertEquals(4, four.size());
        assertTrue(four.stream().anyMatch(c -> c[0] == 1 && c[1] == -1), "the corner chunk");
    }

    @Test
    void givenEditsThatKeepCellsOpenOrOpaque_whenEdited_thenNothingIsMarkedButTheClassesFollow() {
        ChunkClasses classes = ground();
        OpenChunk cells = classes.open();
        // stone to dirt (both opaque), air to water (both open)
        EditedChunk edit = builder(0, 0).add(5, 10, 5, 1, 2).add(5, 100, 5, 0, 3).build();
        SurveyEdits.Result r = SurveyEdits.apply(edit, IDS, cells, classes, MIN_Y, HEIGHT, MARGIN);
        assertTrue(marked(r, 0, 0).isEmpty(), "dirt to grass, water to air: ignored like single block changes");
        assertEquals(0, r.changedCells());
        assertSame(cells, r.cells(), "no new snapshot of the open cells");
        assertEquals(CellClass.FLUID, r.classes().at(5, 100 - MIN_Y, 5), "the cached classes know the water");
    }

    @Test
    void givenARecordWithoutCellsOrClasses_whenEdited_thenTheBeforeAndAfterStatesDecide() {
        EditedChunk opens = builder(2, 2).add(40, 0, 40, 1, 4).build(); // stone to glass
        SurveyEdits.Result r = SurveyEdits.apply(opens, IDS, null, null, MIN_Y, HEIGHT, 4);
        assertEquals(1, marked(r, 2, 2).size());
        assertEquals(null, r.cells());
        assertEquals(null, r.classes());
        EditedChunk same = builder(2, 2).add(40, 0, 40, 1, 2).build(); // stone to dirt
        assertTrue(marked(SurveyEdits.apply(same, IDS, null, null, MIN_Y, HEIGHT, 4), 2, 2).isEmpty());
    }

    @Test
    void givenACellWrittenTwiceInOneEvent_whenItEndsAsItWas_thenTheLastWriteCounts() {
        ChunkClasses classes = ground();
        OpenChunk cells = classes.open();
        EditedChunk edit = builder(0, 0).add(5, 10, 5, 1, 0).add(5, 10, 5, 0, 1).build();
        SurveyEdits.Result r = SurveyEdits.apply(edit, IDS, cells, classes, MIN_Y, HEIGHT, 4);
        assertFalse(r.cells().isOpen(5, 10, 5));
        assertEquals(CellClass.OPAQUE, r.classes().at(5, 10 - MIN_Y, 5));
    }

    @Test
    void givenCellsOutsideTheWorldHeight_whenEdited_thenTheyAreSkipped() {
        ChunkClasses classes = ground();
        EditedChunk edit = builder(0, 0).add(5, MIN_Y - 1, 5, 1, 0).add(5, MIN_Y + HEIGHT, 5, 1, 0).build();
        SurveyEdits.Result r = SurveyEdits.apply(edit, IDS, classes.open(), classes, MIN_Y, HEIGHT, 4);
        assertTrue(marked(r, 0, 0).isEmpty());
    }

    @Test
    void givenOneMillionCellsInSixteenChunks_whenEdited_thenThePassStaysCheap() {
        ChunkClasses classes = ground();
        OpenChunk cells = classes.open();
        List<EditedChunk> edits = new ArrayList<>();
        // 16 chunks x 256 columns x 245 cells = 1,003,520 cells, solid ground carved open
        for (int c = 0; c < 16; c++) {
            EditedChunk.Builder b = builder(c & 3, c >> 2);
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    for (int y = MIN_Y; y < MIN_Y + 245; y++) b.add(((c & 3) << 4) + x, y, ((c >> 2) << 4) + z, y < 64 ? 1 : 0, 0);
            edits.add(b.build());
        }
        int cellsTotal = edits.stream().mapToInt(EditedChunk::size).sum();
        assertTrue(cellsTotal >= 1_000_000);
        long best = Long.MAX_VALUE;
        long changed = 0;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            changed = 0;
            for (EditedChunk e : edits) changed += SurveyEdits.apply(e, IDS, cells, classes, MIN_Y, HEIGHT, MARGIN).changedCells();
            best = Math.min(best, System.nanoTime() - start);
        }
        assertEquals(16L * 256 * 128, changed, "the 128 solid cells of every column turned open");
        System.out.printf("SurveyBlocksEditedTest: %d cells in %.1f ms%n", cellsTotal, best / 1e6);
        assertTrue(best < 1_000_000_000L, "1M cells took " + best / 1e6 + " ms");
    }

    @Test
    void givenTheWatcher_whenItIsAListener_thenItHandlesBlocksEditedEventAtMonitor() throws Exception {
        Method m = SurveyWatcher.class.getMethod("onBlocksEdited", BlocksEditedEvent.class);
        EventHandler h = m.getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, h.priority());
    }
}
