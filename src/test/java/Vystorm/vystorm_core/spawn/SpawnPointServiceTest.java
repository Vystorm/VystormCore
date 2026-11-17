package Vystorm.vystorm_core.spawn;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpawnPointServiceTest {
    final SpawnPointService service = SpawnPointService.get();
    final UUID world = UUID.randomUUID();
    Plugin plugin(String name) { Plugin p = mock(Plugin.class); when(p.getName()).thenReturn(name); return p; }
    SpawnPoint point(String id, String region, String role) { return SpawnPoint.of(id, region, world, 1, 2, 3, role, "crypt"); }

    @BeforeEach void reset() { service.clear(); }

    @Test void givenSyncedRegion_whenQueried_thenPointsAndOwnerAreReturned() {
        var forge = plugin("WorldForge");long before = service.revision();
        service.sync(forge, "dungeon:a", List.of(point("a/1", "dungeon:a", "MELEE"), point("a/b", "dungeon:a", "BOSS")));
        assertEquals(2, service.inRegion("dungeon:a").size());
        assertEquals(Optional.of("WorldForge"), service.owner("dungeon:a"));
        assertTrue(service.revision() > before);
        long same = service.revision();
        service.sync(forge, "dungeon:a", List.of(point("a/1", "dungeon:a", "MELEE"), point("a/b", "dungeon:a", "BOSS")));
        assertEquals(same, service.revision(), "unchanged points keep the revision");
    }

    @Test void givenForeignRegion_whenAnotherPluginSyncs_thenItIsRejected() {
        service.sync(plugin("WorldForge"), "dungeon:a", List.of(point("a/1", "dungeon:a", "MELEE")));
        assertThrows(IllegalStateException.class, () -> service.sync(plugin("Other"), "dungeon:a", List.of(point("x", "dungeon:a", "MELEE"))));
        assertThrows(IllegalArgumentException.class, () -> service.sync(plugin("WorldForge"), "dungeon:a", List.of(point("x", "dungeon:b", "MELEE"))));
    }

    @Test void givenRetainAndDisable_whenApplied_thenOnlyKeptRegionsRemain() {
        var forge = plugin("WorldForge");
        service.sync(forge, "dungeon:a", List.of(point("a/1", "dungeon:a", "MELEE")));
        service.sync(forge, "dungeon:b", List.of(point("b/1", "dungeon:b", "MELEE")));
        service.sync(plugin("Arena"), "arena:x", List.of(point("x/1", "arena:x", "MELEE")));
        service.retain(forge, List.of("dungeon:b"));
        assertEquals(Set.of("dungeon:b", "arena:x"), service.regions());
        service.sync(forge, "dungeon:b", List.of());
        assertEquals(Set.of("arena:x"), service.regions());
        assertEquals(1, service.unregisterOwner(plugin("Arena")));
        assertTrue(service.all().isEmpty());
    }
}
