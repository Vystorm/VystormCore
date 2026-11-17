package Vystorm.vystorm_core.worldevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

import Vystorm.vystorm_core.worldevent.engine.EventDefinition;
import Vystorm.vystorm_core.worldevent.engine.EventScope;
import Vystorm.vystorm_core.worldevent.engine.EventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.Set;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class EventDomainFileTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);

    @TempDir Path folder;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn((Server) null);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
    }

    @AfterEach
    void tearDown() { bukkit.close(); }

    @Test
    void activeEventsAndCooldownsRoundTripWithTheDomainCatalog() {
        Plugin owner = owner();
        WorldEvents first = new WorldEvents(CLOCK, new Random(1L), folder);
        WorldEvents.Domain original = first.domain(owner, "weather");
        original.register(event());
        var started = original.start("storm", EventScope.GLOBAL, "global").event().orElseThrow();

        WorldEvents restarted = new WorldEvents(CLOCK, new Random(2L), folder);
        WorldEvents.Domain restored = restarted.domain(owner, "weather");

        assertEquals(Set.of("storm"), restored.registry().snapshot().keySet());
        assertEquals(Set.of(started.instanceId()), restored.runtime().activeEvents().stream()
                .map(e -> e.instanceId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(CLOCK.instant().plus(Duration.ofHours(2)), restored.runtime()
                .cooldownUntil("storm", EventScope.GLOBAL, "global").orElseThrow());
        first.close();
        restarted.close();
    }

    @Test
    void invalidExistingFileIsRejectedWithoutBeingReset() throws Exception {
        Path file = folder.resolve("weather.json");
        Files.createDirectories(folder);
        Files.writeString(file, "{\"schema\":99,\"definitions\":[],\"runtime\":{\"activeEvents\":[],\"cooldowns\":[]}}");
        byte[] original = Files.readAllBytes(file);

        WorldEvents events = new WorldEvents(CLOCK, new Random(1L), folder);

        assertThrows(IllegalStateException.class, () -> events.domain(owner(), "weather"));
        assertTrue(Files.exists(file));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(events.activeEvents().isEmpty());
    }

    @Test
    void offlineExpirySurvivesCheckpointBeforeHandlersRegisterAndIsDeliveredOnce() {
        Plugin owner = owner();
        WorldEvents original = new WorldEvents(CLOCK, new Random(1L), folder);
        var domain = original.domain(owner, "weather");
        domain.register(event());
        var started = domain.start("storm", EventScope.GLOBAL, "global").event().orElseThrow();
        original.close();
        Clock later = Clock.offset(CLOCK, Duration.ofMinutes(5));
        WorldEvents interrupted = new WorldEvents(later, new Random(2L), folder);
        interrupted.domain(owner, "weather").register(event());
        interrupted.close();
        WorldEvents restored = new WorldEvents(later, new Random(3L), folder);
        restored.domain(owner, "weather");
        java.util.List<java.util.UUID> ended = new java.util.ArrayList<>();
        restored.handler(owner, "storm", new WorldEventHandler() {
            public void start(Vystorm.vystorm_core.worldevent.engine.ActiveEvent e) { throw new AssertionError("must not replay start"); }
            public void stop(Vystorm.vystorm_core.worldevent.engine.ActiveEvent e,
                             Vystorm.vystorm_core.worldevent.engine.EventEndReason reason) {
                assertEquals(Vystorm.vystorm_core.worldevent.engine.EventEndReason.EXPIRED, reason);
                ended.add(e.instanceId());
            }
        });
        restored.tick(); restored.tick(); restored.close();
        assertEquals(java.util.List.of(started.instanceId()), ended);
        WorldEvents again = new WorldEvents(later, new Random(4L), folder);
        again.domain(owner, "weather");
        again.handler(owner, "storm", new WorldEventHandler() {
            public void start(Vystorm.vystorm_core.worldevent.engine.ActiveEvent e) { throw new AssertionError(); }
            public void stop(Vystorm.vystorm_core.worldevent.engine.ActiveEvent e,
                             Vystorm.vystorm_core.worldevent.engine.EventEndReason reason) { throw new AssertionError("already delivered"); }
        });
        again.tick(); again.close();
    }

    private static Plugin owner() {
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("file-test-owner");
        return owner;
    }

    private static EventDefinition event() {
        return new EventDefinition("storm", Set.of(EventScope.GLOBAL), EventType.NEGATIVE, "weather", 2.0D,
                Set.of("rain"), Duration.ofHours(2), Duration.ofMinutes(1), Duration.ofMinutes(1),
                Set.of(), Set.of(), Set.of("weather"), 3.0D, 0.0D, 1);
    }
}
