package Vystorm.vystorm_core.blocks;

import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * A loaded chunk held by a plugin chunk ticket ({@link ChunkLoads#load}). Close it as soon as the chunk is no longer
 * needed; Core removes the tickets of a plugin that is disabled without closing them.
 *
 * @since 0.69.0
 */
public interface ChunkHold extends AutoCloseable {
    World world();
    int chunkX();
    int chunkZ();

    /** The chunk. Server thread only. @throws IllegalStateException off the server thread or after close */
    Chunk chunk();

    /** Removes the plugin ticket (on the server thread; hops there when called elsewhere). A second call does nothing. */
    @Override
    void close();
}
