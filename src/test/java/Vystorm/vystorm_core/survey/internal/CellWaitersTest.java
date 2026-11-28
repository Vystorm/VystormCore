package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.survey.OpenChunk;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class CellWaitersTest {
    static OpenChunk cells() { return OpenChunk.of(-4, new long[8][OpenChunk.WORDS]); }

    @Test
    void givenWaitersForAChunk_whenItsCellsArePublished_thenAllCompleteWithThemAndOthersWait() throws Exception {
        CellWaiters w = new CellWaiters();
        CompletableFuture<OpenChunk> a = w.register(RoomJoin.key(1, 2)), b = w.register(RoomJoin.key(1, 2)), other = w.register(RoomJoin.key(3, 3));
        assertFalse(a.isDone());
        assertEquals(3, w.size());
        OpenChunk c = cells();
        w.publish(RoomJoin.key(1, 2), c);
        assertSame(c, a.get());
        assertSame(c, b.get());
        assertFalse(other.isDone());
        assertEquals(1, w.size());
        w.publish(RoomJoin.key(9, 9), c); // nobody waits: nothing happens
        assertEquals(1, w.size());
    }

    @Test
    void givenAWaiter_whenTheChunkUnloads_thenItFailsAndIsForgotten() {
        CellWaiters w = new CellWaiters();
        CompletableFuture<OpenChunk> a = w.register(RoomJoin.key(-5, 7));
        w.fail(RoomJoin.key(-5, 7), "unloaded");
        ExecutionException e = assertThrows(ExecutionException.class, a::get);
        assertTrue(e.getCause().getMessage().contains("unloaded"));
        assertEquals(0, w.size());
    }

    @Test
    void givenWaitersOfAWorld_whenAllFail_thenNoneIsLeft() {
        CellWaiters w = new CellWaiters();
        CompletableFuture<OpenChunk> a = w.register(1), b = w.register(2);
        w.failAll("survey stopped");
        assertTrue(a.isCompletedExceptionally() && b.isCompletedExceptionally());
        assertEquals(0, w.size());
    }
}
