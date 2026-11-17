package Vystorm.vystorm_core.lifecycle;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ProviderFailures}, reached through {@code api.debug().failures()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ProviderFailures}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ProviderFailuresInterface {
    /** The delegating default. Plugins use {@code api.debug().failures()}. */
    ProviderFailuresInterface DEFAULT = new ProviderFailuresInterface() {};

    /** See {@link ProviderFailures#report}. */
    default void report(String registry, String provider, String operation, Throwable failure) {
        ProviderFailures.report(registry, provider, operation, failure);
    }

    /** See {@link ProviderFailures#replaced}. */
    default void replaced(String registry, String key, Plugin previousOwner, Plugin newOwner) {
        ProviderFailures.replaced(registry, key, previousOwner, newOwner);
    }
}
