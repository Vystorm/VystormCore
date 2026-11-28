package Vystorm.vystorm_core.blocks.internal;

import Vystorm.vystorm_core.blocks.ChunkHold;
import Vystorm.vystorm_core.blocks.ChunkLoads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Core's {@link ChunkLoads}. Requests from any thread wait in one queue; {@link #tick()} runs once per server tick
 * and starts them within the budget. Ticket counts are kept per plugin and chunk, so two holds of the same chunk by
 * one plugin share one Bukkit ticket, which is removed when the last of them closes. All bookkeeping except the
 * queue happens on the server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class TicketChunkLoads implements ChunkLoads {
    /** The server calls, a seam for tests. All methods are called on the server thread. */
    public interface ChunkSource {
        /** {@code World.getChunkAtAsync} exists (Paper, Purpur). */
        boolean asyncAvailable();
        boolean isLoaded(World world, int x, int z);
        boolean isGenerated(World world, int x, int z);
        /** A chunk that {@link #isLoaded} said is loaded. */
        Chunk loaded(World world, int x, int z);
        /** Starts an asynchronous load; completes with null when {@code generate} is false and the chunk does not exist. */
        CompletableFuture<Chunk> loadAsync(World world, int x, int z, boolean generate);
        /** Synchronous load that generates a missing chunk. */
        Chunk loadSync(World world, int x, int z);
        void addTicket(Chunk chunk, Plugin plugin);
        void removeTicket(World world, int x, int z, Plugin plugin);
    }

    /** Thread, scheduler, stop flag and clock of the server, a seam for tests. */
    public interface Host {
        boolean serverThread();
        /** Runs the task on the server thread soon (next tick). */
        void runOnServer(Runnable task);
        boolean stopping();
        long nanos();
    }

    static final int DEFAULT_REQUESTS_PER_TICK = 8;
    static final double DEFAULT_SYNC_MS = 5.0;
    static final int GENERATED_PER_TICK_SYNC = 1;
    /** At most this many waiting requests are looked at per tick in synchronous mode (generation waits are skipped). */
    static final int SYNC_SCAN_PER_TICK = 512;

    private static final class Request {
        final Plugin plugin; final World world; final int x, z; final boolean generate;
        final CompletableFuture<ChunkHold> future = new CompletableFuture<>();
        Boolean exists;
        Request(Plugin plugin, World world, int x, int z, boolean generate) {
            this.plugin = plugin; this.world = world; this.x = x; this.z = z; this.generate = generate;
        }
    }

    private record TicketKey(Plugin plugin, World world, int x, int z) {}

    private static final class Ticket {
        int holds;
        boolean dead;
    }

    private final ChunkSource source;
    private final Host host;
    private final ConcurrentLinkedDeque<Request> waiting = new ConcurrentLinkedDeque<>();
    private final AtomicInteger waitingCount = new AtomicInteger();
    private final Set<Request> inFlight = new HashSet<>();
    private final Map<TicketKey, Ticket> tickets = new HashMap<>();
    private volatile int requestsPerTick = DEFAULT_REQUESTS_PER_TICK;
    private volatile double syncMillisPerTick = DEFAULT_SYNC_MS;
    private final boolean async;

    public TicketChunkLoads(ChunkSource source, Host host) {
        this.source = Objects.requireNonNull(source, "source");
        this.host = Objects.requireNonNull(host, "host");
        this.async = source.asyncAvailable();
    }

    @Override
    public CompletableFuture<ChunkHold> load(Plugin plugin, World world, int chunkX, int chunkZ, boolean generate) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(world, "world");
        Request request = new Request(plugin, world, chunkX, chunkZ, generate);
        if (host.stopping()) {
            request.future.completeExceptionally(new CancellationException("The server is stopping"));
            return request.future;
        }
        if (host.serverThread() && source.isLoaded(world, chunkX, chunkZ)) {
            finish(request, source.loaded(world, chunkX, chunkZ), null);
            return request.future;
        }
        // Spigot: decide "must be generated" now, before loads of neighbours leave half-built chunks on disk that
        // isChunkGenerated reports as existing (measured: 11 generations in one tick without this)
        if (!async && host.serverThread()) request.exists = source.isGenerated(world, chunkX, chunkZ);
        waitingCount.incrementAndGet();
        waiting.addLast(request);
        return request.future;
    }

    @Override public int requestsPerTick() { return requestsPerTick; }
    @Override public void requestsPerTick(int value) { requestsPerTick = Math.max(1, Math.min(256, value)); }
    @Override public int waiting() { return Math.max(0, waitingCount.get()); }
    @Override public boolean asynchronous() { return async; }
    @Override public double syncMillisPerTick() { return syncMillisPerTick; }
    @Override public void syncMillisPerTick(double value) {
        if (!Double.isFinite(value)) return;
        syncMillisPerTick = Math.max(0.5, Math.min(50.0, value));
    }

    /** Server thread, once per tick: starts waiting loads within the budget. */
    public void tick() {
        if (host.stopping()) { cancelAll("The server is stopping"); return; }
        if (async) tickAsync(); else tickSync();
    }

    private Request poll() {
        Request request = waiting.pollFirst();
        if (request != null) waitingCount.decrementAndGet();
        return request;
    }

    private void pushFront(Request request) {
        waitingCount.incrementAndGet();
        waiting.addFirst(request);
    }

    private void tickAsync() {
        int started = 0, budget = requestsPerTick;
        Request request;
        while ((request = poll()) != null) {
            if (request.future.isDone()) continue;
            if (source.isLoaded(request.world, request.x, request.z)) {
                finish(request, source.loaded(request.world, request.x, request.z), null);
                continue;
            }
            if (started >= budget) { pushFront(request); break; }
            started++;
            Request current = request;
            inFlight.add(current);
            CompletableFuture<Chunk> load;
            try {
                load = source.loadAsync(current.world, current.x, current.z, current.generate);
            } catch (RuntimeException | LinkageError failure) {
                inFlight.remove(current);
                current.future.completeExceptionally(failure);
                continue;
            }
            load.whenComplete((chunk, failure) -> {
                if (host.serverThread()) completeAsync(current, chunk, failure);
                else host.runOnServer(() -> completeAsync(current, chunk, failure));
            });
        }
    }

    private void completeAsync(Request request, Chunk chunk, Throwable failure) {
        inFlight.remove(request);
        if (host.stopping()) { request.future.completeExceptionally(new CancellationException("The server is stopping")); return; }
        finish(request, chunk, failure);
    }

    private void tickSync() {
        long start = host.nanos(), budget = (long) (syncMillisPerTick * 1_000_000L);
        int loads = 0, generated = 0, scanned = 0;
        // Requests made off the server thread: decide "must be generated" for as many as fit into half the slice
        // before this tick loads anything (see load()).
        int looked = 0;
        for (Request waitingRequest : waiting) {
            if (looked++ >= SYNC_SCAN_PER_TICK || host.nanos() - start >= budget / 2) break;
            if (waitingRequest.exists == null && !waitingRequest.future.isDone())
                waitingRequest.exists = source.isGenerated(waitingRequest.world, waitingRequest.x, waitingRequest.z);
        }
        List<Request> later = new ArrayList<>();
        Request request;
        while (scanned < SYNC_SCAN_PER_TICK && (request = poll()) != null) {
            scanned++;
            if (request.future.isDone()) continue;
            if (source.isLoaded(request.world, request.x, request.z)) {
                finish(request, source.loaded(request.world, request.x, request.z), null);
                continue;
            }
            if (loads > 0 && host.nanos() - start >= budget) { later.add(request); break; }
            try {
                if (request.exists == null) request.exists = source.isGenerated(request.world, request.x, request.z);
                if (!request.exists && !request.generate) { finish(request, null, null); continue; }
                // a chunk next to one this service generated may be half built: Spigot reports it as existing,
                // but loading it finishes its generation, so it counts as a generation too
                boolean generation = !request.exists || nextToGenerated(request);
                if (generation) {
                    if (generated >= GENERATED_PER_TICK_SYNC) { later.add(request); continue; }
                    generated++;
                }
                Chunk chunk = source.loadSync(request.world, request.x, request.z);
                loads++;
                if (generation) rememberGenerated(request);
                finish(request, chunk, null);
            } catch (RuntimeException | LinkageError failure) {
                request.future.completeExceptionally(failure);
            }
        }
        for (int i = later.size() - 1; i >= 0; i--) pushFront(later.get(i));
    }

    /** Chunks this service generated synchronously, per world, the newest {@link #GENERATED_MEMORY} of them. */
    static final int GENERATED_MEMORY = 4096;
    private final Map<World, java.util.LinkedHashSet<Long>> generatedHere = new java.util.WeakHashMap<>(); // unloaded worlds drop out

    private static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    private boolean nextToGenerated(Request request) {
        java.util.LinkedHashSet<Long> keys = generatedHere.get(request.world);
        if (keys == null || keys.isEmpty()) return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if ((dx != 0 || dz != 0) && keys.contains(chunkKey(request.x + dx, request.z + dz))) return true;
        return false;
    }

    private void rememberGenerated(Request request) {
        java.util.LinkedHashSet<Long> keys = generatedHere.computeIfAbsent(request.world, w -> new java.util.LinkedHashSet<>());
        keys.add(chunkKey(request.x, request.z));
        if (keys.size() > GENERATED_MEMORY) { var oldest = keys.iterator(); oldest.next(); oldest.remove(); }
    }

    /** Server thread: adds the ticket and completes the future, or fails it. */
    private void finish(Request request, Chunk chunk, Throwable failure) {
        if (request.future.isDone()) return;
        if (failure != null) { request.future.completeExceptionally(failure); return; }
        if (chunk == null) {
            request.future.completeExceptionally(new NoSuchElementException("Chunk " + request.x + ", " + request.z + " of "
                    + request.world.getName() + " does not exist and generate is false"));
            return;
        }
        TicketKey key = new TicketKey(request.plugin, request.world, request.x, request.z);
        Ticket ticket = tickets.get(key);
        if (ticket == null || ticket.dead) {
            ticket = new Ticket();
            tickets.put(key, ticket);
            source.addTicket(chunk, request.plugin);
        }
        ticket.holds++;
        Hold hold = new Hold(request, chunk, key, ticket);
        if (!request.future.complete(hold)) hold.close();
    }

    private void releaseHold(TicketKey key, Ticket ticket, World world) {
        if (ticket.dead) return;
        if (--ticket.holds > 0) return;
        ticket.dead = true;
        if (tickets.get(key) == ticket) tickets.remove(key);
        source.removeTicket(world, key.x(), key.z(), key.plugin());
    }

    /** Server thread: fails the plugin's waiting and running loads and removes its tickets (plugin disabled). */
    public void release(Plugin plugin) {
        if (plugin == null) return;
        CancellationException cancelled = new CancellationException(plugin.getName() + " was disabled");
        for (Request request : List.copyOf(waiting)) {
            if (request.plugin != plugin) continue;
            if (waiting.remove(request)) waitingCount.decrementAndGet();
            request.future.completeExceptionally(cancelled);
        }
        for (Request request : List.copyOf(inFlight)) {
            if (request.plugin != plugin) continue;
            inFlight.remove(request);
            request.future.completeExceptionally(cancelled);
        }
        for (var entry : List.copyOf(tickets.entrySet())) {
            if (entry.getKey().plugin() != plugin) continue;
            Ticket ticket = entry.getValue();
            ticket.dead = true;
            tickets.remove(entry.getKey());
            World world = entry.getKey().world();
            if (world != null) source.removeTicket(world, entry.getKey().x(), entry.getKey().z(), plugin);
        }
    }

    /** Server thread: fails everything that waits and removes every ticket (Core disables). */
    public void shutdown() {
        cancelAll("Vystorm Core is stopping");
        for (var entry : List.copyOf(tickets.entrySet())) {
            entry.getValue().dead = true;
            World world = entry.getKey().world();
            if (world != null) source.removeTicket(world, entry.getKey().x(), entry.getKey().z(), entry.getKey().plugin());
        }
        tickets.clear();
    }



    private void cancelAll(String reason) {
        CancellationException cancelled = new CancellationException(reason);
        Request request;
        while ((request = poll()) != null) request.future.completeExceptionally(cancelled);
        for (Request running : List.copyOf(inFlight)) running.future.completeExceptionally(cancelled);
        inFlight.clear();
    }

    private final class Hold implements ChunkHold {
        private final World world; private final int x, z;
        private final Chunk chunk;
        private final TicketKey key;
        private final Ticket ticket;
        private final AtomicBoolean closed = new AtomicBoolean();

        Hold(Request request, Chunk chunk, TicketKey key, Ticket ticket) {
            this.world = request.world; this.x = request.x; this.z = request.z;
            this.chunk = chunk; this.key = key; this.ticket = ticket;
        }

        @Override public World world() { return world; }
        @Override public int chunkX() { return x; }
        @Override public int chunkZ() { return z; }

        @Override public Chunk chunk() {
            if (closed.get()) throw new IllegalStateException("The hold of chunk " + x + ", " + z + " is closed");
            if (!host.serverThread()) throw new IllegalStateException("ChunkHold.chunk() is server thread only");
            return chunk;
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (host.serverThread()) releaseHold(key, ticket, world);
            else host.runOnServer(() -> releaseHold(key, ticket, world));
        }

        @Override public String toString() { return "ChunkHold[" + world.getName() + " " + x + ", " + z + (closed.get() ? ", closed]" : "]"); }
    }
}
