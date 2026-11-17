package Vystorm.vystorm_core.worlds.internal;

import static org.mockito.Mockito.*;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;

class VisitSpawnCompatibilityTest {
    @Test void legacyLoginUsesTheSameDurableRecoveryLocation() {
        VisitEngine visits = mock(VisitEngine.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        Location current = mock(Location.class), recovered = mock(Location.class);
        when(visits.spawnLocation(id, current)).thenReturn(recovered);
        PlayerSpawnLocationEvent event = mock(PlayerSpawnLocationEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getSpawnLocation()).thenReturn(current);
        new VisitSpawnListeners.Legacy(visits).onSpawnLocation(event);
        verify(event).setSpawnLocation(recovered);
    }

    @Test void modernLoginKeepsTheAsyncRecoveryPath() {
        VisitEngine visits = mock(VisitEngine.class);
        AsyncPlayerSpawnLocationEvent event = mock(AsyncPlayerSpawnLocationEvent.class, RETURNS_DEEP_STUBS);
        UUID id = UUID.randomUUID();
        when(event.getConnection().getProfile().getId()).thenReturn(id);
        Location current = mock(Location.class), recovered = mock(Location.class);
        when(event.getSpawnLocation()).thenReturn(current);
        when(visits.spawnLocation(id, current)).thenReturn(recovered);
        new ModernVisitSpawnListener(visits).onSpawnLocation(event);
        verify(event).setSpawnLocation(recovered);
    }

    @Test void noRecoveryDoesNotOverrideAnotherPluginsSpawn() {
        VisitEngine visits = mock(VisitEngine.class);
        PlayerSpawnLocationEvent event = mock(PlayerSpawnLocationEvent.class, RETURNS_DEEP_STUBS);
        new VisitSpawnListeners.Legacy(visits).onSpawnLocation(event);
        verify(event, never()).setSpawnLocation(any());
    }
}
