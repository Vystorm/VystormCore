package Vystorm.vystorm_core.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class RuntimeContextHubTest {
    private static Plugin plugin(boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(enabled);
        when(plugin.getName()).thenReturn("p");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        return plugin;
    }

    private static RuntimeContextHub.Provider provider(String id, int priority, String value) {
        return new RuntimeContextHub.Provider() {
            public String id() { return id; }
            public Set<RuntimeContextHub.Channel> channels() { return Set.of(RuntimeContextHub.Channel.CUSTOM); }
            public int priority() { return priority; }
            public RuntimeContextHub.Snapshot snapshot(RuntimeContextHub.Channel channel, RuntimeContextHub.Query query) {
                return new RuntimeContextHub.Snapshot(id, channel, priority, 1L, Set.of(), Map.of("k", value), Map.of());
            }
        };
    }

    private static RuntimeContextHub.Query query() { return RuntimeContextHub.Query.world(UUID.randomUUID(), 0, 64, 0); }

    @Test
    void givenProvidersWithPriorities_whenMerged_thenHigherPriorityValueWinsAndDisabledOwnerIsSkipped() {
        RuntimeContextHub hub = RuntimeContextHub.get();
        Plugin enabled = plugin(true), disabled = plugin(false);
        String a = "ctx-test-a-" + UUID.randomUUID(), b = "ctx-test-b-" + UUID.randomUUID(), c = "ctx-test-c-" + UUID.randomUUID();
        try {
            hub.register(enabled, provider(a, 5_000, "low"));
            hub.register(enabled, provider(b, 6_000, "high"));
            hub.register(disabled, provider(c, 7_000, "disabled"));
            var merged = hub.merged(RuntimeContextHub.Channel.CUSTOM, query());
            assertEquals("high", merged.values().get("k"));
            assertFalse(merged.providers().contains(c));
            // Re-Register mit niedrigerer Priorität ändert die Reihenfolge
            hub.register(enabled, provider(b, 4_000, "high"));
            assertEquals("low", hub.merged(RuntimeContextHub.Channel.CUSTOM, query()).values().get("k"));
        } finally {
            hub.unregisterOwner(enabled);
            hub.unregisterOwner(disabled);
        }
    }

    @Test
    void givenConcurrentReRegistration_whenQueried_thenReadersNeverSeeAnEmptyList() throws Exception {
        RuntimeContextHub hub = RuntimeContextHub.get();
        Plugin owner = plugin(true);
        String id = "ctx-test-stable-" + UUID.randomUUID();
        hub.register(owner, provider(id, 0, "v"));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger misses = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Thread reader = Thread.ofPlatform().start(() -> {
            started.countDown();
            while (!stop.get()) {
                List<RuntimeContextHub.Snapshot> result = hub.query(RuntimeContextHub.Channel.CUSTOM, query());
                if (result.stream().noneMatch(s -> s.providerId().equals(id))) misses.incrementAndGet();
            }
        });
        try {
            started.await();
            String other = "ctx-test-other-" + UUID.randomUUID();
            for (int i = 0; i < 2_000; i++) hub.register(owner, provider(other, i % 3, "o"));
        } finally {
            stop.set(true);
            reader.join();
            hub.unregisterOwner(owner);
        }
        assertEquals(0, misses.get());
    }
}
