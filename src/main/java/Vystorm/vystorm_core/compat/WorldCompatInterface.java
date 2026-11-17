package Vystorm.vystorm_core.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

/**
 * Manager view of {@link WorldCompat}, reached through {@code api.compat().world()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link WorldCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface WorldCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().world()}. */
    WorldCompatInterface DEFAULT = new WorldCompatInterface() {};

    /** Looks up an already loaded world by its actual dimension key, without loading a world or chunk. */
    default World getWorld(NamespacedKey key) { return WorldCompat.getWorld(key); }

    /**
     * Samples the generator biome without creating chunks. CraftBukkit's getBiome samples a loaded BIOMES chunk
     * with create=false and otherwise uses its generator's uncached noise biome; Paper has an explicit method.
     */
    default Biome computedBiome(World world, int x, int y, int z) { return WorldCompat.computedBiome(world, x, y, z); }

    /**
     * Creates a correctly keyed creator. A custom namespace must never silently become a vanilla dimension.
     * Servers with named world folders use the name for minecraft keys; custom dimensions need keyed-world API.
     */
    default WorldCreator worldCreator(NamespacedKey key) { return WorldCompat.worldCreator(key); }

    /** Changes this world's native chunk-send radius. Must be called on the server thread. */
    default void setViewDistance(World world, int distance) { WorldCompat.setViewDistance(world, distance); }

    /** Changes this world's native simulation radius. Must be called on the server thread. */
    default void setSimulationDistance(World world, int distance) { WorldCompat.setSimulationDistance(world, distance); }

    /** Uses vanilla's block-state replaceability, preserving state-dependent placement rules. */
    default boolean isReplaceable(Block block) { return WorldCompat.isReplaceable(block); }

    /** Uses the actual dimension type, including custom datapack coordinate scales. */
    default double coordinateScale(World world) { return WorldCompat.coordinateScale(world); }

    /** Server send radius, bounded by the client's requested radius on Bukkit. */
    default int sendViewDistance(org.bukkit.entity.Player player) { return WorldCompat.sendViewDistance(player); }

    /** True only after the real chunk was sent; queued chunks must not erase client previews. */
    default boolean isChunkSent(org.bukkit.entity.Player player, long key) { return WorldCompat.isChunkSent(player, key); }

    /** Schedules the actual fluid tick in the population region, without touching the live Bukkit world. */
    default void scheduleFluidUpdate(org.bukkit.generator.LimitedRegion region, int x, int y, int z) {
        WorldCompat.scheduleFluidUpdate(region, x, y, z);
    }

    /** Creates the correct debris block before applying its damage/drop settings. */
    default org.bukkit.entity.FallingBlock spawnFallingBlock(World world, org.bukkit.Location location, org.bukkit.block.data.BlockData data, java.util.function.Consumer<org.bukkit.entity.FallingBlock> configure) {
        return WorldCompat.spawnFallingBlock(world, location, data, configure);
    }

    /** Checks the unconsumed loot table without generating its contents. */
    default boolean hasLootTable(org.bukkit.block.TileState tile) { return WorldCompat.hasLootTable(tile); }

    /** An unloaded-coordinate biome sampler for worlds used as the source of a sky layer. */
    default org.bukkit.generator.BiomeProvider biomeProvider(World source) { return WorldCompat.biomeProvider(source); }
}
