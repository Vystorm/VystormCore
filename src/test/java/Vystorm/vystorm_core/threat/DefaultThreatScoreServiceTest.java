package Vystorm.vystorm_core.threat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class DefaultThreatScoreServiceTest {
    @Test
    void combinesAdditiveAndMultiplierModifiers() {
        Plugin plugin = plugin("ThreatTest");
        DefaultThreatScoreService service = new DefaultThreatScoreService();
        ThreatTarget target = ThreatTarget.player(UUID.randomUUID());

        service.addPoints(plugin, target, new NamespacedKey(plugin, "base"), 10.0D, Duration.ofMinutes(1));
        service.addPoints(plugin, target, new NamespacedKey(plugin, "event"), 5.0D, Duration.ofMinutes(1));
        service.setMultiplier(plugin, target, new NamespacedKey(plugin, "multiplier"), 1.5D, Duration.ofMinutes(1));

        ThreatSnapshot snapshot = service.snapshot(target, 0.0D, 100.0D);
        assertEquals(15.0D, snapshot.additiveTotal(), 0.0001D);
        assertEquals(1.5D, snapshot.multiplierProduct(), 0.0001D);
        assertEquals(22.5D, snapshot.effectiveThreat(), 0.0001D);
    }

    @Test
    void purgeExpiredRemovesOnlyExpiredModifiersOfAllTargets() throws Exception {
        Plugin plugin = plugin("ThreatTest");
        DefaultThreatScoreService service = new DefaultThreatScoreService();
        ThreatTarget gone = ThreatTarget.player(UUID.randomUUID());
        ThreatTarget kept = ThreatTarget.player(UUID.randomUUID());
        service.addPoints(plugin, gone, new NamespacedKey(plugin, "short"), 10.0D, Duration.ofMillis(1));
        service.addPoints(plugin, kept, new NamespacedKey(plugin, "long"), 5.0D, Duration.ofMinutes(5));
        Thread.sleep(5);
        assertEquals(1, service.purgeExpired());
        assertEquals(0, service.purgeExpired());
        assertEquals(5.0D, service.snapshot(kept, 0.0D, 100.0D).additiveTotal(), 0.0001D);
    }

    @Test
    void rejectsForeignNamespace() {
        Plugin plugin = plugin("ThreatTest");
        DefaultThreatScoreService service = new DefaultThreatScoreService();
        ThreatTarget target = ThreatTarget.player(UUID.randomUUID());
        NamespacedKey foreign = Objects.requireNonNull(NamespacedKey.fromString("foreign:modifier"));

        assertThrows(IllegalArgumentException.class, () ->
                service.addPoints(plugin, target, foreign, 10.0D, Duration.ofMinutes(1)));
    }

    @Test
    void removeOwnerClearsOnlyOwnersModifiers() {
        Plugin first = plugin("FirstPlugin");
        Plugin second = plugin("SecondPlugin");
        DefaultThreatScoreService service = new DefaultThreatScoreService();
        ThreatTarget target = ThreatTarget.player(UUID.randomUUID());

        service.addPoints(first, target, new NamespacedKey(first, "a"), 10.0D, Duration.ofMinutes(1));
        service.addPoints(second, target, new NamespacedKey(second, "b"), 7.0D, Duration.ofMinutes(1));
        assertEquals(1, service.removeOwner(first));
        assertEquals(7.0D, service.snapshot(target).effectiveThreat(), 0.0001D);
    }

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.namespace()).thenReturn(name.toLowerCase(java.util.Locale.ROOT));
        return plugin;
    }
}
