package Vystorm.vystorm_core.curve;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Manager view of {@link CurveRegistry}, reached through {@code api.runtime().curves()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link CurveRegistry}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface CurveRegistryInterface {
    /** The delegating default. Plugins use {@code api.runtime().curves()}. */
    CurveRegistryInterface DEFAULT = new CurveRegistryInterface() {};

    /** See {@link CurveRegistry#register}. */
    default void register(String id, Curve curve) { CurveRegistry.register(id, curve); }

    /** See {@link CurveRegistry#unregister}. */
    default void unregister(String id) { CurveRegistry.unregister(id); }

    /** See {@link CurveRegistry#unregisterPrefix}. */
    default void unregisterPrefix(String prefix) { CurveRegistry.unregisterPrefix(prefix); }

    /** See {@link CurveRegistry#get}. */
    default Optional<Curve> get(String id) { return CurveRegistry.get(id); }

    /** See {@link CurveRegistry#apply}. */
    default double apply(String id, double x, double fallback) { return CurveRegistry.apply(id, x, fallback); }

    /** See {@link CurveRegistry#ids}. */
    default Set<String> ids() { return CurveRegistry.ids(); }

    /** See {@link CurveRegistry#loadSection}. */
    default java.util.List<String> loadSection(String prefix, ConfigurationSection section) { return CurveRegistry.loadSection(prefix, section); }
}
