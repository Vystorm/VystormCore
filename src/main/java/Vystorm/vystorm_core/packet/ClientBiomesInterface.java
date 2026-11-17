package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.packet.internal.BiomePackets;
import java.util.*;
import Vystorm.vystorm_core.visual.BlockPosition;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manager view of {@link ClientBiomes}, reached through {@code api.packages().clientBiomes()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClientBiomes}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ClientBiomesInterface {
    /** The delegating default. Plugins use {@code api.packages().clientBiomes()}. */
    ClientBiomesInterface DEFAULT = new ClientBiomesInterface() {};

    /** See {@link ClientBiomes#supported}. */
    default boolean supported() { return ClientBiomes.supported(); }

    /** Start a view, or retain the identical existing view. One owner per player. Returns false on unsupported runtime. */
    default boolean snow(Plugin owner, Player viewer, Set<String> excludedBiomes) { return ClientBiomes.snow(owner, viewer, excludedBiomes); }

    /** Replace a spatial overlay (<=4096 four-block-aligned quart cells, <=16 chunks).
     * Composes over snow views, takes destination biome keys, never edits server biomes.
     * Vanilla quart resolution/blending can tint adjacent blocks. @since 0.36.0 */
    default boolean project(Plugin owner, Player viewer, Map<BlockPosition, NamespacedKey> cells) { return ClientBiomes.project(owner, viewer, cells); }

    /** Restore the snowy base view if present, otherwise real biomes. @since 0.36.0 */
    default void clearProjection(Plugin owner, Player viewer) { ClientBiomes.clearProjection(owner, viewer); }

    /** Restore current real biome data to the viewer and release the view. */
    default void clear(Plugin owner, Player viewer) { ClientBiomes.clear(owner, viewer); }

    /** See {@link ClientBiomes#release}. */
    default void release(Plugin owner) { ClientBiomes.release(owner); }
}
