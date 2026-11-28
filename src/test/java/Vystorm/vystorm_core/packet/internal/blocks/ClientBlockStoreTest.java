package Vystorm.vystorm_core.packet.internal.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClientBlockStoreTest {
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    /** States 100 and up are solid in the fake environment. */
    static final int GLASS = 7, STONE = 100, DIRT = 101, FLOWER = 8;

    /** One batch handed to send(): section and cell -> state (REAL = -1). */
    record Batch(UUID viewer, long section, Map<Long, Integer> cells) {}

    static final class FakeEnv implements ClientBlockStore.Env {
        final List<Batch> batches = new ArrayList<>();
        final Set<Long> missingChunks = new HashSet<>();
        final Set<Long> realSolid = new HashSet<>();
        int[] box; // x1 y1 z1 x2 y2 z2 inclusive, or null
        double eyeX, eyeY = 64, eyeZ;

        @Override public boolean hasChunk(ViewerModel viewer, int cx, int cz) { return !missingChunks.contains(CellKeys.chunk(cx, cz)); }
        @Override public boolean inBox(ViewerModel viewer, int x, int y, int z) {
            return box != null && x >= box[0] && y >= box[1] && z >= box[2] && x <= box[3] && y <= box[4] && z <= box[5];
        }
        @Override public boolean realSolid(ViewerModel viewer, int x, int y, int z) { return realSolid.contains(CellKeys.cell(x, y, z)); }
        @Override public boolean solid(int state) { return state >= 100; }
        @Override public boolean locate(ViewerModel viewer) { viewer.eyeX = eyeX; viewer.eyeY = eyeY; viewer.eyeZ = eyeZ; return true; }
        @Override public void send(ViewerModel viewer, long section, short[] locals, int[] states, int count) {
            Map<Long, Integer> cells = new LinkedHashMap<>();
            for (int i = 0; i < count; i++)
                cells.put(CellKeys.cell(CellKeys.blockX(section, locals[i]), CellKeys.blockY(section, locals[i]), CellKeys.blockZ(section, locals[i])), states[i]);
            batches.add(new Batch(viewer.id(), section, cells));
        }
        int cells() { return batches.stream().mapToInt(b -> b.cells().size()).sum(); }
        Integer last(int x, int y, int z) {
            Integer state = null;
            for (Batch b : batches) if (b.cells().containsKey(CellKeys.cell(x, y, z))) state = b.cells().get(CellKeys.cell(x, y, z));
            return state;
        }
        void reset() { batches.clear(); }
    }

    ClientBlockStore store;
    FakeEnv env;
    ViewerModel alice;
    long now;

    @BeforeEach
    void givenStoreWithOneViewer() {
        store = new ClientBlockStore(8192, 32768);
        env = new FakeEnv();
        alice = store.viewer(ALICE, WORLD, -4, 19);
        now = 0;
    }

    void tick() { store.tick(++now, env); }

    BlockSetModel open(String owner, int priority) {
        BlockSetModel set = store.open(owner, "test", priority, false);
        assertTrue(store.attach(set, alice));
        return set;
    }

    // ---------------------------------------------------------------- priorities

    /** owner priorities, then the state alice sees at (1, 64, 1) after each close in order. */
    static Stream<Arguments> priorityCases() {
        return Stream.of(
                Arguments.of("higher priority wins, then falls through to the lower set, then the real block",
                        new int[]{0, 5}, new int[]{GLASS, STONE}, new int[]{1, 0}, new int[]{STONE, GLASS, ClientBlockStore.REAL}),
                Arguments.of("equal priority: the newer set is shown",
                        new int[]{3, 3}, new int[]{GLASS, STONE}, new int[]{1, 0}, new int[]{STONE, GLASS, ClientBlockStore.REAL}),
                Arguments.of("closing the hidden lower set sends nothing new",
                        new int[]{0, 5}, new int[]{GLASS, STONE}, new int[]{0, 1}, new int[]{STONE, STONE, ClientBlockStore.REAL}),
                Arguments.of("negative priorities order the same way",
                        new int[]{-10, -2}, new int[]{GLASS, DIRT}, new int[]{1, 0}, new int[]{DIRT, GLASS, ClientBlockStore.REAL}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("priorityCases")
    void givenTwoSetsOnOneCellWhenClosingInOrderThenTheShownStateFallsThrough(
            String label, int[] priorities, int[] states, int[] closeOrder, int[] expected) {
        BlockSetModel[] sets = new BlockSetModel[priorities.length];
        for (int i = 0; i < priorities.length; i++) {
            sets[i] = open("p" + i, priorities[i]);
            store.put(sets[i], 1, 64, 1, states[i]);
        }
        tick();
        assertEquals(expected[0], env.last(1, 64, 1), label);
        for (int i = 0; i < closeOrder.length; i++) {
            store.close(sets[closeOrder[i]]);
            tick();
            assertEquals(expected[i + 1], env.last(1, 64, 1), label + " after close " + i);
        }
        assertEquals(-1, alice.shown(1, 64, 1));
    }

    @Test
    void givenHigherSetWhenItIsShownThenWinnerAndShownAgree() {
        BlockSetModel low = open("a", 0), high = open("b", 9);
        store.put(low, 2, 70, 2, GLASS);
        store.put(high, 2, 70, 2, STONE);
        tick();
        assertSame(high, store.shownBy(ALICE, 2, 70, 2));
        assertEquals(STONE, alice.shown(2, 70, 2));
        assertEquals(List.of(high, low), alice.sets());
    }

    // ---------------------------------------------------------------- budgets and batching

    static Stream<Arguments> budgetCases() {
        // viewer budget, set budget, cells, expected cells per tick until done
        return Stream.of(
                Arguments.of(10, 4096, 30, new int[]{10, 10, 10}),
                Arguments.of(8192, 4, 10, new int[]{4, 4, 2}),
                Arguments.of(8192, 4096, 5000, new int[]{4096, 904}),
                Arguments.of(3, 2, 5, new int[]{2, 2, 1}));
    }

    @ParameterizedTest(name = "viewer {0}, set {1}, cells {2}")
    @MethodSource("budgetCases")
    void givenBudgetsWhenSendingThenEachTickStaysWithinThem(int viewerBudget, int setBudget, int cells, int[] perTick) {
        store.budgets(viewerBudget, 1_000_000);
        BlockSetModel set = open("a", 0);
        store.budget(set, setBudget);
        long[] keys = new long[cells];
        int[] states = new int[cells];
        for (int i = 0; i < cells; i++) {
            keys[i] = CellKeys.cell(i % 64, 64 + (i / 4096), (i / 64) % 64);
            states[i] = GLASS;
        }
        store.putAll(set, keys, states, cells);
        for (int expected : perTick) {
            env.reset();
            tick();
            assertEquals(expected, env.cells());
        }
        env.reset();
        tick();
        assertEquals(0, env.cells());
        assertEquals(0, store.pending(set));
        assertTrue(store.idle());
    }

    @Test
    void givenTwoViewersWhenTheTotalBudgetIsSmallThenTheyTakeTurns() {
        store.budgets(10, 10);
        ViewerModel bob = store.viewer(BOB, WORLD, -4, 19);
        BlockSetModel a = open("a", 0);
        BlockSetModel b = store.open("b", "x", 0, false);
        store.attach(b, bob);
        for (int i = 0; i < 10; i++) {
            store.put(a, i, 64, 0, GLASS);
            store.put(b, i, 64, 0, GLASS);
        }
        tick();
        assertEquals(10, env.cells());
        assertEquals(1, env.batches.stream().map(Batch::viewer).distinct().count());
        UUID first = env.batches.get(0).viewer();
        env.reset();
        tick();
        assertEquals(10, env.cells());
        assertFalse(env.batches.get(0).viewer().equals(first), "the other viewer goes first in the next tick");
    }

    @Test
    void givenRestoresAndAdditionsWhenBudgetIsShortThenAQuarterIsKeptForRestores() {
        store.budgets(8, 1000);
        BlockSetModel old = open("a", 0);
        for (int i = 0; i < 20; i++) store.put(old, i, 64, 0, GLASS);
        store.budgets(100, 1000);
        tick();
        store.budgets(8, 1000);
        store.close(old);
        BlockSetModel next = open("a", 0);
        for (int i = 0; i < 20; i++) store.put(next, i, 64, 5, STONE);
        env.reset();
        tick();
        long restoresSent = env.batches.stream().flatMap(b -> b.cells().values().stream()).filter(s -> s == ClientBlockStore.REAL).count();
        assertEquals(2, restoresSent, "8 / 4 restores");
        assertEquals(8, env.cells());
    }

    @Test
    void givenCellsInTwoSectionsWhenSendingThenOneBatchPerSectionNearestFirst() {
        BlockSetModel set = open("a", 0);
        for (int i = 0; i < 16; i++) {
            store.put(set, i, 64, 100, GLASS); // section z = 6, far
            store.put(set, i, 64, 1, STONE);  // section z = 0, near
        }
        env.eyeX = 8; env.eyeZ = 0;
        tick();
        assertEquals(2, env.batches.size());
        assertEquals(CellKeys.section(0, 4, 0), env.batches.get(0).section());
        assertEquals(16, env.batches.get(0).cells().size());
        assertEquals(CellKeys.section(0, 4, 6), env.batches.get(1).section());
    }

    @Test
    void givenSmallBudgetWhenSectionsAreFarApartThenTheNearestSectionGoesFirst() {
        store.budgets(4, 1000);
        BlockSetModel set = open("a", 0);
        for (int i = 0; i < 4; i++) store.put(set, i, 64, 200, GLASS);
        for (int i = 0; i < 4; i++) store.put(set, i, 64, 20, STONE);
        env.eyeZ = 16;
        tick();
        assertEquals(1, env.batches.size());
        assertEquals(CellKeys.section(0, 4, 1), env.batches.get(0).section());
    }

    @Test
    void givenReplaceWithMostlyTheSameCellsWhenSendingThenOnlyChangesCost() {
        BlockSetModel set = open("a", 0);
        long[] keys = new long[100];
        int[] states = new int[100];
        for (int i = 0; i < 100; i++) { keys[i] = CellKeys.cell(i, 64, 0); states[i] = GLASS; }
        store.replace(set, keys, states, 100);
        tick();
        env.reset();
        keys[0] = CellKeys.cell(500, 64, 0);
        states[1] = STONE;
        store.replace(set, keys, states, 100);
        tick();
        assertEquals(3, env.cells(), "one removed (restore), one added, one changed");
        assertEquals(ClientBlockStore.REAL, env.last(0, 64, 0));
        assertEquals(STONE, env.last(1, 64, 0));
        assertEquals(GLASS, env.last(500, 64, 0));
        assertEquals(100, set.size());
    }

    @Test
    void givenUrgentCellsWhenFlushingThenOnlyTheyGoOutAndWithoutBudget() {
        store.budgets(1, 1);
        BlockSetModel background = open("bg", 0);
        for (int i = 0; i < 50; i++) store.put(background, i, 64, 3, GLASS);
        BlockSetModel view = open("view", 0);
        long[] keys = new long[30];
        int[] states = new int[30];
        for (int i = 0; i < 30; i++) { keys[i] = CellKeys.cell(i, 70, 3); states[i] = STONE; }
        store.putAll(view, keys, states, 30);
        store.markUrgent(alice, keys, 30);
        store.flushUrgent(ALICE, env);
        assertEquals(30, env.cells());
        assertTrue(env.batches.stream().allMatch(b -> b.cells().values().stream().allMatch(s -> s == STONE)));
        assertEquals(50, store.pending(background));
    }

    // ---------------------------------------------------------------- limits

    @Test
    void givenLimitsWhenExceededThenRefusedWithoutChange() {
        BlockSetModel set = open("a", 0);
        long[] keys = new long[ClientBlockStore.MAX_SET_CELLS + 1];
        int[] states = new int[keys.length];
        for (int i = 0; i < keys.length; i++) keys[i] = CellKeys.cell(i % 256, i / 65536, (i / 256) % 256);
        assertThrows(IllegalArgumentException.class, () -> store.putAll(set, keys, states, keys.length));
        assertEquals(0, set.size(), "all or nothing");
        store.putAll(set, keys, states, keys.length - 1);
        assertEquals(ClientBlockStore.MAX_SET_CELLS, set.size());

        // 131,072 per viewer: four full sets fit, a fifth is refused on attach and on growth.
        for (int s = 1; s < 4; s++) {
            BlockSetModel more = open("a" + s, 0);
            long[] shifted = new long[ClientBlockStore.MAX_SET_CELLS];
            for (int i = 0; i < shifted.length; i++) shifted[i] = CellKeys.cell(1000 * s + i % 256, i / 65536, (i / 256) % 256);
            store.putAll(more, shifted, new int[shifted.length], shifted.length);
        }
        assertEquals(ClientBlockStore.MAX_VIEWER_CELLS, alice.cells());
        BlockSetModel fifth = store.open("b", "x", 0, false);
        store.put(fifth, 9999, 0, 9999, GLASS);
        assertFalse(store.attach(fifth, alice));
        BlockSetModel empty = open("c", 0);
        assertThrows(IllegalArgumentException.class, () -> store.put(empty, 5000, 0, 5000, GLASS));
        assertEquals(0, empty.size());
    }

    @Test
    void givenSharedSetWhenOneViewerWouldPassHisLimitThenOnlyHeLosesTheSet() {
        ViewerModel bob = store.viewer(BOB, WORLD, -4, 19);
        for (int s = 0; s < 4; s++) {
            BlockSetModel full = store.open("other", "full" + s, 0, false);
            store.attach(full, bob);
            long[] keys = new long[ClientBlockStore.MAX_SET_CELLS];
            for (int i = 0; i < keys.length; i++) keys[i] = CellKeys.cell(1000 * s + i % 256, i / 65536, (i / 256) % 256);
            store.putAll(full, keys, new int[keys.length], keys.length);
        }
        BlockSetModel shared = store.open("a", "lamps", 0, true);
        assertTrue(store.attach(shared, alice));
        assertTrue(store.attach(shared, bob));
        store.put(shared, 9000, 64, 9000, GLASS);
        assertEquals(1, shared.size());
        assertEquals(List.of(ALICE), shared.viewerIds());
    }

    @Test
    void givenSixtyFourSetsWhenAttachingOneMoreThenRefused() {
        for (int i = 0; i < ClientBlockStore.MAX_SETS_PER_VIEWER; i++) open("o", i);
        BlockSetModel extra = store.open("o", "x", 0, false);
        assertFalse(store.attach(extra, alice));
        assertEquals(1, store.counters().refused());
    }

    // ---------------------------------------------------------------- repairs

    @Test
    void givenSentCellWhenTheRealBlockChangesThenItIsResentTwoTicksLater() {
        BlockSetModel set = open("a", 0);
        store.put(set, 5, 64, 5, GLASS);
        tick();
        env.reset();
        assertTrue(store.watched(WORLD, 5, 64, 5));
        store.realChanged(WORLD, 5, 64, 5);
        tick();
        assertEquals(0, env.cells(), "the real update may still be on its way");
        tick();
        assertEquals(GLASS, env.last(5, 64, 5));
        assertEquals(1, store.counters().repairs());
    }

    static Stream<Arguments> unwatchedCases() {
        return Stream.of(
                Arguments.of("other section", 100, 64, 100),
                Arguments.of("same section, uncovered cell", 6, 64, 5),
                Arguments.of("other world", -1, -1, -1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unwatchedCases")
    void givenChangeOutsideTheSetWhenReportedThenNothingIsRepaired(String label, int x, int y, int z) {
        BlockSetModel set = open("a", 0);
        store.put(set, 5, 64, 5, GLASS);
        tick();
        env.reset();
        if (x == -1) store.realChanged(UUID.randomUUID(), 5, 64, 5);
        else store.realChanged(WORLD, x, y, z);
        tick();
        tick();
        assertEquals(0, env.cells(), label);
        assertEquals(0, store.counters().repairs());
    }

    @Test
    void givenRefusedClickWhenRepairingOneViewerThenOnlyThatViewerGetsTheCell() {
        ViewerModel bob = store.viewer(BOB, WORLD, -4, 19);
        BlockSetModel shared = store.open("a", "shared", 0, true);
        store.attach(shared, alice);
        store.attach(shared, bob);
        store.put(shared, 1, 64, 1, GLASS);
        tick();
        env.reset();
        store.repair(BOB, 1, 64, 1);
        tick();
        tick();
        assertEquals(1, env.batches.size());
        assertEquals(BOB, env.batches.get(0).viewer());
    }

    // ---------------------------------------------------------------- chunks

    @Test
    void givenChunkLeavesAndReturnsWhenReloadedThenTheSetIsSentAgain() {
        BlockSetModel set = open("a", 0);
        store.put(set, 1, 64, 1, GLASS);
        store.put(set, 20, 64, 1, STONE);
        tick();
        store.chunkUnloaded(ALICE, 0, 0);
        assertEquals(-1, alice.shown(1, 64, 1));
        assertEquals(STONE, alice.shown(20, 64, 1));
        env.reset();
        tick();
        assertEquals(0, env.cells());
        store.chunkLoaded(ALICE, 0, 0);
        tick();
        assertEquals(1, env.cells());
        assertEquals(GLASS, env.last(1, 64, 1));
    }

    @Test
    void givenChunkNotHeldByTheClientWhenSendingThenItWaitsForTheChunk() {
        env.missingChunks.add(CellKeys.chunk(0, 0));
        BlockSetModel set = open("a", 0);
        store.put(set, 1, 64, 1, GLASS);
        tick();
        assertEquals(0, env.cells());
        assertTrue(store.idle(), "nothing to do until the chunk arrives");
        env.missingChunks.clear();
        store.chunkLoaded(ALICE, 0, 0);
        tick();
        assertEquals(GLASS, env.last(1, 64, 1));
    }

    @Test
    void givenReloadOfAChunkWithoutCellsWhenReportedThenNothingHappens() {
        BlockSetModel set = open("a", 0);
        store.put(set, 1, 64, 1, GLASS);
        tick();
        env.reset();
        store.chunkLoaded(ALICE, 5, 5);
        tick();
        assertEquals(0, env.cells());
        assertEquals(0, store.counters().chunkReloads());
    }

    // ---------------------------------------------------------------- expiry, viewers, shared

    @Test
    void givenExpiryWhenTheTickComesThenTheSetClosesAndRestores() {
        BlockSetModel set = open("a", 0);
        store.put(set, 1, 64, 1, GLASS);
        store.expireAfter(set, 3);
        tick();
        tick();
        assertFalse(set.closed());
        env.reset();
        tick();
        assertTrue(set.closed());
        assertEquals(ClientBlockStore.REAL, env.last(1, 64, 1));
        assertEquals(1, store.counters().expired());
    }

    @Test
    void givenViewerLeavesWhenDroppedThenPerPlayerSetsCloseSilentlyAndSharedOnesStay() {
        BlockSetModel own = open("a", 0);
        BlockSetModel shared = store.open("b", "s", 0, true);
        store.attach(shared, alice);
        store.put(own, 1, 64, 1, GLASS);
        store.put(shared, 2, 64, 1, GLASS);
        tick();
        env.reset();
        List<BlockSetModel> closed = store.dropViewer(ALICE);
        assertEquals(List.of(own), closed);
        assertTrue(own.closed());
        assertFalse(shared.closed());
        assertFalse(store.watched(WORLD, 1, 64, 1));
        tick();
        assertEquals(0, env.cells());
        assertNull(store.find(ALICE));
        assertEquals(List.of(shared), store.openSets());
    }

    @Test
    void givenSharedSetWhenOneViewerIsDetachedThenOnlyHeGetsTheRestore() {
        ViewerModel bob = store.viewer(BOB, WORLD, -4, 19);
        BlockSetModel shared = store.open("a", "s", 0, true);
        store.attach(shared, alice);
        store.attach(shared, bob);
        store.put(shared, 1, 64, 1, GLASS);
        tick();
        assertEquals(2, env.batches.size());
        env.reset();
        store.detach(shared, bob);
        tick();
        assertEquals(1, env.batches.size());
        assertEquals(BOB, env.batches.get(0).viewer());
        assertEquals(ClientBlockStore.REAL, env.last(1, 64, 1));
        env.reset();
        store.put(shared, 2, 64, 1, STONE);
        tick();
        assertEquals(ALICE, env.batches.get(0).viewer());
    }

    @Test
    void givenViewerInAnotherWorldWhenAskedAgainThenTheOldModelIsDropped() {
        BlockSetModel own = open("a", 0);
        ViewerModel moved = store.viewer(ALICE, UUID.randomUUID(), 0, 15);
        assertTrue(own.closed());
        assertTrue(moved.sets().isEmpty());
    }

    // ---------------------------------------------------------------- collision rule

    static Stream<Arguments> boxCases() {
        // state, real block solid, collision declared, sent?
        return Stream.of(
                Arguments.of("solid client block over real air inside the box is refused", STONE, false, false, false),
                Arguments.of("declared collision sets may do it", STONE, false, true, true),
                Arguments.of("solid over solid is a safe swap", STONE, true, false, true),
                Arguments.of("non-solid client block is visual only", FLOWER, false, false, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boxCases")
    void givenCellInTheViewersBoxWhenSendingThenOnlySafeSwapsGoOut(String label, int state, boolean realSolid, boolean collision, boolean sent) {
        env.box = new int[]{1, 64, 1, 1, 65, 1};
        if (realSolid) env.realSolid.add(CellKeys.cell(1, 64, 1));
        BlockSetModel set = open("a", 0);
        store.collision(set, collision);
        store.put(set, 1, 64, 1, state);
        tick();
        assertEquals(sent ? Integer.valueOf(state) : null, env.last(1, 64, 1), label);
        if (!sent) {
            assertEquals(1, store.pending(set));
            env.box = null;
            tick();
            assertEquals(state, env.last(1, 64, 1), "sent once the player stepped out");
        }
    }

    @Test
    void givenClosedSetWhenChangedThenRefused() {
        BlockSetModel set = open("a", 0);
        store.close(set);
        assertThrows(IllegalStateException.class, () -> store.put(set, 1, 1, 1, GLASS));
        assertFalse(store.attach(set, alice));
    }

    @Test
    void givenNoWorkWhenTickingThenNoViewerIsVisited() {
        BlockSetModel set = open("a", 0);
        store.put(set, 1, 64, 1, GLASS);
        tick();
        assertTrue(store.idle());
        assertEquals(0, store.activeViewers());
    }
}
