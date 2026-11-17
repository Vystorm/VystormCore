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
 * World operations shared by Paper and CraftBukkit consumers. @since 0.51.0
 * <p>Preferred access: {@code api.compat().world()}.
 */
public final class WorldCompat {
    private WorldCompat() {}

    /** Looks up an already loaded world by its actual dimension key, without loading a world or chunk. */
    public static World getWorld(NamespacedKey key) {
        Objects.requireNonNull(key, "key");
        for (World world : Bukkit.getWorlds()) if (key.equals(world.getKey())) return world;
        return null;
    }

    /**
     * Samples the generator biome without creating chunks. CraftBukkit's getBiome samples a loaded BIOMES chunk
     * with create=false and otherwise uses its generator's uncached noise biome; Paper has an explicit method.
     */
    public static Biome computedBiome(World world, int x, int y, int z) {
        Objects.requireNonNull(world, "world");
        Method paper = method(World.class, "getComputedBiome", int.class, int.class, int.class);
        return paper == null ? world.getBiome(x, y, z) : (Biome) invoke(paper, world, x, y, z);
    }

    /**
     * Creates a correctly keyed creator. A custom namespace must never silently become a vanilla dimension.
     * Servers with named world folders use the name for minecraft keys; custom dimensions need keyed-world API.
     */
    public static WorldCreator worldCreator(NamespacedKey key) {
        Objects.requireNonNull(key, "key");
        Method keyed = method(WorldCreator.class, "ofKey", NamespacedKey.class);
        if (keyed != null) return (WorldCreator) invoke(keyed, null, key);
        if (!NamespacedKey.MINECRAFT.equals(key.getNamespace()))
            throw new UnsupportedOperationException("This server cannot create a dimension with key " + key);
        return new WorldCreator(key.getKey());
    }

    /** Changes this world's native chunk-send radius. Must be called on the server thread. */
    public static void setViewDistance(World world, int distance) { setDistance(world, "setViewDistance", distance); }

    /** Changes this world's native simulation radius. Must be called on the server thread. */
    public static void setSimulationDistance(World world, int distance) { setDistance(world, "setSimulationDistance", distance); }

    /** Uses vanilla's block-state replaceability, preserving state-dependent placement rules. */
    public static boolean isReplaceable(Block block) {
        Objects.requireNonNull(block, "block");
        Method paper = method(Block.class, "isReplaceable");
        if (paper != null) return (Boolean) invoke(paper, block);
        Object data = block.getBlockData();
        Method stateMethod = method(data.getClass(), "getState");
        if (stateMethod != null) {
            Object state = invoke(stateMethod, data);
            Method replaceable = method(state.getClass(), "canBeReplaced");
            if (replaceable != null) return (Boolean) invoke(replaceable, state);
        }
        org.bukkit.Tag<org.bukkit.Material> tag = Bukkit.getTag(org.bukkit.Tag.REGISTRY_BLOCKS,
                NamespacedKey.minecraft("replaceable"), org.bukkit.Material.class);
        if (tag != null) return tag.isTagged(block.getType());
        throw new IllegalStateException("The server does not expose vanilla block replaceability");
    }

    /** Uses the actual dimension type, including custom datapack coordinate scales. */
    public static double coordinateScale(World world) {
        Method api = method(World.class, "getCoordinateScale");
        if (api != null) return ((Number) invoke(api, world)).doubleValue();
        Object dimension = invokeRequired(invokeRequired(world, "getHandle"), "dimensionType");
        return ((Number) invokeRequired(dimension, "coordinateScale")).doubleValue();
    }

    /** Server send radius, bounded by the client's requested radius on Bukkit. */
    public static int sendViewDistance(org.bukkit.entity.Player player) {
        Method api = method(org.bukkit.entity.Player.class, "getSendViewDistance");
        if (api != null) return ((Number) invoke(api, player)).intValue();
        return Math.min(player.getWorld().getViewDistance(), Math.max(2, player.getClientViewDistance()));
    }

    /** True only after the real chunk was sent; queued chunks must not erase client previews. */
    public static boolean isChunkSent(org.bukkit.entity.Player player, long key) {
        Method api = method(org.bukkit.entity.Player.class, "isChunkSent", long.class);
        if (api != null) return (Boolean) invoke(api, player, key);
        Object handle = invokeRequired(player, "getHandle");
        Object view = invokeRequired(handle, "getChunkTrackingView");
        boolean tracked = (Boolean) invoke(method(view.getClass(), "contains", int.class, int.class), view,
                (int) key, (int) (key >> 32));
        if (!tracked) return false;
        try {
            Object connection = handle.getClass().getField("connection").get(handle);
            Object sender = connection.getClass().getField("chunkSender").get(connection);
            return !(Boolean) invoke(method(sender.getClass(), "isPending", long.class), sender, key);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native chunk-send state unavailable", failure);
        }
    }

    /** Schedules the actual fluid tick in the population region, without touching the live Bukkit world. */
    public static void scheduleFluidUpdate(org.bukkit.generator.LimitedRegion region, int x, int y, int z) {
        Method api = method(org.bukkit.generator.LimitedRegion.class, "scheduleFluidUpdate", int.class, int.class, int.class);
        if (api != null) { invoke(api, region, x, y, z); return; }
        if (!region.isInRegion(x, y, z)) throw new IllegalArgumentException("Fluid tick outside population region");
        Object access = invokeRequired(region, "getHandle");
        Object state = invokeRequired(region.getBlockData(x, y, z), "getState");
        Object fluid = invokeRequired(invokeRequired(state, "getFluidState"), "getType");
        try {
            Class<?> positionType = Class.forName("net.minecraft.core.BlockPos");
            Object position = positionType.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            Method tick = java.util.Arrays.stream(access.getClass().getMethods())
                    .filter(m -> m.getName().equals("scheduleTick") && m.getParameterCount() == 3
                            && m.getParameterTypes()[0] == positionType
                            && m.getParameterTypes()[1].isInstance(fluid)).findFirst().orElseThrow();
            invoke(tick, access, position, fluid, 1);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native population fluid scheduling unavailable", failure);
        }
    }

    /** Creates the correct debris block before applying its damage/drop settings. */
    public static org.bukkit.entity.FallingBlock spawnFallingBlock(World world, org.bukkit.Location location,
            org.bukkit.block.data.BlockData data, java.util.function.Consumer<org.bukkit.entity.FallingBlock> configure) {
        org.bukkit.entity.FallingBlock falling = world.spawnFallingBlock(location, data);
        configure.accept(falling);
        return falling;
    }

    /** Checks the unconsumed loot table without generating its contents. */
    public static boolean hasLootTable(org.bukkit.block.TileState tile) {
        if (tile instanceof org.bukkit.loot.Lootable loot) return loot.getLootTable() != null;
        Method snapshot = method(tile.getClass(), "getSnapshot");
        if (snapshot == null) throw new IllegalStateException("Tile loot snapshot unavailable");
        Object state = invoke(snapshot, tile);
        Method loot = method(state.getClass(), "getLootTable");
        return loot != null && invoke(loot, state) != null;
    }

    /** An unloaded-coordinate biome sampler for worlds used as the source of a sky layer. */
    public static org.bukkit.generator.BiomeProvider biomeProvider(World source) {
        Method paper = method(World.class, "vanillaBiomeProvider");
        if (paper != null) return (org.bukkit.generator.BiomeProvider) invoke(paper, source);
        return new org.bukkit.generator.BiomeProvider() {
            @Override public Biome getBiome(org.bukkit.generator.WorldInfo info, int x, int y, int z) {
                return computedBiome(source, x, y, z);
            }
            @Override public java.util.List<Biome> getBiomes(org.bukkit.generator.WorldInfo info) {
                java.util.ArrayList<Biome> biomes = new java.util.ArrayList<>();
                Bukkit.getRegistry(Biome.class).forEach(biomes::add);
                return java.util.List.copyOf(biomes);
            }
        };
    }

    private static void setDistance(World world, String name, int distance) {
        Objects.requireNonNull(world, "world");
        if (distance < 2 || distance > 32) throw new IllegalArgumentException("Distance must be between 2 and 32");
        Method api = method(World.class, name, int.class);
        if (api != null) { invoke(api, world, distance); return; }
        // CraftBukkit does not expose these per-world methods, but its chunk source owns the same real settings.
        Object handle = invokeRequired(world, "getHandle");
        Object chunks = invokeRequired(handle, "getChunkSource");
        Method setter = method(chunks.getClass(), name, int.class);
        if (setter == null) throw new IllegalStateException("Native world distance method unavailable: " + name);
        invoke(setter, chunks, distance);
    }

    private static Object invokeRequired(Object receiver, String name) {
        Method method = method(receiver.getClass(), name);
        if (method == null) throw new IllegalStateException("Native world method unavailable: " + name);
        return invoke(method, receiver);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try { return type.getMethod(name, parameters); }
        catch (NoSuchMethodException absent) { return null; }
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("World operation failed: " + method.getName(), cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("World operation inaccessible: " + method.getName(), failure);
        }
    }
}
