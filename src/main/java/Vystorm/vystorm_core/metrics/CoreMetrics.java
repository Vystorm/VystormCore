package Vystorm.vystorm_core.metrics;

import java.util.Map;
import java.util.TreeMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Anonymous usage statistics through bStats, sent only by Core: which Vystorm plugins (every plugin that depends on
 * Core) run on how many servers, and in which versions. Server owners opt out in {@code plugins/bStats/config.yml}
 * or with {@code metrics: false} in Core's config.yml.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CoreMetrics {
    /** bStats service id of "Vystorm Core" (bstats.org/plugin/bukkit/Vystorm%20Core/34998); 0 keeps metrics off. */
    static final int BSTATS_ID = 34998;

    private static Metrics metrics;

    private CoreMetrics() {}

    public static void start(JavaPlugin core) {
        stop();
        if (BSTATS_ID <= 0 || !core.getConfig().getBoolean("metrics", true)) return;
        PluginManager plugins = core.getServer().getPluginManager();
        metrics = new Metrics(core, BSTATS_ID);
        metrics.addCustomChart(new Metrics.AdvancedPie("vystorm_plugins", () -> {
            Map<String, Integer> installed = new TreeMap<>();
            for (String name : vystormPlugins(plugins, core).keySet()) installed.put(name, 1);
            return installed;
        }));
        metrics.addCustomChart(new Metrics.DrilldownPie("vystorm_versions", () -> {
            Map<String, Map<String, Integer>> versions = new TreeMap<>();
            vystormPlugins(plugins, core).forEach((name, version) -> versions.put(name, Map.of(version, 1)));
            return versions;
        }));
        metrics.addCustomChart(new Metrics.SimplePie("vystorm_plugin_count",
                () -> Integer.toString(vystormPlugins(plugins, core).size())));
    }

    public static void stop() {
        if (metrics != null) metrics.shutdown();
        metrics = null;
    }

    /** Core and every enabled plugin that (soft-)depends on it: name to version. */
    static Map<String, String> vystormPlugins(PluginManager plugins, Plugin core) {
        Map<String, String> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Plugin plugin : plugins.getPlugins()) {
            if (!plugin.isEnabled()) continue;
            var meta = plugin.getDescription();
            if (plugin == core || meta.getDepend().contains(core.getName()) || meta.getSoftDepend().contains(core.getName()))
                found.put(plugin.getName(), meta.getVersion());
        }
        return found;
    }
}
