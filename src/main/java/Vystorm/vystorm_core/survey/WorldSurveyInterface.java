package Vystorm.vystorm_core.survey;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * The world survey, reached through {@code api.survey()}: which cells of a world are surface, which open cells are
 * enclosed rooms, how the rooms join across chunks, and the live open cells of loaded chunks. Capability
 * {@code "world-survey"} ({@code CoreApi.Capability.WORLD_SURVEY}).
 *
 * <p>Nothing runs until the Core switch {@code survey.enabled} is on or a plugin calls {@link #require(Plugin)}; until
 * then the parts answer empty ({@code cached}), fail ({@code request}, {@code start}) or count zero.
 *
 * <p>Every method has a default body that delegates to the static method of the same name of {@link WorldSurvey}.
 *
 * @since 0.68.0
 */
public interface WorldSurveyInterface {
    /** The delegating default. Plugins use {@code api.survey()}. */
    WorldSurveyInterface DEFAULT = new WorldSurveyInterface() {};

    /** Whether the survey runs (switch on or required by a plugin). */
    default boolean active() { return WorldSurvey.active(); }

    /** Whether Core's switch {@code survey.enabled} is on. */
    default boolean enabled() { return WorldSurvey.enabled(); }

    /** Switches the survey on for as long as the plugin is enabled, whatever the switch says. */
    default void require(Plugin plugin) { WorldSurvey.require(plugin); }

    /** Names of the plugins that called {@link #require(Plugin)}. */
    default java.util.Set<String> requiredBy() { return WorldSurvey.requiredBy(); }

    /** Withdraws {@link #require(Plugin)} (also done when the plugin is disabled). */
    default void release(Plugin plugin) { WorldSurvey.release(plugin); }

    default SurveyRooms rooms() { return WorldSurvey.rooms(); }

    default OpenCells openCells() { return WorldSurvey.openCells(); }

    default SurveyPrecompute precompute() { return WorldSurvey.precompute(); }

    /** The surface rule of a world (its own or the default of its environment). */
    default SurveyRule rule(World world) { return WorldSurvey.rule(world); }

    /** Sets a world's rule (kept in Core's {@code survey/rules.yml}); its records become stale. */
    default void rule(World world, SurveyRule rule) { WorldSurvey.rule(world, rule); }

    /**
     * Registers a listener; {@code serverThread} = true delivers on the server thread, false on a worker thread.
     * Closing the result removes it; it is removed with the plugin as well.
     */
    default AutoCloseable listen(Plugin plugin, SurveyListener listener, boolean serverThread) {
        return WorldSurvey.listen(plugin, listener, serverThread);
    }

    /** Something changed at a block that Core may not have seen (a plugin's own block writes): survey again. */
    default void invalidate(World world, int x, int y, int z) { WorldSurvey.invalidate(world, x, y, z); }

    /** A whole chunk changed: survey it again. */
    default void invalidateChunk(World world, int chunkX, int chunkZ) { WorldSurvey.invalidateChunk(world, chunkX, chunkZ); }
}
