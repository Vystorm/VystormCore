package Vystorm.vystorm_core._integrations.luckpermsIntegration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class DefaultluckpermsHandlerTest {
    private final DefaultluckpermsHandler handler = new DefaultluckpermsHandler();

    @Test
    void givenPrefixWildcard_whenChecked_thenGranted() {
        Player player = mock(Player.class);
        when(player.hasPermission("a.b.*")).thenReturn(true);
        assertTrue(handler.permissionChecker("a.b.c", player));
        assertFalse(handler.permissionChecker("x.y", player));
        verify(player, never()).hasPermission("c*");
    }

    @Test
    void givenOfflinePlayer_whenCheckedByUuid_thenDeniedWithoutException() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            UUID id = UUID.randomUUID();
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
            assertFalse(handler.permissionChecker("a.b", id));
        }
    }
}
