package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.packet.internal.ChunkPackets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import Vystorm.vystorm_core.packet.ClientChunks.*;

/**
 * Manager view of {@link ClientChunks}, reached through {@code api.packages().clientChunks()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClientChunks}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ClientChunksInterface {
    /** The delegating default. Plugins use {@code api.packages().clientChunks()}. */
    ClientChunksInterface DEFAULT = new ClientChunksInterface() {};

    /** True when this server can send client-only chunks (adapter built and a test chunk encodes). */
    default boolean supported() { return ClientChunks.supported(); }

    /** Why {@link #supported()} is false; empty when supported. */
    default Optional<String> unsupportedReason() { return ClientChunks.unsupportedReason(); }

    /**
     * Builds the packet of a draft. Any thread, but {@link #supported()} must have returned true once (on the server
     * thread) before; empty otherwise or when building fails (reported once a minute in the debug log).
     */
    default Optional<Prepared> prepare(Draft draft) { return ClientChunks.prepare(draft); }

    /**
     * Sends a client-only chunk to a player. Refused (false) when unsupported, the owner is disabled, the player is in
     * another world, the server already sent the real chunk to that player, or another plugin shows a client-only
     * chunk there.
     */
    default boolean send(Plugin owner, Player viewer, Prepared chunk) { return ClientChunks.send(owner, viewer, chunk); }

    /** Removes a client-only chunk of this owner from the player's client; false if it shows none there (or the real one). */
    default boolean forget(Plugin owner, Player viewer, int chunkX, int chunkZ) { return ClientChunks.forget(owner, viewer, chunkX, chunkZ); }

    /** Removes every client-only chunk of the owner from one player (all players with {@code viewer == null}). */
    default void forgetAll(Plugin owner, Player viewer) { ClientChunks.forgetAll(owner, viewer); }

    /** Chunk keys ({@code x & 0xFFFFFFFF | z << 32}, like {@code Chunk.getChunkKey}) the owner shows to the player. */
    default Set<Long> shown(Plugin owner, Player viewer) { return ClientChunks.shown(owner, viewer); }

    /** Whether any plugin shows a client-only chunk to the player at that position (as far as Core knows). */
    default boolean showing(Player viewer, int chunkX, int chunkZ) { return ClientChunks.showing(viewer, chunkX, chunkZ); }

    /** Listener for real chunks replacing the owner's client-only ones (one per plugin; null removes it). */
    default void onReplaced(Plugin owner, Replaced listener) { ClientChunks.onReplaced(owner, listener); }

    /** Forgets every client-only chunk of the plugin and its listener (also done when it disables). */
    default void release(Plugin owner) { ClientChunks.release(owner); }

    /** See {@link ClientChunks#stats}. */
    default Stats stats() { return ClientChunks.stats(); }

    /** chunk radius the vanilla client keeps around its view centre for a server view distance */
    default int clientRange(int viewDistance) { return ClientChunks.clientRange(viewDistance); }
}
