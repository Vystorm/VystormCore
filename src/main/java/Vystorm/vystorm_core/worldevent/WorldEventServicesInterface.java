package Vystorm.vystorm_core.worldevent;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.main;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link WorldEventServices}, reached through {@code api.events().services()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link WorldEventServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface WorldEventServicesInterface {
    /** The delegating default. Plugins use {@code api.events().services()}. */
    WorldEventServicesInterface DEFAULT = new WorldEventServicesInterface() {};

    /** See {@link WorldEventServices#register}.
     * @deprecated Use {@code api.events().domain(owner, id).source(source)}. */
    @Deprecated(since = "0.64.0")
    default void register(Plugin owner, WorldEventSource source) { WorldEventServices.register(owner, source); }

    /** See {@link WorldEventServices#unregister}.
     * @deprecated Use {@code api.events().release(owner)}. */
    @Deprecated(since = "0.64.0")
    default void unregister(Plugin owner) { WorldEventServices.unregister(owner); }

    /** See {@link WorldEventServices#available}. */
    default boolean available() { return WorldEventServices.available(); }

    /** See {@link WorldEventServices#active}. */
    default List<WorldEventView> active() { return WorldEventServices.active(); }

    /** See {@link WorldEventServices#activeAt}. */
    default List<WorldEventView> activeAt(Location location) { return WorldEventServices.activeAt(location); }

    /** See {@link WorldEventServices#activeAt}. */
    default List<WorldEventView> activeAt(UUID worldId, int x, int y, int z) { return WorldEventServices.activeAt(worldId, x, y, z); }

    /** See {@link WorldEventServices#activeAt}. */
    default boolean activeAt(Location location, String eventIdOrTag) { return WorldEventServices.activeAt(location, eventIdOrTag); }

    /** See {@link WorldEventServices#modifier}. */
    default double modifier(String key, Location location) { return WorldEventServices.modifier(key, location); }

    /** See {@link WorldEventServices#modifier}. */
    default double modifier(String key, UUID worldId, int x, int y, int z) { return WorldEventServices.modifier(key, worldId, x, y, z); }

    /** See {@link WorldEventServices#fireStarted}. */
    default void fireStarted(WorldEventView event) { WorldEventServices.fireStarted(event); }

    /** See {@link WorldEventServices#fireEnded}. */
    default void fireEnded(WorldEventView event, WorldEventEndedEvent.Reason reason) { WorldEventServices.fireEnded(event, reason); }
}
