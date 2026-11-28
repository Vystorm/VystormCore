package Vystorm.vystorm_core.survey;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/**
 * Live open cells of the loaded, surveyed chunks: {@code api.survey().openCells()}. Core updates them on the server
 * thread from the same block events that invalidate surveys and publishes a new {@link OpenChunk} for every change
 * (copy on write); any thread may read and keep a snapshot.
 *
 * @since 0.68.0
 */
public interface OpenCells {
    /** The open cells of a loaded, surveyed chunk. */
    Optional<OpenChunk> chunk(World world, int chunkX, int chunkZ);

    /** Whether a block is open; empty if its chunk is not tracked. */
    default Optional<Boolean> isOpen(World world, int x, int y, int z) {
        return chunk(world, x >> 4, z >> 4).map(c -> c.isOpen(x, y, z));
    }

    /** Number of tracked chunks of a world. */
    int tracked(World world);

    /**
     * The open cells of a loaded chunk, also when Core has only the stored record of it so far (a record offered at
     * chunk load carries no cells): Core then takes a snapshot of the chunk at once (an audit ahead of the sampled ones)
     * or surveys it when it has no usable record, and completes the future on the server thread when the cells are
     * published. Fails when the chunk unloads first or is not loaded. Any thread. A plugin that reveals rooms near a
     * player asks for the chunks around him, nearest first.
     *
     * @since 0.68.0
     */
    default CompletableFuture<OpenChunk> request(World world, int chunkX, int chunkZ) {
        return chunk(world, chunkX, chunkZ).map(CompletableFuture::completedFuture)
                .orElseGet(() -> CompletableFuture.failedFuture(new UnsupportedOperationException("open cells of chunk " + chunkX + "," + chunkZ + " are not tracked")));
    }
}
