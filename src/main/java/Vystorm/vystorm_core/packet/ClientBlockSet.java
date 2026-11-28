package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * One named set of client blocks: cells that the viewer's client shows with another block than the real one, without
 * changing a real block. Opened through {@code api.packages().clientBlocks().open(..)} (one player) or
 * {@code openShared(..)} (every player near an anchor).
 * <p>
 * <b>Threads:</b> every method must be called on the server thread. <b>Inputs are copied:</b> changing a map or a
 * {@link BlockData} after the call changes nothing.
 * <p>
 * <b>Sending:</b> changes are sent at the end of the tick (Core's tick task), grouped per chunk section into one
 * multi-block packet each, nearest section to the viewer first, within the set's {@link #budget(int)} and the viewer's
 * budget (setting {@code client-blocks.cells-per-tick}, 8,192 by default). Cells of chunks the client does not hold
 * are kept and sent when the chunk arrives; cells outside the world's height or border are ignored. The budget of a
 * shared set is shared by all its viewers.
 * <p>
 * <b>Shown order:</b> where two sets of one viewer cover the same cell, the higher {@link #priority()} is shown; on equal
 * priority the newer set. Clearing or closing a set shows the next lower set's block or, if none, the real block as it
 * is at that moment.
 * <p>
 * <b>Real block changes</b> at a cell (break, place, explosions, pistons, fluids, growth, block physics, Core's bulk
 * edits) overwrite the client block in the client; Core sends the cell again two ticks later. A chunk that the server
 * sends again (the player left and came back within view distance) gets the whole set again.
 * <p>
 * <b>Collision:</b> sets are visual only by default. A set whose cells change what a player can stand on or walk into
 * must say so with {@link #collision(boolean)}. Core then logs one warning per plugin and lists the set in
 * {@code /vadmin packets}. Without it, Core does not send a solid client block over a non-solid real block inside the
 * viewer's own bounding box (it waits until the player moved out).
 * <p>
 * <b>Life:</b> a set closes with {@link #close()}, after {@link #expireAfter(long)}, when its plugin is disabled, and
 * (per-player sets) when the viewer quits, changes world or respawns. Changes to a closed set are ignored; check
 * {@link #isClosed()} and open a new one.
 *
 * @since 0.72.0
 */
public interface ClientBlockSet extends AutoCloseable {
    Plugin owner();

    /** The name given at open; a label for {@code /vadmin packets}, not a key (two sets may share a name). */
    String name();

    int priority();

    /** True for a set opened with {@code openShared}. */
    boolean shared();

    /** What {@link #collision(boolean)} declared. */
    boolean changesCollision();

    /** Adds or changes one cell. */
    ClientBlockSet set(BlockPosition cell, BlockData data);

    /**
     * Adds or changes cells. All or nothing: at most 32,768 cells per set and 131,072 per viewer (all sets together).
     *
     * @throws IllegalArgumentException when a limit would be exceeded (nothing changes)
     */
    ClientBlockSet setAll(Map<BlockPosition, BlockData> cells);

    /**
     * Replaces the whole content with {@code desired}: cells not in it are cleared, unchanged cells cost nothing.
     *
     * @throws IllegalArgumentException when a limit would be exceeded (nothing changes)
     */
    ClientBlockSet replace(Map<BlockPosition, BlockData> desired);

    /** Removes cells: the next lower set's block or the real block is shown there. */
    ClientBlockSet clear(Collection<BlockPosition> cells);

    /** Removes every cell; the set stays open. */
    ClientBlockSet clearAll();

    /** Closes the set after this many ticks (from now); 0 = never (default). */
    ClientBlockSet expireAfter(long ticks);

    /** Cells this set may send per tick (1..32,768, default 4,096); the viewer's budget caps all sets together. */
    ClientBlockSet budget(int cellsPerTick);

    /** Declares that cells of this set change what a player can stand on or walk into (see class Javadoc). */
    ClientBlockSet collision(boolean changesCollision);

    /**
     * Called (server thread) when a player clicks, starts to break or breaks a cell where Core shows him a block of this
     * set. Without a listener Core simply sends the cell again two ticks later, because the client was told the real
     * block. With a listener Core sends it again only when the listener calls {@link ClientBlockInteract#repair()};
     * otherwise, after a right click or a break (the server sent the real block), that player keeps the real block at
     * that cell until the cell changes again; after a left click or the start of breaking the client still shows the
     * client block. One click can call the listener more than once (a left click is LEFT_CLICK and START_BREAK).
     * {@code null} removes it.
     */
    ClientBlockSet onInteract(Consumer<ClientBlockInteract> listener);

    /** Cells waiting for a packet in any viewer of this set (budget, chunk not held yet, refused in the bounding box). */
    int pending();

    /** Number of cells in this set. */
    int size();

    /** Whether the set holds this cell (shown or hidden by a higher set). */
    boolean covers(BlockPosition cell);

    boolean isClosed();

    /** Closes the set: the next lower set's block or the real one is sent within the budget. Idempotent. */
    @Override void close();
}
