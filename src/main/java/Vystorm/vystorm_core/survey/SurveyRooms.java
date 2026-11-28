package Vystorm.vystorm_core.survey;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/**
 * Rooms and cell kinds of surveyed chunks: {@code api.survey().rooms()}.
 *
 * <p>Thread rules: every method may be called from any thread. The cache methods never block and never start a
 * survey; the future methods never run a survey on the server thread (chunk snapshots are taken there within a tick
 * budget, the survey runs on Core's worker threads) and complete on a worker thread, so a caller that touches the
 * world afterwards must hop back to the server thread itself.
 *
 * @since 0.68.0
 */
public interface SurveyRooms {
    /** The survey of a chunk if Core has it in memory and it is not stale; empty otherwise. */
    Optional<ChunkSurvey> cached(World world, int chunkX, int chunkZ);

    /**
     * The survey of a chunk: from memory, from Core's files, or surveyed now (the chunk and its eight neighbours must
     * be generated; a chunk that is not loaded is loaded for the snapshot without generating anything). Fails with
     * {@link IllegalStateException} while the survey is off or when a chunk is not generated.
     */
    CompletableFuture<ChunkSurvey> request(World world, int chunkX, int chunkZ);

    /** The room of a block from the cache (the chunk must be loaded and surveyed). */
    Optional<RoomId> roomAt(World world, int x, int y, int z);

    /** The kind of a block from the cache (the chunk must be loaded and surveyed). */
    Optional<CellKind> kindAt(World world, int x, int y, int z);

    /** Rooms of the four neighbour chunks that share border cells with this room, as far as they are cached. */
    Set<RoomId> adjacent(RoomId room);

    /**
     * The rooms joined with this one across chunk borders, following at most {@code maxChunks} chunks (requests
     * chunks that are not cached). In real worlds one component can span thousands of chunks: choose the limit after
     * what the answer is worth.
     */
    CompletableFuture<RoomComponent> component(RoomId room, int maxChunks);

    /**
     * True if the room's component reaches a surface cell within 256 chunks; false if it is sealed or no contact was
     * found within that limit (use {@link #component} to tell those apart).
     */
    CompletableFuture<Boolean> isSkyConnected(RoomId room);
}
