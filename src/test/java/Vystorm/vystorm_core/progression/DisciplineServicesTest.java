package Vystorm.vystorm_core.progression;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DisciplineServicesTest {
    private final Plugin owner = mock(Plugin.class);

    @AfterEach
    void cleanup() { DisciplineServices.unregister(owner); }

    @Test
    void givenSource_whenRegisteredAndOwnerDisabled_thenNeutralValues() {
        DisciplineSource source = mock(DisciplineSource.class);
        UUID player = UUID.randomUUID();
        when(owner.isEnabled()).thenReturn(true);
        when(source.disciplines()).thenReturn(Set.of("smithing"));
        when(source.level(player, "smithing")).thenReturn(7);
        DisciplineServices.register(owner, source);
        assertTrue(DisciplineServices.available());
        assertEquals(Set.of("smithing"), DisciplineServices.disciplines());
        assertEquals(7, DisciplineServices.level(player, "smithing"));
        when(owner.isEnabled()).thenReturn(false);
        assertFalse(DisciplineServices.available());
        assertEquals(0, DisciplineServices.level(player, "smithing"));
        assertEquals(Set.of(), DisciplineServices.disciplines());
    }

    @Test
    void givenFailingSource_whenQueried_thenFallbacksInsteadOfExceptions() {
        DisciplineSource source = mock(DisciplineSource.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("skills");
        when(source.disciplines()).thenThrow(new IllegalStateException("boom"));
        when(source.level(any(), any())).thenThrow(new IllegalStateException("boom"));
        when(source.hasUnlock(any(), any())).thenThrow(new IllegalStateException("boom"));
        DisciplineServices.register(owner, source);
        assertEquals(Set.of(), DisciplineServices.disciplines());
        assertEquals(0, DisciplineServices.level(UUID.randomUUID(), "x"));
        assertFalse(DisciplineServices.hasUnlock(UUID.randomUUID(), "x"));
    }

    @Test
    void givenOtherOwner_whenUnregistering_thenSourceStays() {
        when(owner.isEnabled()).thenReturn(true);
        DisciplineServices.register(owner, mock(DisciplineSource.class));
        DisciplineServices.unregister(mock(Plugin.class));
        assertTrue(DisciplineServices.available());
        DisciplineServices.unregister(owner);
        assertFalse(DisciplineServices.available());
    }
}
