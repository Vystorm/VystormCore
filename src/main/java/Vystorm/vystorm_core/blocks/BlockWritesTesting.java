package Vystorm.vystorm_core.blocks;

import Vystorm.vystorm_core.blocks.internal.SharedStateTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * Bulk block writes for unit tests without a server, the same way as {@code EventStageTesting.detached()}:
 * {@link #detached(Function)} gives an own state table, a writer that writes into an in-memory world with the same
 * rules as the Bukkit writer (time checked before each cell, a cell that does not hold its {@code before} state is
 * reported as a mismatch and left alone), chunk loads that complete at once, and a manager
 * ({@link Detached#blocks()}) that hands these out and records announced events instead of firing them.
 *
 * @since 0.69.0
 */
public final class BlockWritesTesting {
    private BlockWritesTesting() {}

    /**
     * A detached set whose state table cannot parse texts ({@code id(String)} fails): enough for tests that pass
     * block data (mocks or stand-ins with {@code getAsString()} and {@code getMaterial()}).
     */
    public static Detached detached() {
        return detached(text -> { throw new IllegalStateException("No server to parse \"" + text + "\": use BlockWritesTesting.detached(parser)"); });
    }

    /** A detached set whose state table parses texts with {@code parser} (instead of {@code Bukkit.createBlockData}). */
    public static Detached detached(Function<String, BlockData> parser) { return detached(parser, BlockWritesTesting::offlineSolid); }

    /**
     * @param solidRule answers {@link StateTable#solid}; the default is a name rule, because {@code Material.isSolid()}
     *                  needs the server's registry and a failed registry start breaks later tests in the same run
     */
    public static Detached detached(Function<String, BlockData> parser, Predicate<Material> solidRule) {
        return new Detached(new SharedStateTable(parser, solidRule));
    }

    private static final String[] NOT_SOLID_ENDINGS = {"GRASS", "FLOWER", "SAPLING", "TORCH", "RAIL", "SIGN", "BUTTON",
            "PRESSURE_PLATE", "CARPET", "VINE", "VINES", "MUSHROOM", "FERN", "BUSH", "TULIP", "DAISY", "ORCHID", "ALLIUM",
            "BLUET", "POPPY", "DANDELION", "KELP", "KELP_PLANT", "LEVER", "REDSTONE_WIRE", "TRIPWIRE", "COBWEB", "ROOTS",
            "SPROUTS", "WHEAT", "CARROTS", "POTATOES", "BEETROOTS"};
    private static final java.util.Set<String> NOT_SOLID = java.util.Set.of("AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA",
            "FIRE", "SOUL_FIRE", "SNOW", "LADDER", "STRING", "SCAFFOLDING");

    /**
     * An offline stand-in for {@code Material.isSolid()}: air, liquids, fire, snow layers, plants, rails, torches,
     * signs, buttons, plates, carpets and similar are not solid; everything else is. An approximation for tests only.
     */
    static boolean offlineSolid(Material material) {
        if (material == null) return false;
        String name = material.name();
        if (NOT_SOLID.contains(name)) return false;
        for (String ending : NOT_SOLID_ENDINGS) if (name.endsWith(ending)) return false;
        return true;
    }

    /** One detached set: state table, in-memory world, writer, chunk loads and a manager over them. Not thread-safe. */
    public static final class Detached {
        private final StateTable states;
        private final Map<Long, Integer> cells = new HashMap<>();
        private final List<BlocksEditedEvent> announced = new ArrayList<>();
        private final List<String> loaded = new ArrayList<>();
        private LongSupplier clock = System::nanoTime;
        private int writes, openHolds;
        private final BlockWriter writer = new MapWriter();
        private final ChunkLoads loads = new InstantLoads();
        private final BlockWritesInterface blocks = new BlockWritesInterface() {
            @Override public StateTable states() { return states; }
            @Override public BlockWriter writer(World world) { return writer; }
            @Override public BlockWriter bukkitWriter() { return writer; }
            @Override public ChunkLoads chunks() { return loads; }
            @Override public BlocksEditedEvent announce(Plugin source, EditedChunk chunk) {
                BlocksEditedEvent event = new BlocksEditedEvent(source, chunk);
                announced.add(event);
                return event;
            }
        };

        private Detached(StateTable states) { this.states = states; }

        private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }

        public StateTable states() { return states; }
        public BlockWriter writer() { return writer; }
        public ChunkLoads chunks() { return loads; }
        /** A manager to pass where code expects {@code api.blocks()}. */
        public BlockWritesInterface blocks() { return blocks; }
        /** The clock the writer reads before each cell ({@code System::nanoTime} by default). */
        public Detached clock(LongSupplier value) { clock = Objects.requireNonNull(value, "value"); return this; }
        /** State id at the cell; cells never set are air. The world argument of the writer is ignored. */
        public int get(int x, int y, int z) { return cells.getOrDefault(key(x, y, z), StateTable.AIR); }
        public Detached set(int x, int y, int z, int state) { cells.put(key(x, y, z), state); return this; }
        public Detached set(int x, int y, int z, BlockData data) { return set(x, y, z, states.id(data)); }
        /** Number of cells the writer wrote. */
        public int writes() { return writes; }
        /** Events {@link #blocks()} announced, in order. */
        public List<BlocksEditedEvent> announced() { return List.copyOf(announced); }
        /** Chunks loaded through {@link #chunks()}, as {@code "x,z"} in order. */
        public List<String> loaded() { return List.copyOf(loaded); }
        /** Holds that are not closed yet. */
        public int openHolds() { return openHolds; }

        private final class MapWriter implements BlockWriter {
            @Override public String id() { return "bukkit"; }
            @Override public boolean direct() { return false; }
            @Override public int write(World world, ChunkCells chunk, int from, long deadlineNanos, WriteSink sink) {
                int size = chunk.size();
                if (from < 0 || from > size) throw new IndexOutOfBoundsException("from " + from + " outside 0.." + size);
                for (int i = from; i < size; i++) {
                    if (clock.getAsLong() >= deadlineNanos) return i;
                    int current = get(chunk.x(i), chunk.y(i), chunk.z(i));
                    if (current != chunk.before(i)) { sink.mismatch(i, current); continue; }
                    set(chunk.x(i), chunk.y(i), chunk.z(i), chunk.after(i));
                    writes++;
                    sink.written(i);
                }
                return size;
            }
        }

        private final class InstantLoads implements ChunkLoads {
            private int perTick = 8;
            private double syncMs = 5.0;
            @Override public CompletableFuture<ChunkHold> load(Plugin plugin, World world, int chunkX, int chunkZ, boolean generate) {
                loaded.add(chunkX + "," + chunkZ);
                openHolds++;
                AtomicBoolean closed = new AtomicBoolean();
                return CompletableFuture.completedFuture(new ChunkHold() {
                    @Override public World world() { return world; }
                    @Override public int chunkX() { return chunkX; }
                    @Override public int chunkZ() { return chunkZ; }
                    @Override public Chunk chunk() { throw new UnsupportedOperationException("A detached chunk load has no Bukkit chunk"); }
                    @Override public void close() { if (closed.compareAndSet(false, true)) openHolds--; }
                });
            }
            @Override public int requestsPerTick() { return perTick; }
            @Override public void requestsPerTick(int value) { perTick = Math.max(1, Math.min(256, value)); }
            @Override public int waiting() { return 0; }
            @Override public boolean asynchronous() { return true; }
            @Override public double syncMillisPerTick() { return syncMs; }
            @Override public void syncMillisPerTick(double value) { syncMs = Math.max(0.5, Math.min(50.0, value)); }
        }
    }
}
