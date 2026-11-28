package Vystorm.vystorm_core.blocks.internal;

import Vystorm.vystorm_core.compat.PlatformCompat;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * The server side of {@link TicketChunkLoads}: Bukkit calls plus {@code getChunkAtAsync} through
 * {@link PlatformCompat}, which exists on Paper and Purpur only. Whether it exists is looked up once by reflection, so
 * Spigot never reaches the Paper method.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class BukkitChunkSource implements TicketChunkLoads.ChunkSource {
    private static final boolean ASYNC = lookUpAsync();

    private static boolean lookUpAsync() {
        try {
            World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
            return true;
        } catch (NoSuchMethodException | SecurityException | LinkageError missing) {
            return false;
        }
    }

    @Override public boolean asyncAvailable() { return ASYNC; }
    @Override public boolean isLoaded(World world, int x, int z) { return world.isChunkLoaded(x, z); }
    @Override public boolean isGenerated(World world, int x, int z) { return world.isChunkGenerated(x, z); }
    @Override public Chunk loaded(World world, int x, int z) { return world.getChunkAt(x, z); }
    @Override public CompletableFuture<Chunk> loadAsync(World world, int x, int z, boolean generate) {
        return PlatformCompat.getChunkAtAsync(world, x, z, generate);
    }
    @Override public Chunk loadSync(World world, int x, int z) { return world.getChunkAt(x, z); }
    @Override public void addTicket(Chunk chunk, Plugin plugin) { chunk.addPluginChunkTicket(plugin); }
    @Override public void removeTicket(World world, int x, int z, Plugin plugin) {
        if (Bukkit.getWorld(world.getUID()) == null) return; // world unloaded: its tickets went with it
        world.removePluginChunkTicket(x, z, plugin);
    }

    /** The server's host: primary thread, Bukkit scheduler with Core as owner, stop flag through PlatformCompat. */
    public static TicketChunkLoads.Host host(Plugin core) {
        return new TicketChunkLoads.Host() {
            @Override public boolean serverThread() { return Bukkit.isPrimaryThread(); }
            @Override public void runOnServer(Runnable task) {
                try { Bukkit.getScheduler().runTask(core, task); }
                catch (RuntimeException notEnabled) { /* Core is disabled: the server removes the tickets on stop */ }
            }
            @Override public boolean stopping() { return PlatformCompat.isStopping(); }
            @Override public long nanos() { return System.nanoTime(); }
        };
    }
}
