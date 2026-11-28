package Vystorm.vystorm_core.survey;

import java.util.Objects;
import java.util.UUID;

/**
 * A room of one chunk: the world, the chunk and the room's local id in that chunk ({@code 1 ..
 * ChunkSurvey.rooms().size()}). Local ids are only valid for the survey they came from; after a block change the
 * chunk is surveyed again and its ids may differ ({@link SurveyListener#changed}).
 *
 * @since 0.68.0
 */
public record RoomId(UUID worldId, int chunkX, int chunkZ, int local) {
    public RoomId {
        Objects.requireNonNull(worldId, "worldId");
        if (local < 1) throw new IllegalArgumentException("local room ids start at 1: " + local);
    }

    /** The chunk key of {@link #chunkX()}/{@link #chunkZ()} (x in the low 32 bits). */
    public long chunkKey() { return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32); }
}
