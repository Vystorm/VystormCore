package Vystorm.vystorm_core.worldevent.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEngineTypesTest {
    @Test
    void definitionNormalizesMetadataAndRejectsInvalidValues() {
        EventDefinition definition = definition(" Storm Event ", 2.0D);
        assertEquals("storm_event", definition.id());
        assertEquals("weather", definition.category());
        assertEquals(Set.of("storm", "global"), definition.tags());

        assertThrows(IllegalArgumentException.class, () -> definition("bad", -1.0D));
        assertThrows(IllegalArgumentException.class, () -> definition("bad", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new EventDefinition(
                "bad", Set.of(), EventType.MIXED, "weather", 1.0D, Set.of("tag"), Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Set.of(), Set.of(), Set.of("weather"), 0.0D, 0.0D, 1));
    }

    @Test
    void registryRejectsDuplicatesAndAtomicallyReplacesLoadedDefinitions() {
        EventRegistry live = new EventRegistry();
        EventDefinition kept = definition("kept", 1.0D);
        live.register(definition("old", 1.0D));
        live.register(kept);
        assertThrows(IllegalArgumentException.class, () -> live.register(kept));

        EventRegistry loaded = new EventRegistry();
        loaded.register(kept);
        loaded.register(definition("new", 1.0D));
        live.replaceAll(loaded);

        assertEquals(Set.of("kept", "new"), live.snapshot().keySet());
        assertTrue(live.find(" OLD ").isEmpty());
    }

    @Test
    void lifecycleRegistryContainsFailuresAndCanUnregisterListeners() {
        List<String> calls = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        EventLifecycleRegistry registry = new EventLifecycleRegistry((id, failure) -> failures.add(id));
        registry.register(new EventLifecycleListener() {
            @Override public String id() { return "BROKEN"; }
            @Override public void onStarted(ActiveEvent event) { throw new IllegalStateException("failure"); }
        });
        registry.register(new EventLifecycleListener() {
            @Override public String id() { return "observer"; }
            @Override public void onStarted(ActiveEvent event) { calls.add(event.instanceId().toString()); }
        });

        ActiveEvent active = new ActiveEvent(UUID.randomUUID(), definition("storm", 1.0D), EventScope.GLOBAL,
                "global", Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        registry.fireStarted(active);

        assertEquals(List.of("BROKEN"), failures); // Diagnostics preserve the provider-supplied ID.
        assertEquals(List.of(active.instanceId().toString()), calls);
        assertTrue(registry.unregister(" OBSERVER "));
        assertEquals(1, registry.all().size());
    }

    private static EventDefinition definition(String id, double weight) {
        return new EventDefinition(id, Set.of(EventScope.GLOBAL), EventType.MIXED, " Weather ", weight,
                Set.of("storm", "global"), Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Set.of(), Set.of(), Set.of("weather"), 0.0D, 0.0D, 1);
    }
}
