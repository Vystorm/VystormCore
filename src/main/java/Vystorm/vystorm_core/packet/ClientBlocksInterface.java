package Vystorm.vystorm_core.packet;

import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ClientBlocks}, reached through {@code api.packages().clientBlocks()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClientBlocks} and {@link ClientBlockSet}. An own
 * implementation of the owning manager may return a replacement that overrides single methods (tests, special
 * servers).
 *
 * @since 0.72.0
 */
public interface ClientBlocksInterface {
    /** The delegating default. Plugins use {@code api.packages().clientBlocks()}. */
    ClientBlocksInterface DEFAULT = new ClientBlocksInterface() {};

    /** See {@link ClientBlocks#supported}. */
    default boolean supported() { return ClientBlocks.supported(); }

    /** See {@link ClientBlocks#open}. Server thread. */
    default ClientBlockSet open(Plugin owner, Player viewer, String name, int priority) {
        return ClientBlocks.open(owner, viewer, name, priority);
    }

    /** See {@link ClientBlocks#openShared}. Server thread. */
    default ClientBlockSet openShared(Plugin owner, Location anchor, int range, String name, int priority) {
        return ClientBlocks.openShared(owner, anchor, range, name, priority);
    }

    /** See {@link ClientBlocks#shown}. Server thread. */
    default Optional<BlockData> shown(Player viewer, int x, int y, int z) { return ClientBlocks.shown(viewer, x, y, z); }

    /** See {@link ClientBlocks#sets}. Server thread. */
    default List<ClientBlockSet> sets(Player viewer) { return ClientBlocks.sets(viewer); }

    /** See {@link ClientBlocks#release}. Server thread. */
    default void release(Plugin owner) { ClientBlocks.release(owner); }

    /** See {@link ClientBlocks#stats}. Any thread. */
    default ClientBlocksStats stats() { return ClientBlocks.stats(); }
}
