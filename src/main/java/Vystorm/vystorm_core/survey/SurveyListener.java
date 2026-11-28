package Vystorm.vystorm_core.survey;

import java.util.UUID;

/**
 * Told about surveys and block changes. Registered with {@code api.survey().listen(plugin, listener, serverThread)};
 * with {@code serverThread = false} the calls come on a survey worker thread. Released when the plugin is disabled.
 * Exceptions are caught and logged.
 *
 * @since 0.68.0
 */
public interface SurveyListener {
    /** A chunk was surveyed (or its stored record was confirmed after the chunk loaded). */
    default void surveyed(ChunkSurvey survey) {}

    /**
     * A block change in this chunk (or close to its border) turned an opaque cell open or the other way round. The
     * old record is stale from now on; the chunk is surveyed again a few seconds after the last change while it is
     * loaded.
     */
    default void invalidated(UUID worldId, int chunkX, int chunkZ) {}

    /** A stale chunk was surveyed again. {@code before} is the record that went stale. */
    default void changed(ChunkSurvey before, ChunkSurvey after) {}

    /**
     * A chunk loaded. With {@code serverThread = true} this is called inside the chunk load event, before the chunk
     * is sent to any player: the place to build a chunk's mask ({@link ChunkSurvey#mask()} needs no cells). The
     * record is the stored one whose stamp equals the chunk's, or null when there is none yet (no record, a stale
     * one, or its region file was still being read; then {@link #surveyed} follows when it is there).
     */
    default void loaded(UUID worldId, int chunkX, int chunkZ, ChunkSurvey surveyOrNull) {}
}
