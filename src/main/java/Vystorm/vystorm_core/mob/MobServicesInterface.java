package Vystorm.vystorm_core.mob;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.main;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link MobServices}, reached through {@code api.mobs()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MobServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MobServicesInterface {
    /** The delegating default. Plugins use {@code api.mobs()}. */
    MobServicesInterface DEFAULT = new MobServicesInterface() {};

    /** See {@link MobServices#register}. */
    default void register(Plugin owner, MobService service) { MobServices.register(owner, service); }

    /** See {@link MobServices#unregister}. */
    default void unregister(Plugin owner) { MobServices.unregister(owner); }

    /** See {@link MobServices#available}. */
    default boolean available() { return MobServices.available(); }

    /** Name of the currently enabled provider for diagnostics. @since 0.26.5 */
    default Optional<String> providerName() { return MobServices.providerName(); }

    /** See {@link MobServices#service}. */
    default Optional<MobService> service() { return MobServices.service(); }

    /** See {@link MobServices#fire}. */
    default void fire(Event event) { MobServices.fire(event); }
}
