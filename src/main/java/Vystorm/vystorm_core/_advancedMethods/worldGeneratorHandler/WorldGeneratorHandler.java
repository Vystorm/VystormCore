package Vystorm.vystorm_core._advancedMethods.worldGeneratorHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.world.WorldEnvironmentRegistry;
import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import java.util.*;

/** Registry changes and world lifecycle run on the server thread. Generators contain immutable state. */
public class WorldGeneratorHandler implements WorldGeneratorInterface {
    public record Layer(Material material, int height) {
        public Layer {
            Objects.requireNonNull(material, "material");
            if (!material.isBlock() || !material.isSolid() || height <= 0) {
                throw new IllegalArgumentException("Flat layers require a solid block and positive height");
            }
        }
    }
    private final Map<String, ChunkGenerator> generators = new HashMap<>();

    private String key(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Generator name is required");
        return name.toLowerCase(Locale.ROOT);
    }

    @Override public void register(String name, ChunkGenerator generator) {
        String key = key(name);
        ChunkGenerator value = Objects.requireNonNull(generator);
        if (generators.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("Generator already registered: " + name);
        }
        WorldEnvironmentRegistry.get().generatorRegistered(name, value);
        DebugMessages.mid("[WorldGeneratorHandler] Generator registered; name=" + name);
    }
    @Override public ChunkGenerator get(String name) { return generators.get(key(name)); }
    @Override public boolean unregister(String name) {
        boolean removed = generators.remove(key(name)) != null;
        if (removed) {
            WorldEnvironmentRegistry.get().generatorUnregistered(name);
            DebugMessages.mid("[WorldGeneratorHandler] Generator unregistered; name=" + name);
        }
        return removed;
    }
    @Override public ChunkGenerator voidGenerator() { return new FlatGenerator(0, List.of()); }
    @Override public ChunkGenerator flatGenerator(int bottomY, List<Layer> layers) {
        List<Layer> copy = List.copyOf(layers);
        if (copy.isEmpty()) throw new IllegalArgumentException("At least one layer is required; use voidGenerator for empty worlds");
        return new FlatGenerator(bottomY, copy);
    }

    @SuppressWarnings("deprecation")
    @Override public World create(WorldCreator creator, String generatorName) {
        requireServerThread();
        Objects.requireNonNull(creator, "creator");
        ChunkGenerator generator = Objects.requireNonNull(get(generatorName), "Unknown generator: " + generatorName);
        if (Bukkit.getWorld(creator.name()) != null) throw new IllegalStateException("World is already loaded: " + creator.name());
        World world = new WorldCreator(creator.name()).copy(creator).generator(generator).createWorld();
        if (world == null) {
            DebugMessages.high("[WorldGeneratorHandler] World creation failed; name=" + creator.name());
            throw new IllegalStateException("World creation failed: " + creator.name());
        }
        DebugMessages.mid("[WorldGeneratorHandler] World created/loaded; name=" + world.getName()
                + "; profile=" + detect(world).id());
        return world;
    }

    @SuppressWarnings("deprecation")
    @Override public boolean unload(World world, boolean save) {
        requireServerThread();
        Objects.requireNonNull(world, "world");
        if (!world.getPlayers().isEmpty()) return false;
        WorldEnvironmentRegistry.get().clearExplicitProfile(world);
        boolean unloaded = Bukkit.unloadWorld(world, save);
        DebugMessages.mid("[WorldGeneratorHandler] World unload; success=" + unloaded + "; name=" + world.getName());
        return unloaded;
    }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World lifecycle requires the server thread");
    }

    private static final class FlatGenerator extends ChunkGenerator {
        private final int bottomY;
        private final List<Layer> layers;
        private final long topY;
        private FlatGenerator(int bottomY, List<Layer> layers) {
            this.bottomY = bottomY; this.layers = layers;
            topY = (long) bottomY + layers.stream().mapToLong(Layer::height).sum();
        }
        @Override public void generateNoise(WorldInfo world, Random random, int chunkX, int chunkZ, ChunkData data) {
            long y = bottomY;
            for (Layer layer : layers) {
                long next = y + layer.height();
                int start = (int) Math.max(data.getMinHeight(), Math.min(data.getMaxHeight(), y));
                int end = (int) Math.max(data.getMinHeight(), Math.min(data.getMaxHeight(), next));
                if (start < end) data.setRegion(0, start, 0, 16, end, 16, layer.material());
                y = next;
            }
        }
        @Override public int getBaseHeight(WorldInfo world, Random random, int x, int z, HeightMap map) {
            if (layers.isEmpty() || topY <= world.getMinHeight() || bottomY >= world.getMaxHeight()) return world.getMinHeight();
            return (int) Math.min(topY, world.getMaxHeight());
        }
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }
}
