package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.survey.CellKind;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.OpenChunk;
import Vystorm.vystorm_core.survey.SurveyMask;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A chunk's survey the moment the chunk loads: the stamp decision, the audit, the masks and the missing queue. */
class SurveyAtLoadTest {
    @BeforeAll
    static void workerThread() { SurveyThreads.install(() -> false); }

    @AfterAll
    static void reset() { SurveyThreads.install(null); }

    private static ChunkSurvey survey(SurveyFixtures.Fixture f, long stamp) {
        return SurveyEngine.survey(f.fullGrid().crop(f.rule().margin()), f.rule(), SurveyFixtures.WORLD, f.chunkX(), f.chunkZ(), stamp);
    }

    @Test
    void givenAStoredRecordAndTheSameStampInTheChunk_whenTheChunkLoads_thenTheRecordIsOfferedFromMemoryWithoutCells() {
        SurveyFixtures.Fixture f = SurveyFixtures.load("ow-43.241");
        ChunkSurvey s = survey(f, 0x5EED);
        RegionRecords region = new RegionRecords();
        region.put(RegionRecords.index(f.chunkX(), f.chunkZ()), SurveyCodec.encode(s));
        LoadGate.Result r = LoadGate.decide(region, f.chunkX(), f.chunkZ(), 0x5EED, f.rule().hash(), SurveyFixtures.WORLD);
        assertEquals(LoadGate.Outcome.OFFER, r.outcome());
        assertEquals(s, r.record());
        assertFalse(r.record().cells().isPresent(), "no snapshot was taken: the record has no cells");
        // the mask needs no cells: it is the one a consumer builds the chunk packet from
        SurveyMask fromRecord = r.record().mask();
        SurveyMask fromSurvey = s.mask();
        assertFalse(fromRecord.hasEnclosed());
        for (int sy = fromRecord.minSection(); sy < fromRecord.minSection() + fromRecord.sectionCount(); sy++)
            for (int i = 0; i < OpenChunk.WORDS; i++) {
                assertEquals(fromSurvey.surfaceWord(sy, i), fromRecord.surfaceWord(sy, i));
                assertEquals(fromSurvey.visibleWord(sy, i), fromRecord.visibleWord(sy, i));
            }
        // in the load event Core only checks the header and decodes on first use
        LoadGate.Result lazy = LoadGate.decide(region, f.chunkX(), f.chunkZ(), 0x5EED, f.rule().hash(), SurveyFixtures.WORLD, false);
        assertEquals(LoadGate.Outcome.OFFER, lazy.outcome());
        assertEquals(null, lazy.record());
        assertTrue(java.util.Arrays.equals(region.get(RegionRecords.index(f.chunkX(), f.chunkZ())), lazy.bytes()));
        // other stamp, other rule, no stamp, no region in memory, no record
        assertEquals(LoadGate.Outcome.STALE, LoadGate.decide(region, f.chunkX(), f.chunkZ(), 0x5EEE, f.rule().hash(), SurveyFixtures.WORLD).outcome());
        assertEquals(LoadGate.Outcome.STALE, LoadGate.decide(region, f.chunkX(), f.chunkZ(), 0x5EED, f.rule().hash() + 1, SurveyFixtures.WORLD).outcome());
        assertEquals(LoadGate.Outcome.STALE, LoadGate.decide(region, f.chunkX(), f.chunkZ(), 0, f.rule().hash(), SurveyFixtures.WORLD).outcome());
        assertEquals(LoadGate.Outcome.NOT_YET, LoadGate.decide(null, f.chunkX(), f.chunkZ(), 0x5EED, f.rule().hash(), SurveyFixtures.WORLD).outcome());
        assertEquals(LoadGate.Outcome.NONE, LoadGate.decide(region, f.chunkX() + 1, f.chunkZ(), 0x5EED, f.rule().hash(), SurveyFixtures.WORLD).outcome());
    }

    @Test
    void givenAnOfferedRecord_whenTheAuditSeesOtherCells_thenItIsStaleAndWithTheSameCellsItGetsThem() {
        SurveyFixtures.Fixture f = SurveyFixtures.load("ow-35.246");
        ChunkSurvey s = survey(f, 7);
        ChunkSurvey offered;
        try { offered = SurveyCodec.decode(SurveyCodec.encode(s), SurveyFixtures.WORLD, f.chunkX(), f.chunkZ()); }
        catch (SurveyCodec.FormatException e) { throw new AssertionError(e); }
        OpenChunk cells = s.cells().orElseThrow();
        assertTrue(LoadGate.audit(offered, cells).orElseThrow().cells().isPresent(), "same blocks: the record keeps, now with cells");
        // WorldEdit fast mode: a block changed without events and without touching the chunk's stamp
        OpenChunk edited = cells.with(5, 20, 5, !cells.isOpen(5, 20, 5));
        assertTrue(LoadGate.audit(offered, edited).isEmpty(), "the audit finds it");
    }

    @Test
    void givenAnAuditShare_whenChunksAreChosen_thenTheShareHoldsAndTheChoiceIsStable() {
        LoadGate.AuditPolicy sample = LoadGate.AuditPolicy.parse("sample", 0.1);
        int chosen = 0;
        for (int x = -50; x < 50; x++) for (int z = -50; z < 50; z++) if (sample.audits(x, z)) chosen++;
        assertTrue(chosen > 800 && chosen < 1200, "about 10 % of 10000: " + chosen);
        assertEquals(sample.audits(3, 4), sample.audits(3, 4));
        assertFalse(LoadGate.AuditPolicy.parse("off", 1).audits(1, 1));
        assertTrue(LoadGate.AuditPolicy.parse("all", 0).audits(1, 1));
    }

    @Test
    void givenRealChunks_whenMasked_thenTheBitsetsEqualKindAtCellForCell() {
        for (SurveyFixtures.Fixture f : SurveyFixtures.all()) {
            ChunkSurvey s = survey(f, 1);
            SurveyMask m = s.mask();
            assertTrue(m.hasEnclosed());
            assertTrue(m == s.mask(), "built once and kept");
            OpenChunk cells = s.cells().orElseThrow();
            for (int y = f.minY(); y < f.minY() + f.height(); y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        CellKind k = s.kindAt(x, y, z);
                        assertEquals(k == CellKind.SURFACE, m.isSurface(x, y, z), f.name() + " surface " + x + "," + y + "," + z);
                        assertEquals(k == CellKind.ENCLOSED, m.isEnclosed(x, y, z), f.name() + " enclosed " + x + "," + y + "," + z);
                        boolean near = k == CellKind.SURFACE;
                        int[][] nb = {{x - 1, y, z}, {x + 1, y, z}, {x, y - 1, z}, {x, y + 1, z}, {x, y, z - 1}, {x, y, z + 1}};
                        for (int[] n : nb)
                            if (n[0] >= 0 && n[0] < 16 && n[2] >= 0 && n[2] < 16 && n[1] >= f.minY() && n[1] < f.minY() + f.height())
                                near |= s.kindAt(n[0], n[1], n[2]) == CellKind.SURFACE;
                        assertEquals(near, m.isVisible(x, y, z), f.name() + " visible " + x + "," + y + "," + z);
                        assertEquals(!near, m.isHidden(x, y, z));
                        if (k == CellKind.SURFACE) assertTrue(cells.isOpen(x, y, z));
                    }
            assertTrue(m.footprint() < 60_000, f.name() + " mask " + m.footprint() + " bytes");
        }
    }

    @Test
    void givenManyLoadedChunksWithoutRecord_whenPicked_thenFewAtATimeNearestFirstAndOnlyNearPlayers() {
        MissingQueue q = new MissingQueue();
        for (int x = -30; x <= 30; x++) for (int z = -30; z <= 30; z++) q.add(RoomJoin.key(x, z));
        q.add(RoomJoin.key(0, 0));
        assertEquals(61 * 61, q.size());
        int[] players = {10, 10};
        List<Long> first = q.pick(4, players, 8);
        assertEquals(4, first.size(), "never more than asked for");
        assertEquals(RoomJoin.key(10, 10), first.get(0), "the player's own chunk first");
        for (long k : first) assertTrue(Math.max(Math.abs(RoomJoin.keyX(k) - 10), Math.abs(RoomJoin.keyZ(k) - 10)) <= 1);
        int taken = 4;
        for (List<Long> next; !(next = q.pick(50, players, 8)).isEmpty(); ) taken += next.size();
        assertEquals(17 * 17, taken, "only chunks within the view distance of the player");
        assertEquals(List.of(), q.pick(4, new int[0], 8), "no player, no survey");
    }
}
