package Vystorm.vystorm_core.lifecycle;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Where each plugin's updates are published. Plugins register themselves in {@code onEnable}; the {@link UpdateChecker}
 * then asks SpigotMC for their current version. Registrations are removed when the plugin is disabled.
 *
 * <pre>{@code
 * try {
 *     if (CoreApi.has(CoreApi.Capability.UPDATE_SOURCES)) UpdateSources.spigot(this, 140049);
 * } catch (LinkageError olderCore) {}
 * }</pre>
 *
 * Thread-safe.
 *
 * <p>Preferred access: {@code api.admin().updates()}.
 *
 * @since 0.40.2
 */
public final class UpdateSources {
    /**
     * Plugin versions released before 0.40.2 do not register themselves yet; their resource ids are kept here as a
     * fallback only. Do not add new entries: new plugins call {@link #spigot(Plugin, int)}.
     */
    static final Map<String, Integer> LEGACY = Map.ofEntries(
            Map.entry("vystorm_core", 139848), Map.entry("CustomTrees", 139850), Map.entry("XPVaultEconomy", 139852),
            Map.entry("BlockIntegrations", 139853), Map.entry("VystormPortals", 139879), Map.entry("VystormVeins", 139880),
            Map.entry("WinterIsComing", 140367), Map.entry("VystormInsomnia", 140006), Map.entry("VystormGallery", 140049),
            Map.entry("VystormPaddock", 140050), Map.entry("VystormSnapshot", 140051), Map.entry("VystormBoards", 140052),
            Map.entry("VystormEstates", 140053), Map.entry("VystormRails", 140076));

    private static final Map<String, Integer> REGISTERED = new ConcurrentHashMap<>();

    private UpdateSources() {}

    /** Registers the plugin's SpigotMC resource id (the number in {@code spigotmc.org/resources/<id>}). */
    public static void spigot(Plugin plugin, int resourceId) {
        if (plugin == null) throw new IllegalArgumentException("plugin is null");
        if (resourceId <= 0) throw new IllegalArgumentException("invalid SpigotMC resource id " + resourceId);
        REGISTERED.put(plugin.getName(), resourceId);
    }

    /** Removes the plugin's registration; called by Core when the plugin is disabled. */
    public static void unregister(Plugin plugin) {
        if (plugin != null) REGISTERED.remove(plugin.getName());
    }

    /** Whether the plugin registered itself (legacy fallbacks do not count). */
    public static boolean registered(Plugin plugin) { return plugin != null && REGISTERED.containsKey(plugin.getName()); }

    /** Plugin name to resource id: legacy fallbacks, overridden by self-registrations. */
    public static Map<String, Integer> all() {
        Map<String, Integer> all = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        all.putAll(LEGACY);
        all.putAll(REGISTERED);
        return all;
    }
}
