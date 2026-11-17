package Vystorm.vystorm_core.lifecycle;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link UpdateSources}, reached through {@code api.admin().updates()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link UpdateSources}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface UpdateSourcesInterface {
    /** The delegating default. Plugins use {@code api.admin().updates()}. */
    UpdateSourcesInterface DEFAULT = new UpdateSourcesInterface() {};

    /** Registers the plugin's SpigotMC resource id (the number in {@code spigotmc.org/resources/<id>}). */
    default void spigot(Plugin plugin, int resourceId) { UpdateSources.spigot(plugin, resourceId); }

    /** Removes the plugin's registration; called by Core when the plugin is disabled. */
    default void unregister(Plugin plugin) { UpdateSources.unregister(plugin); }

    /** Whether the plugin registered itself (legacy fallbacks do not count). */
    default boolean registered(Plugin plugin) { return UpdateSources.registered(plugin); }

    /** Plugin name to resource id: legacy fallbacks, overridden by self-registrations. */
    default Map<String, Integer> all() { return UpdateSources.all(); }
}
