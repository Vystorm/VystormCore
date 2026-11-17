package Vystorm.vystorm_core.party;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PartyServicesTest {
    @Test
    void withoutSourceEveryoneIsAlone() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertFalse(PartyServices.available());
        assertTrue(PartyServices.partyOf(a).isEmpty());
        assertFalse(PartyServices.sameParty(a, b));
        assertTrue(PartyServices.flag(a, "friendly-fire", true));
        assertFalse(PartyServices.setting(UUID.randomUUID(), "x", "y"));
    }

    @Test
    void viewIsImmutableAndReadsFlags() {
        UUID a = UUID.randomUUID();
        Set<UUID> members = new HashSet<>(Set.of(a));
        PartyView view = new PartyView(UUID.randomUUID(), "Test", a, members, Map.of("xp-share", "false"), 0);
        members.add(UUID.randomUUID());
        assertEquals(1, view.members().size());
        assertFalse(view.flag("xp-share", true));
        assertTrue(view.flag("missing", true));
        assertTrue(view.contains(a));
    }
}
