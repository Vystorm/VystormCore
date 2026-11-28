package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.packet.internal.blocks.ClientBlocksRuntime;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Client blocks: per-player (or per-area) sets of cells that clients show with another block than the real one, sent
 * as ordinary block-change packets, one multi-block packet per chunk section. Works on every server Core runs on
 * (Paper, Purpur and Spigot 1.20.6 to 26.x) without a packet handler. See {@link ClientBlockSet} for sending, shown
 * order, repairs, collision and clean-up.
 * <p>
 * <b>Threads:</b> every method except {@link #stats()} and {@link #supported()} must be called on the server thread.
 * <p>
 * <b>Limits:</b> 32,768 cells per set, 131,072 cells and 64 sets per viewer.
 * <p>
 * Preferred access: {@code api.packages().clientBlocks()}.
 *
 * @since 0.72.0
 */
public final class ClientBlocks {
    private ClientBlocks() {}

    /** True on every server Core runs on (Bukkit block-change API). Any thread. */
    public static boolean supported() { return true; }

    /**
     * Opens a named set for one viewer. Several sets per viewer and per plugin are allowed; where two sets name the
     * same cell, the higher priority is shown (equal: the newer set). The set closes when the viewer quits, changes
     * world or respawns.
     *
     * @throws IllegalStateException when the owner is disabled, the viewer is offline or already has 64 sets
     */
    public static ClientBlockSet open(Plugin owner, Player viewer, String name, int priority) {
        return ClientBlocksRuntime.get().open(owner, viewer, name, priority);
    }

    /**
     * Opens a set shown to every player who is (or comes) within {@code range} blocks (1..256) of {@code anchor} in its
     * world: shared fake blocks such as borders or lamps. Core checks who is in range twice a second; a player who
     * leaves the range or the world gets the real blocks back. A player at a limit (64 sets, 131,072 cells) does not
     * get the set.
     *
     * @throws IllegalStateException when the owner is disabled or the anchor's world is not loaded
     */
    public static ClientBlockSet openShared(Plugin owner, Location anchor, int range, String name, int priority) {
        return ClientBlocksRuntime.get().openShared(owner, anchor, range, name, priority);
    }

    /** What Core sent to this viewer at this cell and has not restored; empty when the client has the real block. */
    public static Optional<BlockData> shown(Player viewer, int x, int y, int z) {
        return ClientBlocksRuntime.get().shown(viewer, x, y, z);
    }

    /** Open sets of a viewer (all plugins), in shown order: highest priority first, newer first on equal priority. */
    public static List<ClientBlockSet> sets(Player viewer) { return ClientBlocksRuntime.get().sets(viewer); }

    /** Closes every set of the owner (Core also does this when the plugin is disabled). */
    public static void release(Plugin owner) { ClientBlocksRuntime.get().release(owner); }

    /** Counters for diagnostics. Any thread (off the server thread: a snapshot at most 20 ticks old). */
    public static ClientBlocksStats stats() { return ClientBlocksRuntime.get().stats(); }

    /** Core lifecycle only: starts the tick task and the listeners. */
    public static void initialize(Plugin core) { ClientBlocksRuntime.get().start(core); }

    /** Core lifecycle only: restores every client block that is still shown and stops. */
    public static void shutdown() { ClientBlocksRuntime.shutdownInstance(); }
}
