package Vystorm.vystorm_core.mob;

import Vystorm.vystorm_core.party.PartyServices;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link RelationServices}, reached through {@code api.mobs().relations()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link RelationServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface RelationServicesInterface {
    /** The delegating default. Plugins use {@code api.mobs().relations()}. */
    RelationServicesInterface DEFAULT = new RelationServicesInterface() {};

    /** See {@link RelationServices#get}. */
    default RelationService get() { return RelationServices.get(); }

    /** See {@link RelationServices#register}. */
    default void register(Plugin owner, RelationProvider provider) { RelationServices.register(owner, provider); }

    /** See {@link RelationServices#unregisterAll}. */
    default void unregisterAll(Plugin owner) { RelationServices.unregisterAll(owner); }

    /** See {@link RelationServices#providers}. */
    default List<String> providers() { return RelationServices.providers(); }

    /** See {@link RelationServices#fallback}. */
    default Relation fallback(EntityRef source, EntityRef target) { return RelationServices.fallback(source, target); }
}
