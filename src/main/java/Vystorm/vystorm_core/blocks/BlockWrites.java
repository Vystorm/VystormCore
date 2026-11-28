package Vystorm.vystorm_core.blocks;

import Vystorm.vystorm_core.blocks.internal.BukkitBlockWriter;
import Vystorm.vystorm_core.blocks.internal.BukkitChunkSource;
import Vystorm.vystorm_core.blocks.internal.SharedStateTable;
import Vystorm.vystorm_core.blocks.internal.TicketChunkLoads;
import Vystorm.vystorm_core.main;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Static entry of the bulk block writes ({@code api.blocks()} is the manager view, {@link BlockWritesInterface}).
 * Thread rules are named on each method.
 *
 * @since 0.69.0
 */
public final class BlockWrites {
    private static final WriterReport REPORT = new WriterReport("bukkit", List.of("bukkit"),
            "Core 0.69.0 has only the Bukkit writer (setBlockData without physics).");
    private static final StateTable STATES = new SharedStateTable(Bukkit::createBlockData);
    private static final BlockWriter BUKKIT = new BukkitBlockWriter(STATES, System::nanoTime);

    private BlockWrites() {}

    /** The shared state table of this server run. Any thread. */
    public static StateTable states() { return STATES; }

    /**
     * The writer Core picks for this world: the Bukkit writer in 0.69.0; a later Core may hand out a direct writer once
     * its self-test passed on this server. Any thread; the writer itself runs on the server thread.
     */
    public static BlockWriter writer(World world) {
        java.util.Objects.requireNonNull(world, "world");
        return BUKKIT;
    }

    /** Always the Bukkit writer ({@code setBlockData(data, false)}). Any thread. */
    public static BlockWriter bukkitWriter() { return BUKKIT; }

    /** Which writers exist and why the selected one is used. Any thread. */
    public static WriterReport writerReport() { return REPORT; }

    /**
     * Fires a {@link BlocksEditedEvent} for the written cells of one chunk on the server thread and returns it. Called
     * on the server thread, the listeners have run when this returns; called on any other thread, the event is fired
     * from a task of Core in the next tick.
     */
    public static BlocksEditedEvent announce(Plugin source, EditedChunk chunk) {
        BlocksEditedEvent event = new BlocksEditedEvent(source, chunk);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Plugin core = main.getPlugin();
            if (core == null || !core.isEnabled()) throw new IllegalStateException("Vystorm Core is not enabled");
            Bukkit.getScheduler().runTask(core, () -> Bukkit.getPluginManager().callEvent(event));
        }
        return event;
    }

    private static TicketChunkLoads loads;
    private static BukkitTask ticker;

    /**
     * Chunk loading with plugin tickets and a budget per tick, shared by all plugins (see {@link ChunkLoads}). Nothing
     * runs until the first call; from then on one task of Core starts the waiting loads every tick. Any thread.
     *
     * @throws IllegalStateException when Vystorm Core is not enabled
     */
    public static synchronized ChunkLoads chunks() {
        if (loads != null) return loads;
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled()) throw new IllegalStateException("Vystorm Core is not enabled");
        TicketChunkLoads created = new TicketChunkLoads(new BukkitChunkSource(), BukkitChunkSource.host(core));
        ticker = Bukkit.getScheduler().runTaskTimer(core, created::tick, 1L, 1L);
        loads = created;
        return created;
    }

    /**
     * Core internal: fails the plugin's waiting chunk loads and removes its tickets (plugin disabled). Server thread.
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    public static void release(Plugin plugin) {
        TicketChunkLoads current;
        synchronized (BlockWrites.class) { current = loads; }
        if (current != null) current.release(plugin);
    }

    /**
     * Core internal: stops the chunk loads (Core disables). Server thread.
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    public static void shutdown() {
        TicketChunkLoads current;
        BukkitTask task;
        synchronized (BlockWrites.class) { current = loads; task = ticker; loads = null; ticker = null; }
        if (task != null) task.cancel();
        if (current != null) current.shutdown();
    }
}
