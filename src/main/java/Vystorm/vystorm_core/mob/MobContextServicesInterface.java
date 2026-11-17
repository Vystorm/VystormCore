package Vystorm.vystorm_core.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link MobContextServices}, reached through {@code api.mobs().context()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MobContextServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MobContextServicesInterface {
    /** The delegating default. Plugins use {@code api.mobs().context()}. */
    MobContextServicesInterface DEFAULT = new MobContextServicesInterface() {};

    /** See {@link MobContextServices#register}. */
    default void register(Plugin owner, ContextProvider provider) { MobContextServices.register(owner, provider); }

    /** See {@link MobContextServices#unregisterAll}. */
    default void unregisterAll(Plugin owner) { MobContextServices.unregisterAll(owner); }

    /** See {@link MobContextServices#providers}. */
    default List<String> providers() { return MobContextServices.providers(); }

    /** See {@link MobContextServices#resolve}. */
    default MobContext resolve(MobContextQuery query) { return MobContextServices.resolve(query); }
}
