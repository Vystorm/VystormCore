package Vystorm.vystorm_core.worldevent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SuppressWarnings("deprecation")
class WorldEventServicesTest {
    final Plugin owner = mock(Plugin.class);
    @AfterEach void reset() { WorldEventServices.unregister(owner); }

    @Test void withoutSourceEverythingIsNeutral() {
        WorldEventServices.unregister(owner);
        assertEquals(1.0, WorldEventServices.modifier("farm.crop.yield", UUID.randomUUID(), 0, 64, 0));
        assertTrue(WorldEventServices.active().isEmpty());
    }

    @Test void modifiersAreClampedAndFailuresAreNeutral() {
        var view = new WorldEventView(UUID.randomUUID(), "harvest_moon", "agriculture", "POSITIVE", "GLOBAL", "global", Set.of("harvest"), Instant.now(), Instant.now().plusSeconds(60), 0.5);
        WorldEventServices.register(owner, new WorldEventSource() {
            public List<WorldEventView> active() { return List.of(view); }
            public List<WorldEventView> activeAt(UUID w, int x, int y, int z) { return List.of(view); }
            public double modifier(String key, UUID w, int x, int y, int z) {
                if (key.equals("boom")) throw new IllegalStateException();
                return key.equals("huge") ? 500 : 1.5;
            }
        });
        assertEquals(1.5, WorldEventServices.modifier("farm.crop.yield", UUID.randomUUID(), 0, 0, 0));
        assertEquals(WorldEventServices.MAX_MODIFIER, WorldEventServices.modifier("huge", UUID.randomUUID(), 0, 0, 0));
        assertEquals(1.0, WorldEventServices.modifier("boom", UUID.randomUUID(), 0, 0, 0));
        assertEquals(List.of(view), WorldEventServices.active());
        assertTrue(view.hasTag("HARVEST"));
    }
}
