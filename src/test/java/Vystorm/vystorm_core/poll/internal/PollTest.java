package Vystorm.vystorm_core.poll.internal;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PollTest {
    @Test void givenWhitespaceWhenCreatedThenPartsAreTrimmed() {
        Poll poll = new Poll(" Event? | Racing | Building ", 1000, 10, 6, 120);
        assertEquals("Event?", poll.question);
        assertEquals(java.util.List.of("Racing", "Building"), poll.options);
        assertEquals(11000, poll.deadline);
    }

    @Test void givenSamePlayerWhenChangingVoteThenOnlyLatestCounts() {
        Poll poll = new Poll("Q|A|B", 0, 10, 6, 120);
        UUID player = UUID.randomUUID();
        assertTrue(poll.vote(player, 1, 1));
        assertTrue(poll.vote(UUID.fromString(player.toString()), 2, 2));
        assertEquals(1, poll.voters());
        assertArrayEquals(new int[]{0, 1}, poll.counts());
    }

    @Test void givenDeadlineOrInvalidOptionWhenVotingThenNoVoteChanges() {
        Poll poll = new Poll("Q|A|B", 0, 10, 6, 120);
        UUID player = UUID.randomUUID();
        assertFalse(poll.vote(player, 0, 1));
        assertFalse(poll.vote(player, 3, 1));
        assertTrue(poll.vote(player, 1, 9999));
        assertFalse(poll.vote(player, 2, 10000));
        assertArrayEquals(new int[]{1, 0}, poll.counts());
    }

    @Test void givenMalformedPollWhenCreatingThenRejected() {
        for (String input : new String[]{"Q|A", "Q|A|", " |A|B", "Q|A|a", "Q|A|B|C"})
            assertThrows(IllegalArgumentException.class, () -> new Poll(input, 0, 10, 2, 120));
        assertThrows(IllegalArgumentException.class, () -> new Poll("Long question|A|B", 0, 10, 6, 3));
        assertThrows(IllegalArgumentException.class, () -> new Poll("Q|A|B", 0, 0, 6, 120));
    }

    @Test void givenSeparatePollsWhenCreatedThenIdsDifferAndCountsAreIsolated() {
        Poll first = new Poll("Q|A|B", 0, 10, 6, 120);
        Poll second = new Poll("Q|A|B", 0, 10, 6, 120);
        first.vote(UUID.randomUUID(), 1, 1);
        assertNotEquals(first.id, second.id);
        assertArrayEquals(new int[]{0, 0}, second.counts());
        int[] snapshot = first.counts();
        snapshot[0] = 99;
        assertArrayEquals(new int[]{1, 0}, first.counts());
    }
}
