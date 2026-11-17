package Vystorm.vystorm_core.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bukkit.plugin.PluginDescriptionFile;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class CoreMetricsTest {
    private static Plugin plugin(String name, String version, List<String> depend, List<String> softDepend, boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        PluginDescriptionFile meta = mock(PluginDescriptionFile.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(enabled);
        when(plugin.getDescription()).thenReturn(meta);
        when(meta.getVersion()).thenReturn(version);
        when(meta.getDepend()).thenReturn(depend);
        when(meta.getSoftDepend()).thenReturn(softDepend);
        return plugin;
    }

    @Test void countsCoreAndEveryPluginBuiltOnIt() {
        Plugin core = plugin("vystorm_core", "0.41.0", List.of(), List.of(), true);
        Plugin rails = plugin("VystormRails", "1.2.0", List.of("vystorm_core"), List.of(), true);
        Plugin trees = plugin("CustomTrees", "1.30.1", List.of(), List.of("vystorm_core"), true);
        Plugin off = plugin("VystormOff", "1.0", List.of("vystorm_core"), List.of(), false);
        Plugin other = plugin("WorldEdit", "7", List.of(), List.of(), true);
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.getPlugins()).thenReturn(new Plugin[] {core, rails, trees, off, other});
        assertEquals(Map.of("vystorm_core", "0.41.0", "VystormRails", "1.2.0", "CustomTrees", "1.30.1"), Map.copyOf(CoreMetrics.vystormPlugins(plugins, core)));
    }
}
