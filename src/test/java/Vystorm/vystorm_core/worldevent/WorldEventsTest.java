package Vystorm.vystorm_core.worldevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.worldevent.engine.ActiveEvent;
import Vystorm.vystorm_core.worldevent.engine.EventDefinition;
import Vystorm.vystorm_core.worldevent.engine.EventEndReason;
import Vystorm.vystorm_core.worldevent.engine.EventScope;
import Vystorm.vystorm_core.worldevent.engine.EventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class WorldEventsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
    private MockedStatic<Bukkit> bukkit;
    private PluginManager pluginManager;
    private Plugin owner;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn((Server) null);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        pluginManager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("test-owner");
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void externalDomainCannotStartUntilDurableCheckpointIsBoundAndMarkedReady() {
        WorldEvents events = events();
        WorldEvents.Domain domain = events.externalDomain(owner, "blood-moon");
        AtomicInteger checkpoints = new AtomicInteger();
        domain.checkpoint(checkpoints::incrementAndGet);
        domain.register(event("blood_moon", Set.of(), Set.of("night")));

        assertEquals("provider-unavailable", domain.start("blood_moon", EventScope.GLOBAL, "global")
                .blockedReason().orElseThrow());

        domain.ready();
        assertTrue(domain.start("blood_moon", EventScope.GLOBAL, "global").started());
        assertTrue(checkpoints.get() >= 2, "catalog and running state are checkpointed");
        events.close();
    }

    @Test
    void handlerRunsForStartAndStopAndReceivesTheSameInstance() {
        WorldEvents events = events();
        WorldEvents.Domain domain = readyDomain(events, "events", event("storm", Set.of(), Set.of("weather")));
        List<String> calls = new java.util.ArrayList<>();
        events.handler(owner, "storm", new WorldEventHandler() {
            @Override public void start(ActiveEvent event) { calls.add("start:" + event.instanceId()); }
            @Override public void stop(ActiveEvent event, EventEndReason reason) {
                calls.add("stop:" + event.instanceId() + ":" + reason);
            }
        });

        ActiveEvent started = domain.start("storm", EventScope.GLOBAL, "global").event().orElseThrow();
        assertTrue(events.stop(started.instanceId()));

        assertEquals(List.of("start:" + started.instanceId(), "stop:" + started.instanceId() + ":MANUAL"), calls);
        verify(pluginManager).callEvent(org.mockito.ArgumentMatchers.any(WorldEventStartedEvent.class));
        verify(pluginManager).callEvent(org.mockito.ArgumentMatchers.any(WorldEventEndedEvent.class));
        events.close();
    }

    @Test
    void activeConflictInAnotherDomainBlocksStartAndStoppedEventKeepsItsDomainCooldown() {
        WorldEvents events = events();
        WorldEvents.Domain first = readyDomain(events, "first", event("storm", Set.of(), Set.of("weather")));
        WorldEvents.Domain second = readyDomain(events, "second",
                event("raid", Set.of("storm"), Set.of("weather")));

        ActiveEvent storm = first.start("storm", EventScope.GLOBAL, "global").event().orElseThrow();
        assertEquals("conflict:storm", second.start("raid", EventScope.GLOBAL, "global")
                .blockedReason().orElseThrow());
        assertTrue(first.stop(storm.instanceId()));
        assertEquals("cooldown", first.start("storm", EventScope.GLOBAL, "global")
                .blockedReason().orElseThrow());
        events.close();
    }

    @Test
    void stoppedEventCooldownBlocksSameEventInAnotherDomain() {
        WorldEvents events = events();
        WorldEvents.Domain first = readyDomain(events, "first", event("storm", Set.of(), Set.of("weather")));
        WorldEvents.Domain second = readyDomain(events, "second", event("storm", Set.of(), Set.of("weather")));

        ActiveEvent storm = first.start("storm", EventScope.GLOBAL, "global").event().orElseThrow();
        assertTrue(first.stop(storm.instanceId()));

        assertEquals("cooldown", second.start("storm", EventScope.GLOBAL, "global")
                .blockedReason().orElseThrow());
        events.close();
    }

    @Test
    void startHandlerFailureIsContainedAndTheNewEventIsStopped() {
        WorldEvents events = events();
        WorldEvents.Domain domain = readyDomain(events, "handlers", event("storm", Set.of(), Set.of("weather")));
        AtomicInteger stops = new AtomicInteger();
        events.handler(owner, "storm", new WorldEventHandler() {
            @Override public void start(ActiveEvent event) { throw new IllegalStateException("cannot start"); }
            @Override public void stop(ActiveEvent event, EventEndReason reason) { stops.incrementAndGet(); }
        });

        var result = domain.start("storm", EventScope.GLOBAL, "global");

        assertEquals("handler-failed", result.blockedReason().orElseThrow());
        assertTrue(domain.runtime().activeEvents().isEmpty());
        assertEquals(1, stops.get(), "failed start invokes handler cleanup once");
        verify(pluginManager, never()).callEvent(org.mockito.ArgumentMatchers.any(WorldEventStartedEvent.class));
        events.close();
    }

    @Test
    void failedStartCheckpointRollsBackRuntimeAndFailsClosed() {
        WorldEvents events = events();
        WorldEvents.Domain domain = events.externalDomain(owner, "broken-store");
        AtomicInteger checkpoints = new AtomicInteger();
        domain.checkpoint(() -> {
            if (checkpoints.incrementAndGet() > 1) throw new IllegalStateException("disk unavailable");
        });
        domain.register(event("storm", Set.of(), Set.of("weather")));
        domain.ready();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> domain.start("storm", EventScope.GLOBAL, "global"));

        assertTrue(domain.runtime().activeEvents().isEmpty(), "failed checkpoint does not leave a running instance");
        assertEquals("provider-unavailable", domain.start("storm", EventScope.GLOBAL, "global")
                .blockedReason().orElseThrow());
        events.close();
    }

    @Test
    void releasingOwnerRemovesDomainAndHandlerAndStopsOwnedEffects() {
        WorldEvents events = events();
        WorldEvents.Domain domain = readyDomain(events, "owned", event("storm", Set.of(), Set.of("weather")));
        AtomicInteger stops = new AtomicInteger();
        events.handler(owner, "storm", new WorldEventHandler() {
            @Override public void start(ActiveEvent event) { }
            @Override public void stop(ActiveEvent event, EventEndReason reason) { stops.incrementAndGet(); }
        });
        domain.start("storm", EventScope.GLOBAL, "global");
        assertTrue(events.available());

        events.release(owner);

        assertFalse(events.available());
        assertTrue(events.activeEvents().isEmpty());
        assertEquals(1, stops.get());
        assertEquals("unknown-event", events.start("storm", EventScope.GLOBAL, "global")
                .blockedReason().orElseThrow());
        events.close();
    }

    @Test
    void releaseOfOneOwnerLeavesOtherDomainsAvailable() {
        WorldEvents events = events();
        Plugin other = mock(Plugin.class);
        when(other.isEnabled()).thenReturn(true);
        WorldEvents.Domain owned = readyDomain(events, "owned", event("owned_event", Set.of(), Set.of("a")));
        readyDomain(events, other, "other", event("other_event", Set.of(), Set.of("b")));

        events.release(owner);

        assertFalse(owned.start("owned_event", EventScope.GLOBAL, "global").started());
        assertTrue(events.start("other_event", EventScope.GLOBAL, "global").started());
        events.close();
    }

    private WorldEvents events() {
        return new WorldEvents(CLOCK, new Random(7L));
    }

    private WorldEvents.Domain readyDomain(WorldEvents events, String id, EventDefinition definition) {
        return readyDomain(events, owner, id, definition);
    }

    private WorldEvents.Domain readyDomain(WorldEvents events, Plugin domainOwner, String id, EventDefinition definition) {
        WorldEvents.Domain domain = events.externalDomain(domainOwner, id);
        domain.checkpoint(() -> { });
        domain.register(definition);
        domain.ready();
        return domain;
    }

    private static EventDefinition event(String id, Set<String> conflicts, Set<String> groups) {
        return new EventDefinition(id, Set.of(EventScope.GLOBAL), EventType.NEGATIVE, "night", 1.0D,
                Set.of("night"), Duration.ofHours(1), Duration.ofMinutes(2), Duration.ofMinutes(2),
                conflicts, Set.of(), groups, 1.0D, 0.0D, 1);
    }
}
