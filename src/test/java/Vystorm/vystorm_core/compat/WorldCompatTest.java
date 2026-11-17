package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class WorldCompatTest {
    @Test void keyedLookupDistinguishesNamespacesWithoutLoadingWorlds() {
        World vanilla = mock(World.class), custom = mock(World.class);
        when(vanilla.getKey()).thenReturn(NamespacedKey.minecraft("mountains"));
        when(custom.getKey()).thenReturn(new NamespacedKey("strata", "mountains"));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(vanilla, custom));
            assertSame(custom, WorldCompat.getWorld(new NamespacedKey("strata", "mountains")));
            assertNull(WorldCompat.getWorld(new NamespacedKey("other", "mountains")));
            verify(vanilla, never()).getChunkAt(anyInt(), anyInt());
            verify(custom, never()).getChunkAt(anyInt(), anyInt());
        }
    }
    @Test void distanceValidationRejectsOutOfRangeBeforeTouchingWorld() {
        World world = mock(World.class);
        assertThrows(IllegalArgumentException.class, () -> WorldCompat.setViewDistance(world, 1));
        assertThrows(IllegalArgumentException.class, () -> WorldCompat.setSimulationDistance(world, 33));
        verifyNoInteractions(world);
    }
}
