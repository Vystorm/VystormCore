package Vystorm.vystorm_core.worldevent.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EventRuntimeServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void givenSameCompatibilityGroupInDifferentRegionsWhenStartingThenBothCanRun() {
        EventRuntimeService runtime = new EventRuntimeService(clock, new Random(1L));
        EventDefinition first = event("wolf_pressure", Set.of(EventScope.REGIONAL), "wildlife");
        EventDefinition second = event("boar_pressure", Set.of(EventScope.REGIONAL), "wildlife");

        assertTrue(runtime.start(first, EventScope.REGIONAL, "region_a").started());
        assertTrue(runtime.start(second, EventScope.REGIONAL, "region_b").started());
    }

    @Test
    void givenSameCompatibilityGroupInSameRegionWhenStartingThenSecondIsBlocked() {
        EventRuntimeService runtime = new EventRuntimeService(clock, new Random(1L));
        EventDefinition first = event("wolf_pressure", Set.of(EventScope.REGIONAL), "wildlife");
        EventDefinition second = event("boar_pressure", Set.of(EventScope.REGIONAL), "wildlife");

        assertTrue(runtime.start(first, EventScope.REGIONAL, "region_a").started());
        assertFalse(runtime.start(second, EventScope.REGIONAL, "region_a").started());
    }

    @Test
    void givenGlobalConflictWhenRegionalEventStartsThenGlobalEventBlocksIt() {
        EventRuntimeService runtime = new EventRuntimeService(clock, new Random(1L));
        EventDefinition global = event("world_hunger", Set.of(EventScope.GLOBAL), "wildlife");
        EventDefinition regional = event("hungry_wolves", Set.of(EventScope.REGIONAL), "wildlife");

        assertTrue(runtime.start(global, EventScope.GLOBAL, "global").started());
        assertFalse(runtime.start(regional, EventScope.REGIONAL, "region_a").started());
    }

    @Test
    void givenStoppedEventStillOnCooldownWhenRestartingThenStartIsBlocked() {
        EventRuntimeService runtime = new EventRuntimeService(clock, new Random(1L));
        EventDefinition event = event("zombie_uprising", Set.of(EventScope.GLOBAL), "undead");
        ActiveEvent active = runtime.start(event, EventScope.GLOBAL, "global").event().orElseThrow();

        assertTrue(runtime.stop(active.instanceId()));
        assertFalse(runtime.start(event, EventScope.GLOBAL, "global").started());
    }

    private static EventDefinition event(String id, Set<EventScope> scopes, String group) {
        return new EventDefinition(
                id,
                scopes,
                EventType.NEGATIVE,
                group,
                20.0D,
                Set.of(group, "combat"),
                Duration.ofHours(6),
                Duration.ofMinutes(10),
                Duration.ofMinutes(20),
                Set.of(),
                Set.of(),
                Set.of(group),
                5.0D,
                0.0D,
                1);
    }
}
