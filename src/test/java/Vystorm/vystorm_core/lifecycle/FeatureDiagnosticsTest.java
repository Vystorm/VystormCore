package Vystorm.vystorm_core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeatureDiagnosticsTest {
    private final Plugin provider = plugin("Provider");

    @AfterEach
    void unregisterProvider() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            FeatureDiagnostics.unregister(provider);
        }
    }

    @Test
    void givenOneProbeThrows_whenInspecting_thenOtherProbesStillReportAndCleanupRemovesOwner() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Player target = mock(Player.class);
            FeatureDiagnostics.register(provider, "broken", player -> { throw new IllegalStateException("broken"); });
            FeatureDiagnostics.register(provider, "healthy", player ->
                    new FeatureDiagnostics.Status("feature", "Provider", FeatureDiagnostics.State.ACTIVE, "ok"));

            List<FeatureDiagnostics.Status> first = FeatureDiagnostics.inspect(target);
            assertEquals(2, first.size());
            assertEquals(FeatureDiagnostics.State.UNAVAILABLE, first.getFirst().state());
            assertEquals("{lang:vystorm_core:diagnostics.probe-failed}", first.getFirst().reason());
            assertEquals(FeatureDiagnostics.State.ACTIVE, first.get(1).state());

            FeatureDiagnostics.unregister(provider);
            assertTrue(FeatureDiagnostics.inspect(target).isEmpty(), "unregister must remove every owner probe");
        }
    }

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }
}
