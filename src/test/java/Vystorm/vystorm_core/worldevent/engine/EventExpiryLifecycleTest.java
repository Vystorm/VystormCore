package Vystorm.vystorm_core.worldevent.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Regression: abgelaufene Events müssen genau einmal ein Ende melden, egal welcher Aufruf den Ablauf erkennt. */
class EventExpiryLifecycleTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T08:00:00Z"));
    private final List<UUID> ended = new ArrayList<>();
    private final EventRuntimeService runtime = new EventRuntimeService(clock, new Random(1L));

    EventExpiryLifecycleTest() {
        runtime.onExpired(event -> ended.add(event.instanceId()));
    }

    @Test
    void expiryDetectedByStartFiresEndedOnce() {
        ActiveEvent first = runtime.start(event("storm", "weather"), EventScope.GLOBAL, "global").event().orElseThrow();
        clock.advance(Duration.ofMinutes(11));
        assertTrue(runtime.start(event("harvest", "farming"), EventScope.GLOBAL, "global").started());
        assertEquals(List.of(first.instanceId()), ended);
        runtime.expire();
        runtime.snapshot();
        assertEquals(1, ended.size());
    }

    @Test
    void expiryDetectedByBlockedEventIdsFiresEnded() {
        EventDefinition storm = event("storm", "weather");
        ActiveEvent first = runtime.start(storm, EventScope.GLOBAL, "global").event().orElseThrow();
        clock.advance(Duration.ofMinutes(11));
        runtime.blockedEventIds(List.of(storm), EventScope.GLOBAL, "global");
        assertEquals(List.of(first.instanceId()), ended);
        assertEquals(0, runtime.expire());
        assertEquals(1, ended.size());
    }

    @Test
    void expiryDetectedBySnapshotFiresEnded() {
        ActiveEvent first = runtime.start(event("storm", "weather"), EventScope.GLOBAL, "global").event().orElseThrow();
        clock.advance(Duration.ofMinutes(11));
        assertTrue(runtime.snapshot().activeEvents().isEmpty());
        assertEquals(List.of(first.instanceId()), ended);
        runtime.expire();
        assertEquals(1, ended.size());
    }

    @Test
    void runningEventsAreNotReported() {
        runtime.start(event("storm", "weather"), EventScope.GLOBAL, "global");
        clock.advance(Duration.ofMinutes(5));
        assertEquals(0, runtime.expire());
        assertTrue(ended.isEmpty());
    }

    @Test
    void failingListenerDoesNotStopOtherExpiries() {
        List<String> seen = new ArrayList<>();
        Consumer<ActiveEvent> listener = event -> {
            seen.add(event.definition().id());
            throw new IllegalStateException("broken consumer");
        };
        runtime.onExpired(listener);
        runtime.start(event("storm", "weather"), EventScope.GLOBAL, "global");
        runtime.start(event("harvest", "farming"), EventScope.GLOBAL, "global");
        clock.advance(Duration.ofMinutes(11));
        assertEquals(2, runtime.expire());
        assertEquals(Set.of("storm", "harvest"), Set.copyOf(seen));
    }

    @Test
    void eventsThatEndedWhileOfflineAreReportedOnRestore() {
        EventDefinition storm = event("storm", "weather");
        EventDefinition harvest = event("harvest", "farming");
        EventRegistry registry = new EventRegistry();
        registry.register(storm);
        registry.register(harvest);
        ActiveEvent ended = runtime.start(storm, EventScope.GLOBAL, "global").event().orElseThrow();
        clock.advance(Duration.ofMinutes(5));
        ActiveEvent running = runtime.start(harvest, EventScope.GLOBAL, "global").event().orElseThrow();
        EventRuntimeService.RuntimeSnapshot saved = runtime.snapshot();

        clock.advance(Duration.ofMinutes(7));
        EventRuntimeService restarted = new EventRuntimeService(clock, new Random(2L));
        EventRuntimeService.RestoreResult result = restarted.restore(saved, registry);

        assertEquals(1, result.activeEvents());
        assertEquals(List.of(ended.instanceId()), result.expiredWhileOffline().stream().map(ActiveEvent::instanceId).toList());
        assertEquals(List.of(running.instanceId()), restarted.activeEvents().stream().map(ActiveEvent::instanceId).toList());
    }

    static EventDefinition event(String id, String group) {
        return new EventDefinition(id, Set.of(EventScope.GLOBAL), EventType.NEGATIVE, group, 20.0D, Set.of(group),
                Duration.ofHours(6), Duration.ofMinutes(10), Duration.ofMinutes(10), Set.of(), Set.of(), Set.of(group),
                5.0D, 0.0D, 1);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
