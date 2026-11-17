package Vystorm.vystorm_core._advancedMethods.worldGeneratorHandler;

import Vystorm.vystorm_core.world.WorldEnvironmentRegistry;
import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.util.List;

public interface WorldGeneratorInterface {
    void register(String name, ChunkGenerator generator);
    ChunkGenerator get(String name);
    boolean unregister(String name);
    ChunkGenerator voidGenerator();
    ChunkGenerator flatGenerator(int bottomY, List<WorldGeneratorHandler.Layer> layers);
    /** @deprecated Use {@code api.worldGenerator().worlds().register(owner, spec)}. */
    @Deprecated(since = "0.64.0")
    World create(WorldCreator creator, String generatorName);
    /** @deprecated Use {@code api.worldGenerator().worlds().unregister(owner, id)}. */
    @Deprecated(since = "0.64.0")
    boolean unload(World world, boolean save);

    /** Shared semantic world detection for vanilla and arbitrary custom generators. */
    default WorldEnvironmentRegistry.WorldProfile detect(World world) {
        return WorldEnvironmentRegistry.get().detect(world);
    }

    /** Register a no-chunk-load detector for a custom world generator/dimension. */
    default WorldEnvironmentRegistry.Registration registerDetector(Plugin owner, WorldEnvironmentRegistry.Detector detector) {
        return WorldEnvironmentRegistry.get().register(owner, detector);
    }

    default boolean unregisterDetector(java.util.UUID registrationId) {
        return WorldEnvironmentRegistry.get().unregister(registrationId);
    }

    default int unregisterDetectors(Plugin owner) {
        return WorldEnvironmentRegistry.get().unregisterOwner(owner);
    }

    default void setWorldProfile(Plugin owner, World world, WorldEnvironmentRegistry.WorldProfile profile) {
        WorldEnvironmentRegistry.get().setExplicitProfile(owner, world, profile);
    }

    default void clearWorldProfile(World world) {
        WorldEnvironmentRegistry.get().clearExplicitProfile(world);
    }

    /** Plugin worlds with rebuild on a changed layout, and visits ({@code worlds.ManagedWorlds}). @since 0.64.0 */
    default Vystorm.vystorm_core.worlds.ManagedWorldsInterface worlds() { return Vystorm.vystorm_core.worlds.ManagedWorldsInterface.DEFAULT; }

    /** The world environment registry behind {@link #detect} ({@code WorldEnvironmentRegistry.get()}). @since 0.64.0 */
    default WorldEnvironmentRegistry environments() { return WorldEnvironmentRegistry.get(); }
}
