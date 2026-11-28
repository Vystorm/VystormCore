package Vystorm.vystorm_core.blocks;

import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Chunk loading for bulk edits ({@code api.blocks().chunks()}): every load holds the chunk with a plugin chunk ticket
 * until the returned {@link ChunkHold} is closed, and new loads start within a budget per tick for all plugins
 * together.
 * <p>
 * Paper and Purpur ({@link #asynchronous()} true): {@code getChunkAtAsync} started on the server thread, at most
 * {@link #requestsPerTick()} new requests per tick. Spigot (false): synchronous {@code getChunkAt} inside a time slice
 * of {@link #syncMillisPerTick()} per tick (at least one chunk per tick) and never more than one chunk that has to be
 * generated per tick. A chunk that is already loaded completes at once when asked on the server thread, otherwise in
 * the next tick, and uses no budget. Futures complete on the server thread. While the server stops, waiting loads
 * fail with {@link java.util.concurrent.CancellationException}; when a plugin is disabled its waiting loads fail the
 * same way and its tickets are removed.
 *
 * @since 0.69.0
 */
public interface ChunkLoads {
    /**
     * Loads the chunk (with {@code generate}: also generates it when it never existed) and holds it with a plugin
     * ticket of {@code plugin}. Any thread. With {@code generate == false} and a chunk that does not exist, the future
     * fails with {@link java.util.NoSuchElementException}.
     */
    CompletableFuture<ChunkHold> load(Plugin plugin, World world, int chunkX, int chunkZ, boolean generate);

    /** New asynchronous requests started per tick for all plugins together (default 8). */
    int requestsPerTick();

    /** Sets {@link #requestsPerTick()}, clamped to 1..256. */
    void requestsPerTick(int value);

    /** Loads waiting for a slot, all plugins. */
    int waiting();

    /** True where the server has asynchronous chunk loading (Paper, Purpur); false on Spigot. */
    boolean asynchronous();

    /** Time slice per tick for synchronous loads on Spigot in milliseconds (default 5). */
    double syncMillisPerTick();

    /** Sets {@link #syncMillisPerTick()}, clamped to 0.5..50. */
    void syncMillisPerTick(double value);
}
