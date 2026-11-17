package Vystorm.vystorm_core.party;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartyActivitiesApiTest {
    @Test void readinessCopiesInputsAndRequiresUnexpiredCompletePositiveAnswers() {
        UUID member = UUID.randomUUID();
        Set<UUID> members = new HashSet<>(Set.of(member)); Map<UUID, Boolean> answers = new HashMap<>();
        var check = new PartyReadyCheck(UUID.randomUUID(), UUID.randomUUID(), 100, members, answers);
        answers.put(member, true); members.clear();
        assertFalse(check.allReady(0)); assertEquals(1, check.members().size());
        assertThrows(UnsupportedOperationException.class, () -> check.answers().put(member, true));
        var complete = new PartyReadyCheck(check.id(), check.partyId(), 100, Set.of(member), Map.of(member, true));
        assertTrue(complete.allReady(99)); assertFalse(complete.allReady(100));
        assertFalse(new PartyReadyCheck(check.id(), check.partyId(), 100, Set.of(), Map.of()).allReady(0));
    }
    @Test void disabledProviderInvalidInputAndOffThreadCallsNeverReachSource() {
        Plugin owner = mock(Plugin.class), provider = mock(Plugin.class); PartySource source = mock(PartySource.class);
        when(owner.isEnabled()).thenReturn(true); when(provider.isEnabled()).thenReturn(true); when(provider.getName()).thenReturn("Skills");
        UUID party = UUID.randomUUID(), event = UUID.randomUUID();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            PartyServices.register(owner, source);
            when(source.recordProgress(party, event, "Skills", "goal", 0.5)).thenReturn(true);
            assertTrue(PartyServices.recordProgress(provider, party, event, "goal", 0.5));
            assertFalse(PartyServices.recordProgress(provider, party, event, "goal", Double.NaN));
            assertFalse(PartyServices.recordProgress(provider, party, event, "goal", -1));
            when(owner.isEnabled()).thenReturn(false);
            assertTrue(PartyServices.readyCheck(party).isEmpty());
            assertFalse(PartyServices.recordProgress(provider, party, event, "goal", 1));
            verify(source, times(1)).recordProgress(any(), any(), any(), any(), anyDouble());
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> PartyServices.readyCheck(party));
        } finally { PartyServices.unregister(owner); }
    }
}
