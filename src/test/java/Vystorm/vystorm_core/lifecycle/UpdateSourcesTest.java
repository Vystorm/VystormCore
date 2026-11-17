package Vystorm.vystorm_core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class UpdateSourcesTest {
    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }

    @Test void pluginsRegisterAndUnregisterThemselves() {
        Plugin plugin = plugin("SelfRegisteringTest");
        assertFalse(UpdateSources.all().containsKey("SelfRegisteringTest"));
        UpdateSources.spigot(plugin, 4242);
        assertTrue(UpdateSources.registered(plugin));
        assertEquals(4242, UpdateSources.all().get("selfregisteringtest"));
        UpdateSources.unregister(plugin);
        assertFalse(UpdateSources.registered(plugin));
        assertFalse(UpdateSources.all().containsKey("SelfRegisteringTest"));
    }

    @Test void selfRegistrationOverridesLegacyFallback() {
        assertEquals(139850, UpdateSources.all().get("CustomTrees"));
        Plugin trees = plugin("CustomTrees");
        UpdateSources.spigot(trees, 1);
        assertEquals(1, UpdateSources.all().get("CustomTrees"));
        UpdateSources.unregister(trees);
        assertEquals(139850, UpdateSources.all().get("CustomTrees"));
    }

    @Test void rejectsInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> UpdateSources.spigot(plugin("X"), 0));
        assertThrows(IllegalArgumentException.class, () -> UpdateSources.spigot(null, 5));
    }
}
