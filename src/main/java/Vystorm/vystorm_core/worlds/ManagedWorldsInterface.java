package Vystorm.vystorm_core.worlds;

import Vystorm.vystorm_core.worlds.internal.WorldsPlatform;
import java.util.List;
import java.util.Optional;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ManagedWorlds}, reached through {@code api.worldGenerator().worlds()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ManagedWorlds}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ManagedWorldsInterface {
    /** The delegating default. Plugins use {@code api.worldGenerator().worlds()}. */
    ManagedWorldsInterface DEFAULT = new ManagedWorldsInterface() {};

    /** See {@link ManagedWorlds#register}. */
    default ManagedWorld register(Plugin owner, WorldSpec spec) { return ManagedWorlds.register(owner, spec); }

    /** See {@link ManagedWorlds#rebuild}. */
    default ManagedWorld rebuild(Plugin owner, WorldSpec spec) { return ManagedWorlds.rebuild(owner, spec); }

    /** See {@link ManagedWorlds#reconfigure}. */
    default ManagedWorld reconfigure(Plugin owner, WorldSpec spec) { return ManagedWorlds.reconfigure(owner, spec); }

    /** See {@link ManagedWorlds#unregister}. */
    default boolean unregister(Plugin owner, String id) { return ManagedWorlds.unregister(owner, id); }

    /** See {@link ManagedWorlds#release}. */
    default void release(Plugin owner) { ManagedWorlds.release(owner); }

    /** See {@link ManagedWorlds#get}. */
    default Optional<ManagedWorld> get(String id) { return ManagedWorlds.get(id); }

    /** See {@link ManagedWorlds#of}. */
    default Optional<ManagedWorld> of(World world) { return ManagedWorlds.of(world); }

    /** See {@link ManagedWorlds#managed}. */
    default boolean managed(World world) { return ManagedWorlds.managed(world); }

    /** See {@link ManagedWorlds#all}. */
    default List<ManagedWorld> all() { return ManagedWorlds.all(); }

    /** See {@link ManagedWorlds#visits}. */
    default VisitService visits() { return ManagedWorlds.visits(); }
}
