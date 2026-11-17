package Vystorm.vystorm_core.threat;

import Vystorm.vystorm_core.main;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ThreatServices}, reached through {@code api.runtime().threat()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ThreatServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ThreatServicesInterface {
    /** The delegating default. Plugins use {@code api.runtime().threat()}. */
    ThreatServicesInterface DEFAULT = new ThreatServicesInterface() {};

    /** See {@link ThreatServices#get}. */
    default ThreatScoreService get() { return ThreatServices.get(); }
}
