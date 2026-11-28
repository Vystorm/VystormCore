package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.schedule.TaskGroup;
import Vystorm.vystorm_core.survey.CellKind;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.JobOptions;
import Vystorm.vystorm_core.survey.OpenCells;
import Vystorm.vystorm_core.survey.OpenChunk;
import Vystorm.vystorm_core.survey.RoomComponent;
import Vystorm.vystorm_core.survey.RoomId;
import Vystorm.vystorm_core.survey.SkyContact;
import Vystorm.vystorm_core.survey.SurveyJob;
import Vystorm.vystorm_core.survey.SurveyListener;
import Vystorm.vystorm_core.survey.SurveyPrecompute;
import Vystorm.vystorm_core.survey.SurveyRooms;
import Vystorm.vystorm_core.survey.SurveyRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The running world survey: worker pool, file thread, snapshot pump, records, open cells, block watcher, jobs and
 * listeners. Created by {@code WorldSurvey} when the switch is on or a plugin requires it, stopped when neither holds.
 *
 * <p>Threads: snapshots, chunk persistent data, chunk tickets and the open-cell updates run on the server thread
 * (the {@link #tick()} timer); surveys and classification on the workers; region files on the file thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SurveyRuntime {
    static final int DEBOUNCE_TICKS = 100;
    static final long FLUSH_TICKS = 100;
    static final long CLASS_CACHE_MILLIS = 30_000;

    final Plugin core;
    final Path root;
    final NamespacedKey stampKey;
    final BlockClasses blocks;
    private final TaskGroup tasks;
    private final ExecutorService workers;
    private final ExecutorService files;
    final int poolSize;
    private final Map<UUID, WorldState> worlds = new ConcurrentHashMap<>();
    private final ArrayDeque<SnapshotRequest> queue = new ArrayDeque<>();
    private final List<Registration> listeners = new CopyOnWriteArrayList<>();
    final Map<UUID, PrecomputeJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<Long>> pendingTouches = new ConcurrentHashMap<>();
    private final Map<String, SurveyRule> rules = new ConcurrentHashMap<>();
    private SurveyWatcher watcher;
    private final SurveyBossBars bars;
    private volatile int snapshotsPerTick = 9;
    private volatile double millisPerTick = 3.0;
    private volatile int classCacheSize = 512;
    private volatile boolean logChanges;
    private volatile LoadGate.AuditPolicy audit = LoadGate.AuditPolicy.parse("sample", 0.1);
    private volatile int auditPerTick = 2;
    private volatile int autoPerSecond = 4;
    /** Chunk loads: record offered in the load event, a moment later (region read), stale, without record, not yet. */
    final AtomicLong offeredAtLoad = new AtomicLong();
    final AtomicLong offeredLate = new AtomicLong();
    final AtomicLong staleAtLoad = new AtomicLong();
    final AtomicLong loadedWithoutRecord = new AtomicLong();
    final AtomicLong notYet = new AtomicLong();
    final AtomicLong lookups = new AtomicLong();
    final AtomicLong lookupNanos = new AtomicLong();
    final AtomicLong audited = new AtomicLong();
    final AtomicLong auditFindings = new AtomicLong();
    /** Audits sent by a block event in a chunk whose record had no cells yet, and how many of them found a change. */
    final AtomicLong touchAudits = new AtomicLong();
    final AtomicLong touchFindings = new AtomicLong();
    final AtomicLong autoSurveys = new AtomicLong();
    /** Loads inside the area of a finished job, and how many of them had their record in the load event. */
    final AtomicLong loadsInArea = new AtomicLong();
    final AtomicLong offeredInArea = new AtomicLong();

    private boolean inJobArea(WorldState ws, int cx, int cz) {
        PrecomputeJob job = jobs.get(ws.id);
        return job != null && job.state() == SurveyJob.State.FINISHED && job.contains(cx, cz);
    }
    /** Time from a chunk load to its region file being read (chunks that waited for it). */
    final AtomicLong lateNanos = new AtomicLong();
    final AtomicLong lateCount = new AtomicLong();
    private long tick;
    private volatile boolean stopped;
    // statistics
    final AtomicLong surveys = new AtomicLong();
    final AtomicLong surveyNanos = new AtomicLong();
    final AtomicLong snapshots = new AtomicLong();
    final AtomicLong pumpNanos = new AtomicLong();
    /** Longest pump of one tick, the snapshots classified on workers and their time, the engine time, chunk loads. */
    final AtomicLong pumpMaxNanos = new AtomicLong();
    final AtomicLong classified = new AtomicLong();
    final AtomicLong classifyNanos = new AtomicLong();
    final AtomicLong engineNanos = new AtomicLong();
    final AtomicLong assembleNanos = new AtomicLong();
    /** CPU time of the workers' surveys (against wall time: how long they waited for a core). */
    final AtomicLong cpuNanos = new AtomicLong();
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    final AtomicLong encodeNanos = new AtomicLong();
    final AtomicLong chunkLoads = new AtomicLong();
    final AtomicLong pumpSkips = new AtomicLong();
    private long lastTickStart;
    /** Server-thread time of the survey: the longest own tick, time inside chunk load calls, and the slowest ticks. */
    final AtomicLong ownTickMaxNanos = new AtomicLong();
    final AtomicLong loadCallNanos = new AtomicLong();
    final AtomicLong loadCallMaxNanos = new AtomicLong();
    private long lastOwnNanos;
    /** Garbage collection time (ms) that fell into the survey's own tick, and that of the longest own tick. */
    private long lastOwnGcMillis;
    private volatile long ownTickMaxGcMillis;
    private int lastTickLoads;
    /**
     * Chunk load calls may block the server thread (measured: up to 220 ms for one call that generates a chunk on
     * Purpur 26.2). At most 8 calls per tick, 1 while generating, and none for a second after a call took more than
     * 50 ms.
     */
    private long loadBackoffUntil;
    final AtomicLong loadBackoffs = new AtomicLong();

    boolean mayLoad(boolean generate) {
        return tick >= loadBackoffUntil && lastTickLoads < (generate ? 1 : 8);
    }

    void loadTook(long nanos) {
        if (nanos > 50_000_000L) { loadBackoffUntil = tick + 20; loadBackoffs.incrementAndGet(); }
    }
    private final java.util.List<long[]> slowTicks = new java.util.ArrayList<>();
    final AtomicLong storedBytes = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    final AtomicInteger inFlight = new AtomicInteger();

    public SurveyRuntime(Plugin core, ConfigurationSection settings) {
        this.core = core;
        this.root = core.getDataFolder().toPath().resolve("survey");
        this.stampKey = new NamespacedKey(core, "survey_stamp");
        this.blocks = BlockClasses.build();
        this.tasks = TaskGroup.of(core, "world-survey");
        int workerSetting = settings == null ? 0 : settings.getInt("workers", 0);
        int cores = Runtime.getRuntime().availableProcessors();
        this.poolSize = workerSetting > 0 ? Math.min(32, workerSetting) : Math.max(1, Math.min(4, cores / 4));
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "Vystorm Survey #" + n.incrementAndGet());
            t.setDaemon(true);
            // below the server thread, but not the lowest: with the lowest priority a survey took 31 ms of wall time
            // on the test server (no generation), with this one 11 ms (9 ms of CPU time)
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.files = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Vystorm Survey Files");
            t.setDaemon(true);
            return t;
        });
        this.bars = new SurveyBossBars(this);
        configure(settings);
    }

    /** Reads the settings (start and reload). */
    public void configure(ConfigurationSection settings) {
        if (settings == null) return;
        snapshotsPerTick = Math.max(1, Math.min(256, settings.getInt("snapshots-per-tick", 9)));
        millisPerTick = Math.max(0.2, Math.min(45, settings.getDouble("millis-per-tick", 3.0)));
        classCacheSize = Math.max(32, Math.min(8192, settings.getInt("class-cache", 512)));
        logChanges = settings.getBoolean("log-changes", false);
        audit = LoadGate.AuditPolicy.parse(settings.getString("audit", "sample"), settings.getDouble("audit-share", 0.1));
        auditPerTick = Math.max(0, Math.min(64, settings.getInt("audit-per-tick", 2)));
        autoPerSecond = Math.max(0, Math.min(200, settings.getInt("auto-survey-per-second", 4)));
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        SurveyThreads.install(Bukkit::isPrimaryThread);
        loadRules();
        for (World w : Bukkit.getWorlds()) {
            // chunks that were loaded before the survey started (spawn chunks): the same check as on a load
            for (Chunk c : w.getLoadedChunks()) chunkLoaded(w, c);
        }
        watcher = new SurveyWatcher(this);
        watcher.register();
        tasks.timer(1, 1, this::tick);
        resumeJobs();
        core.getLogger().info("World survey started: " + poolSize + " worker thread(s), files in " + core.getDataFolder().getName() + "/survey");
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        try { if (watcher != null) watcher.unregister(); } catch (RuntimeException | LinkageError ignored) { }
        tasks.close();
        for (PrecomputeJob job : jobs.values()) job.saveNow();
        bars.clear();
        for (SnapshotRequest r : queue) r.future.completeExceptionally(new IllegalStateException("world survey stopped"));
        queue.clear();
        for (WorldState ws : worlds.values()) { releaseTickets(ws); ws.cellWaiters.failAll("world survey stopped"); }
        workers.shutdownNow();
        files.execute(this::flushAll);
        files.shutdown();
        try {
            if (!files.awaitTermination(10, TimeUnit.SECONDS)) core.getLogger().warning("World survey: files still writing after 10 s");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        core.getLogger().info("World survey stopped");
    }

    private void releaseTickets(WorldState ws) {
        World w = Bukkit.getWorld(ws.id);
        if (w == null) return;
        for (long key : ws.tickets) {
            try { if (w.isChunkLoaded(RoomJoin.keyX(key), RoomJoin.keyZ(key))) w.getChunkAt(RoomJoin.keyX(key), RoomJoin.keyZ(key)).removePluginChunkTicket(core); }
            catch (RuntimeException ignored) { }
        }
        ws.tickets.clear();
    }

    /** A plugin was disabled: its listeners go. */
    public void release(Plugin plugin) { listeners.removeIf(r -> r.plugin.getName().equals(plugin.getName())); }

    public boolean stopped() { return stopped; }

    // ------------------------------------------------------------------ worlds and rules

    WorldState state(World world) {
        return worlds.computeIfAbsent(world.getUID(), id -> new WorldState(world, root.resolve(id.toString()), rule(world)));
    }

    WorldState existing(UUID id) { return worlds.get(id); }

    Iterable<WorldState> states() { return worlds.values(); }

    public static SurveyRule defaultRule(World world) {
        if (world == null) return SurveyRule.OVERWORLD;
        return switch (world.getEnvironment()) {
            case NETHER -> SurveyRule.NETHER;
            case THE_END -> SurveyRule.END;
            default -> SurveyRule.OVERWORLD;
        };
    }

    public SurveyRule rule(World world) {
        SurveyRule own = rules.get(world.getName());
        return own != null ? own : defaultRule(world);
    }

    public void rule(World world, SurveyRule rule) {
        rules.put(world.getName(), rule);
        WorldState ws = state(world);
        ws.rule = rule;
        // every record of the world carries the old hash: stale from now on
        ws.stale.addAll(ws.records.keySet());
        saveRules();
    }

    private Path rulesFile() { return root.resolve("rules.yml"); }

    private void loadRules() {
        Path file = rulesFile();
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration yaml = AtomicFiles.readYaml(file, core.getLogger());
        if (yaml == null) return;
        ConfigurationSection section = yaml.getConfigurationSection("worlds");
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(name);
            if (r == null) continue;
            try {
                World w = Bukkit.getWorld(name);
                SurveyRule base = defaultRule(w);
                rules.put(name, new SurveyRule(r.getInt("reach", base.reach()), r.getInt("hall-radius", base.hallRadius()),
                        r.getBoolean("void-below", base.voidBelow()), r.getBoolean("doors-stop-reach", base.doorsStopReach())));
            } catch (IllegalArgumentException bad) {
                core.getLogger().warning("survey/rules.yml: world " + name + ": " + bad.getMessage());
            }
        }
    }

    private void saveRules() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Surface rules of the world survey per world (written by /vadmin survey rule)."));
        for (var e : rules.entrySet()) {
            String p = "worlds." + e.getKey() + ".";
            yaml.set(p + "reach", e.getValue().reach());
            yaml.set(p + "hall-radius", e.getValue().hallRadius());
            yaml.set(p + "void-below", e.getValue().voidBelow());
            yaml.set(p + "doors-stop-reach", e.getValue().doorsStopReach());
        }
        String text = yaml.saveToString();
        files.execute(() -> {
            try {
                Files.createDirectories(root);
                AtomicFiles.write(rulesFile(), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                core.getLogger().log(Level.WARNING, "survey/rules.yml not written", failure);
            }
        });
    }

    // ------------------------------------------------------------------ the parts

    private final SurveyRooms rooms = new Rooms();
    private final OpenCells openCells = new Cells();
    private final SurveyPrecompute precompute = new Jobs();

    public SurveyRooms rooms() { return rooms; }

    public OpenCells openCells() { return openCells; }

    public SurveyPrecompute precompute() { return precompute; }

    private final class Rooms implements SurveyRooms {
        @Override
        public Optional<ChunkSurvey> cached(World world, int chunkX, int chunkZ) {
            WorldState ws = worlds.get(world.getUID());
            if (ws == null) return Optional.empty();
            long key = RoomJoin.key(chunkX, chunkZ);
            ChunkSurvey s = ws.record(key);
            return s == null || ws.stale.contains(key) ? Optional.empty() : Optional.of(s);
        }

        @Override
        public CompletableFuture<ChunkSurvey> request(World world, int chunkX, int chunkZ) {
            if (stopped) return CompletableFuture.failedFuture(new IllegalStateException("world survey stopped"));
            return SurveyRuntime.this.request(state(world), world, chunkX, chunkZ, false, false);
        }

        @Override
        public Optional<RoomId> roomAt(World world, int x, int y, int z) {
            return cached(world, x >> 4, z >> 4).filter(s -> s.cells().isPresent()).flatMap(s -> {
                int local = s.roomAt(x, y, z);
                return local == 0 ? Optional.empty() : Optional.of(s.id(local));
            });
        }

        @Override
        public Optional<CellKind> kindAt(World world, int x, int y, int z) {
            return cached(world, x >> 4, z >> 4).filter(s -> s.cells().isPresent()).map(s -> s.kindAt(x, y, z));
        }

        @Override
        public Set<RoomId> adjacent(RoomId room) {
            WorldState ws = worlds.get(room.worldId());
            if (ws == null) return Set.of();
            ChunkSurvey s = fresh(ws, room.chunkKey());
            if (s == null || room.local() > s.rooms().size()) return Set.of();
            return RoomJoin.adjacent(s, room.local(), key -> fresh(ws, key));
        }

        @Override
        public CompletableFuture<RoomComponent> component(RoomId room, int maxChunks) {
            World world = Bukkit.getWorld(room.worldId());
            if (world == null) return CompletableFuture.failedFuture(new IllegalArgumentException("unknown world " + room.worldId()));
            WorldState ws = state(world);
            RoomJoin.ComponentSearch search = new RoomJoin.ComponentSearch(room, maxChunks);
            CompletableFuture<RoomComponent> out = new CompletableFuture<>();
            step(ws, world, search, out);
            return out;
        }

        private void step(WorldState ws, World world, RoomJoin.ComponentSearch search, CompletableFuture<RoomComponent> out) {
            try {
                while (true) {
                    search.run();
                    if (search.done()) {
                        out.complete(search.result());
                        return;
                    }
                    Set<Long> needed = search.needed();
                    List<CompletableFuture<Void>> waits = new ArrayList<>();
                    boolean fedAll = true;
                    for (long key : needed) {
                        ChunkSurvey known = fresh(ws, key);
                        if (known != null) {
                            search.feed(key, known);
                            continue;
                        }
                        fedAll = false;
                        waits.add(SurveyRuntime.this.request(ws, world, RoomJoin.keyX(key), RoomJoin.keyZ(key), false, false)
                                .handle((s, failure) -> {
                                    synchronized (search) { search.feed(key, failure == null ? s : null); }
                                    return null;
                                }));
                    }
                    if (!fedAll) {
                        CompletableFuture.allOf(waits.toArray(new CompletableFuture[0]))
                                .whenCompleteAsync((ignored, failure) -> step(ws, world, search, out), workers);
                        return;
                    }
                }
            } catch (RuntimeException failure) {
                out.completeExceptionally(failure);
            }
        }

        @Override
        public CompletableFuture<Boolean> isSkyConnected(RoomId room) {
            return component(room, 256).thenApply(c -> c.skyContact() == SkyContact.MOUTH);
        }
    }

    /** A record that is not stale, from memory or (without blocking) from a region already in memory. */
    ChunkSurvey fresh(WorldState ws, long key) {
        if (ws.stale.contains(key)) return null;
        ChunkSurvey s = ws.record(key);
        if (s != null) return s;
        RegionRecords region = ws.regions.get(regionKey(RoomJoin.keyX(key), RoomJoin.keyZ(key)));
        if (region == null) return null;
        byte[] bytes = region.get(RegionRecords.index(RoomJoin.keyX(key), RoomJoin.keyZ(key)));
        if (bytes == null || SurveyCodec.ruleHashOf(bytes) != ws.rule.hash()) return null;
        try { return SurveyCodec.decode(bytes, ws.id, RoomJoin.keyX(key), RoomJoin.keyZ(key)); }
        catch (SurveyCodec.FormatException damaged) { return null; }
    }

    private final class Cells implements OpenCells {
        @Override
        public Optional<OpenChunk> chunk(World world, int chunkX, int chunkZ) {
            WorldState ws = worlds.get(world.getUID());
            return ws == null ? Optional.empty() : Optional.ofNullable(ws.open.get(RoomJoin.key(chunkX, chunkZ)));
        }

        @Override
        public int tracked(World world) {
            WorldState ws = worlds.get(world.getUID());
            return ws == null ? 0 : ws.open.size();
        }

        @Override
        public CompletableFuture<OpenChunk> request(World world, int chunkX, int chunkZ) {
            WorldState ws = worlds.get(world.getUID());
            if (stopped || ws == null) return CompletableFuture.failedFuture(new IllegalStateException("world survey not running in " + world.getName()));
            long key = RoomJoin.key(chunkX, chunkZ);
            OpenChunk known = ws.open.get(key);
            if (known != null) return CompletableFuture.completedFuture(known);
            CompletableFuture<OpenChunk> f = ws.cellWaiters.register(key);
            tasks.now(() -> {
                OpenChunk again = ws.open.get(key);
                if (again != null) { ws.cellWaiters.publish(key, again); return; }
                if (!ws.loaded.contains(key)) { ws.cellWaiters.fail(key, "chunk " + chunkX + "," + chunkZ + " is not loaded"); return; }
                ChunkSurvey record = ws.record(key);
                // an offered record without cells: an audit at once attaches them; otherwise a survey makes them
                if (record != null && record.cells().isEmpty() && !ws.stale.contains(key)) ws.urgentAudits.add(key);
                else rooms.request(world, chunkX, chunkZ);
            });
            return f;
        }
    }

    private final class Jobs implements SurveyPrecompute {
        @Override
        public SurveyJob start(World world, int centerX, int centerZ, int radius, JobOptions options) {
            if (stopped) throw new IllegalStateException("world survey stopped");
            if (radius < 0 || radius > 1_000_000) throw new IllegalArgumentException("radius " + radius);
            PrecomputeJob old = jobs.get(world.getUID());
            if (old != null && (old.state() == SurveyJob.State.RUNNING || old.state() == SurveyJob.State.PAUSED))
                throw new IllegalStateException("a job runs in " + world.getName() + "; pause, cancel or wait for it");
            JobState s = new JobState(world.getUID(), centerX, centerZ, radius, options, SurveyJob.State.RUNNING, 0, 0, 0);
            resetStats(); // the status and the job's end line then show this job's own numbers
            PrecomputeJob job = new PrecomputeJob(SurveyRuntime.this, world.getName(), s);
            jobs.put(world.getUID(), job);
            job.saveNow();
            return job;
        }

        @Override
        public Optional<SurveyJob> job(World world) { return Optional.ofNullable(jobs.get(world.getUID())); }

        @Override
        public List<SurveyJob> jobs() { return List.copyOf(jobs.values()); }
    }

    private void resumeJobs() {
        for (World w : Bukkit.getWorlds()) {
            Path file = root.resolve(w.getUID().toString()).resolve(JobState.FILE);
            if (!Files.isRegularFile(file)) continue;
            try {
                JobState s = JobState.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (!s.worldId.equals(w.getUID())) continue;
                PrecomputeJob job = new PrecomputeJob(this, w.getName(), s);
                jobs.put(w.getUID(), job);
                // a surveyed area: read its region files ahead (at most 64), so its chunks find their records at load
                RingPlan plan = s.plan();
                int r0x = (plan.centerChunkX() - plan.chunkRadius()) >> 5, r1x = (plan.centerChunkX() + plan.chunkRadius()) >> 5;
                int r0z = (plan.centerChunkZ() - plan.chunkRadius()) >> 5, r1z = (plan.centerChunkZ() + plan.chunkRadius()) >> 5;
                WorldState ws = state(w);
                int read = 0;
                for (int rx = r0x; rx <= r1x && read < 64; rx++)
                    for (int rz = r0z; rz <= r1z && read < 64; rz++, read++) readRegion(ws, w, rx << 5, rz << 5);
                if (s.state == SurveyJob.State.RUNNING)
                    core.getLogger().info("World survey job in " + w.getName() + " continues at chunk " + s.next + " of " + s.plan().count());
            } catch (IOException | IllegalArgumentException damaged) {
                core.getLogger().warning("World survey job file of " + w.getName() + " is unreadable and was moved aside: " + damaged.getMessage());
                AtomicFiles.moveAside(file);
            }
        }
    }

    void writeJob(UUID world, String text) {
        files.execute(() -> {
            try {
                Path dir = root.resolve(world.toString());
                Files.createDirectories(dir);
                AtomicFiles.write(dir.resolve(JobState.FILE), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                core.getLogger().log(Level.WARNING, "World survey job file not written", failure);
            }
        });
    }

    // ------------------------------------------------------------------ requests

    /**
     * The survey of a chunk. {@code force}: survey again even if a record is known; {@code generate}: generate
     * missing chunks. Any thread.
     */
    CompletableFuture<ChunkSurvey> request(WorldState ws, World world, int cx, int cz, boolean generate, boolean force) {
        long key = RoomJoin.key(cx, cz);
        if (!force) {
            ChunkSurvey known = ws.record(key);
            if (known != null && !ws.stale.contains(key)) return CompletableFuture.completedFuture(known);
        }
        CompletableFuture<ChunkSurvey> running = ws.inFlight.get(key);
        if (running != null) return running;
        CompletableFuture<ChunkSurvey> mine = new CompletableFuture<>();
        running = ws.inFlight.putIfAbsent(key, mine);
        if (running != null) return running;
        inFlight.incrementAndGet();
        mine.whenComplete((s, failure) -> {
            ws.inFlight.remove(key, mine);
            inFlight.decrementAndGet();
            if (failure != null) failures.incrementAndGet();
        });
        CompletableFuture<ChunkSurvey> stored = force || ws.stale.contains(key)
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> stored(ws, cx, cz), files);
        stored.thenComposeAsync(s -> {
            if (s != null) {
                if (!ws.loaded.contains(key)) return CompletableFuture.completedFuture(s);
                OpenChunk cells = ws.open.get(key);
                Optional<ChunkSurvey> bound = cells == null ? Optional.empty() : s.withCells(cells);
                if (bound.isPresent() && bound.get().consistent()) {
                    ws.records.put(key, bound.get());
                    return CompletableFuture.completedFuture(bound.get());
                }
            }
            return survey(ws, world, cx, cz, generate);
        }, workers).whenComplete((s, failure) -> {
            if (failure != null) mine.completeExceptionally(failure instanceof CompletionException c && c.getCause() != null ? c.getCause() : failure);
            else mine.complete(s);
        });
        return mine;
    }

    /** A stored record of the world's current rule (file thread). */
    private ChunkSurvey stored(WorldState ws, int cx, int cz) {
        byte[] bytes = region(ws, cx, cz).get(RegionRecords.index(cx, cz));
        if (bytes == null || SurveyCodec.ruleHashOf(bytes) != ws.rule.hash()) return null;
        try { return SurveyCodec.decode(bytes, ws.id, cx, cz); }
        catch (SurveyCodec.FormatException damaged) {
            core.getLogger().fine("World survey record " + cx + "," + cz + " unreadable: " + damaged.getMessage());
            return null;
        }
    }

    private CompletableFuture<ChunkSurvey> survey(WorldState ws, World world, int cx, int cz, boolean generate) {
        SnapshotRequest req = new SnapshotRequest(ws, world, cx, cz, generate, false);
        enqueue(req);
        return req.future.thenApplyAsync(r -> {
            long t0 = System.nanoTime();
            long c0 = cpu();
            ChunkClasses[] nine = classes(r);
            long t1 = System.nanoTime();
            SurveyRule rule = ws.rule;
            SurveyGrid grid = SurveyGrid.assemble(rule.margin(), nine);
            long ta = System.nanoTime();
            assembleNanos.addAndGet(ta - t1);
            long stamp;
            do stamp = ThreadLocalRandom.current().nextLong(); while (stamp == 0);
            ChunkSurvey s = SurveyEngine.survey(grid, rule, ws.id, cx, cz, stamp);
            long te = System.nanoTime();
            byte[] bytes = SurveyCodec.encode(s);
            encodeNanos.addAndGet(System.nanoTime() - te);
            surveys.incrementAndGet();
            long t2 = System.nanoTime();
            cpuNanos.addAndGet(cpu() - c0);
            surveyNanos.addAndGet(t2 - t0);
            engineNanos.addAndGet(t2 - t1);
            storedBytes.addAndGet(bytes.length);
            tasks.now(() -> commit(r, s, bytes));
            return s;
        }, workers);
    }

    /** Classes of the requested chunks: cached ones as they are, snapshots classified now (worker). */
    private ChunkClasses[] classes(SnapshotRequest r) {
        SurveyThreads.assertWorker("classification");
        ChunkClasses[] out = new ChunkClasses[9];
        int minY = r.world.getMinHeight(), height = r.world.getMaxHeight() - minY;
        for (int i = 0; i < 9; i++) {
            if (!r.wanted(i)) continue;
            if (r.classes[i] != null) out[i] = r.classes[i];
            else if (r.snaps[i] != null) {
                long t0 = System.nanoTime();
                out[i] = blocks.classify(r.snaps[i], minY, height);
                classifyNanos.addAndGet(System.nanoTime() - t0);
                classified.incrementAndGet();
                r.snaps[i] = null; // the snapshot can go, the classes are smaller
                r.ws.classes.put(r.key(i), out[i]);
            }
        }
        return out;
    }

    /** Stores a finished survey (server thread). */
    private void commit(SnapshotRequest r, ChunkSurvey s, byte[] bytes) {
        SurveyThreads.assertServer("commit");
        WorldState ws = r.ws;
        long key = r.key(4);
        releaseTicket(r);
        for (int i = 0; i < 9; i++)
            if (r.wanted(i) && ws.version(r.key(i)) != r.versions[i]) {
                // a block changed while the survey ran: this result is already stale
                ws.stale.add(key);
                schedule(ws, key);
                return;
            }
        World world = r.world;
        boolean loaded = ws.loaded.contains(key) && world.isChunkLoaded(r.cx, r.cz);
        if (!loaded) return; // no persistent data to stamp: the record would be stale at the next load anyway
        world.getChunkAt(r.cx, r.cz).getPersistentDataContainer().set(stampKey, PersistentDataType.LONG, s.stamp());
        files.execute(() -> region(ws, r.cx, r.cz).put(RegionRecords.index(r.cx, r.cz), bytes));
        ChunkSurvey before = ws.record(key);
        ws.records.put(key, s);
        ws.lazy.remove(key);
        boolean wasStale = ws.stale.remove(key);
        if (logChanges && wasStale)
            core.getLogger().info("World survey: chunk " + r.cx + "," + r.cz + " in " + ws.name + " surveyed again: " + s.rooms().size()
                    + " rooms" + (before == null ? "" : " (before " + before.rooms().size() + ")") + ", stamp " + Long.toHexString(s.stamp()));
        s.cells().ifPresent(c -> { ws.open.put(key, c); ws.cellWaiters.publish(key, c); });
        fire(l -> l.surveyed(s));
        if (before != null && wasStale) fire(l -> l.changed(before, s));
    }

    private void releaseTicket(SnapshotRequest r) {
        if (!r.ticketed) return;
        r.ticketed = false;
        r.ws.tickets.remove(r.key(4));
        try { if (r.world.isChunkLoaded(r.cx, r.cz)) r.world.getChunkAt(r.cx, r.cz).removePluginChunkTicket(core); }
        catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ chunk load and unload (server thread)

    /**
     * A chunk loaded (server thread, inside the chunk load event): the stored record is offered at once when its
     * stamp equals the chunk's (see {@link LoadGate}); no snapshot, no file read. Listeners get
     * {@link SurveyListener#loaded} right here, with the record or null.
     */
    void chunkLoaded(World world, Chunk chunk) {
        long t0 = System.nanoTime();
        WorldState ws = state(world);
        int cx = chunk.getX(), cz = chunk.getZ();
        long key = RoomJoin.key(cx, cz);
        ws.loaded.add(key);
        Long pdc = chunk.getPersistentDataContainer().get(stampKey, PersistentDataType.LONG);
        long stamp = pdc == null ? 0 : pdc;
        ws.pdcStamps.put(key, stamp);
        long rk = regionKey(cx, cz);
        ws.regionUse.put(rk, System.currentTimeMillis());
        LoadGate.Result r = LoadGate.decide(ws.regions.get(rk), cx, cz, stamp, ws.rule.hash(), ws.id, false);
        if (inJobArea(ws, cx, cz)) loadsInArea.incrementAndGet();
        ChunkSurvey offered = apply(ws, key, cx, cz, r, false);
        lookupNanos.addAndGet(System.nanoTime() - t0);
        lookups.incrementAndGet();
        if (r.outcome() == LoadGate.Outcome.NOT_YET) {
            ws.waitingForRegion.computeIfAbsent(rk, k -> new ArrayList<>()).add(key);
            ws.waitingSince.put(key, t0);
            readRegion(ws, world, cx, cz);
        }
        fireLoaded(ws.id, cx, cz, offered);
    }

    /** Applies a load decision (server thread); returns the offered record or null. */
    private ChunkSurvey apply(WorldState ws, long key, int cx, int cz, LoadGate.Result r, boolean late) {
        switch (r.outcome()) {
            case OFFER -> {
                if (ws.records.containsKey(key)) return ws.records.get(key); // surveyed in the meantime
                ChunkSurvey record = r.record();
                if (record == null) ws.lazy.put(key, r.bytes());
                else ws.records.put(key, record);
                ws.stale.remove(key);
                (late ? offeredLate : offeredAtLoad).incrementAndGet();
                if (inJobArea(ws, cx, cz)) offeredInArea.incrementAndGet();
                if (audit.audits(cx, cz)) ws.audits.add(key);
                return record != null ? record : listeners.isEmpty() ? null : ws.record(key);
            }
            case STALE -> {
                staleAtLoad.incrementAndGet();
                ws.stale.add(key);
                ws.missing.add(key);
                if (logChanges) core.getLogger().info("World survey: record of chunk " + cx + "," + cz + " in " + ws.name + " is stale (stamp, rule or format)");
            }
            case NONE -> {
                loadedWithoutRecord.incrementAndGet();
                ws.missing.add(key);
            }
            case NOT_YET -> notYet.incrementAndGet();
        }
        return null;
    }

    /** Reads a region file on the file thread, then decides the chunks that waited for it (server thread). */
    private void readRegion(WorldState ws, World world, int cx, int cz) {
        long rk = regionKey(cx, cz);
        if (ws.regions.containsKey(rk) || !ws.regionLoading.add(rk)) return;
        files.execute(() -> {
            try { region(ws, cx, cz); }
            finally {
                ws.regionLoading.remove(rk);
                tasks.now(() -> {
                    List<Long> waiting = ws.waitingForRegion.remove(rk);
                    if (waiting == null) return;
                    RegionRecords region = ws.regions.get(rk);
                    for (long key : waiting) {
                        Long since = ws.waitingSince.remove(key);
                        if (!ws.loaded.contains(key)) continue;
                        if (since != null) { lateNanos.addAndGet(System.nanoTime() - since); lateCount.incrementAndGet(); }
                        int x = RoomJoin.keyX(key), z = RoomJoin.keyZ(key);
                        LoadGate.Result r = LoadGate.decide(region, x, z, ws.pdcStamps.getOrDefault(key, 0L), ws.rule.hash(), ws.id);
                        if (r.outcome() == LoadGate.Outcome.NOT_YET) continue;
                        ChunkSurvey offered = apply(ws, key, x, z, r, true);
                        if (offered != null) fire(l -> l.surveyed(offered));
                    }
                });
            }
        });
    }

    /**
     * Reads ahead the region files around every player (view distance plus four chunks), so the chunks a player
     * walks into find their records in memory (server thread, every two seconds).
     */
    private void prefetchRegions() {
        int view = Math.max(2, Bukkit.getViewDistance()) + 4;
        long now = System.currentTimeMillis();
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            World world = p.getWorld();
            WorldState ws = state(world);
            int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
            for (int rx = (pcx - view) >> 5; rx <= (pcx + view) >> 5; rx++)
                for (int rz = (pcz - view) >> 5; rz <= (pcz + view) >> 5; rz++) {
                    long rk = RoomJoin.key(rx, rz);
                    ws.regionUse.put(rk, now);
                    if (!ws.regions.containsKey(rk)) readRegion(ws, world, rx << 5, rz << 5);
                }
        }
    }

    /**
     * The audit (server thread, every tick, low priority): a few offered records get a snapshot of their chunk and
     * are compared with its open cells; a record whose blocks changed without Core seeing it becomes stale.
     */
    private void processAudits(long lastTickMillis) {
        if (lastTickMillis > 50 || queue.size() > 32) return;
        for (WorldState ws : worlds.values()) {
            int n = 0;
            World world = null;
            while (n < Math.max(1, auditPerTick) && !(ws.urgentAudits.isEmpty() && (auditPerTick == 0 || ws.audits.isEmpty()))) {
                boolean urgent = !ws.urgentAudits.isEmpty();
                java.util.Set<Long> from = urgent ? ws.urgentAudits : ws.audits;
                long key = from.iterator().next();
                from.remove(key);
                ws.audits.remove(key);
                ws.urgentAudits.remove(key);
                if (!ws.auditing.add(key)) continue; // one audit per chunk at a time
                ChunkSurvey record = ws.record(key);
                if (record == null || record.cells().isPresent() || !ws.loaded.contains(key)) { ws.auditing.remove(key); continue; }
                if (world == null) world = Bukkit.getWorld(ws.id);
                if (world == null) { ws.auditing.remove(key); break; }
                n++;
                (urgent ? touchAudits : audited).incrementAndGet();
                final boolean byTouch = urgent;
                int cx = RoomJoin.keyX(key), cz = RoomJoin.keyZ(key);
                SnapshotRequest req = new SnapshotRequest(ws, world, cx, cz, false, true);
                enqueue(req);
                final World w = world;
                req.future.thenApplyAsync(r -> LoadGate.audit(record, classes(r)[4].open()), workers).thenAccept(bound -> tasks.now(() -> {
                    ws.auditing.remove(key);
                    if (ws.records.get(key) != record) return; // surveyed again in the meantime
                    if (bound.isPresent()) {
                        ws.records.put(key, bound.get());
                        bound.get().cells().ifPresent(c -> { ws.open.put(key, c); ws.cellWaiters.publish(key, c); });
                    } else {
                        (byTouch ? touchFindings : auditFindings).incrementAndGet();
                        if (logChanges) core.getLogger().info("World survey: audit found the record of chunk " + cx + "," + cz + " in " + ws.name + " stale ("
                                + (byTouch ? "a block event in the chunk" : "blocks changed without events") + ")");
                        invalidate(ws, w, cx, cz);
                    }
                }));
            }
        }
    }

    /** Loaded chunks without a usable record: a few surveys per second, nearest to a player first (server thread). */
    private void processMissing() {
        if (autoPerSecond <= 0 || inFlight.get() > poolSize * 2) return;
        int view = Math.max(2, Bukkit.getViewDistance());
        for (WorldState ws : worlds.values()) {
            if (ws.missing.size() == 0) continue;
            World world = Bukkit.getWorld(ws.id);
            if (world == null) continue;
            List<org.bukkit.entity.Player> players = world.getPlayers();
            int[] at = new int[players.size() * 2];
            for (int i = 0; i < players.size(); i++) {
                at[2 * i] = players.get(i).getLocation().getBlockX() >> 4;
                at[2 * i + 1] = players.get(i).getLocation().getBlockZ() >> 4;
            }
            for (long key : ws.missing.pick(autoPerSecond, at, view)) {
                if (!ws.loaded.contains(key)) continue;
                autoSurveys.incrementAndGet();
                request(ws, world, RoomJoin.keyX(key), RoomJoin.keyZ(key), false, false);
            }
        }
    }

    private void fireLoaded(UUID worldId, int cx, int cz, ChunkSurvey survey) {
        for (Registration r : listeners) {
            Runnable run = () -> {
                try { r.listener.loaded(worldId, cx, cz, survey); }
                catch (RuntimeException | LinkageError failure) {
                    core.getLogger().log(Level.WARNING, "World survey listener of " + r.plugin.getName() + " failed", failure);
                }
            };
            if (r.serverThread) run.run();
            else if (!workers.isShutdown()) workers.execute(run);
        }
    }

    void chunkUnloaded(World world, Chunk chunk) {
        int cx = chunk.getX(), cz = chunk.getZ();
        WorldState ws = worlds.get(world.getUID());
        if (ws == null) return;
        touchesBeforeUnload(ws, world, chunk);
        long key = RoomJoin.key(cx, cz);
        ws.loaded.remove(key);
        ws.records.remove(key);
        ws.lazy.remove(key);
        ws.open.remove(key);
        ws.cellWaiters.fail(key, "chunk unloaded");
        ws.versions.remove(key);
        ws.due.remove(key);
        ws.tickets.remove(key);
        ws.missing.remove(key);
        ws.audits.remove(key);
        ws.pdcStamps.remove(key);
        ws.waitingSince.remove(key);
        ws.auditing.remove(key);
    }

    // ------------------------------------------------------------------ block changes

    /** Whether the survey keeps open cells or classes of a chunk (a cheap filter for frequent events). */
    boolean tracks(World world, int cx, int cz) {
        WorldState ws = worlds.get(world.getUID());
        if (ws == null) return false;
        long key = RoomJoin.key(cx, cz);
        return ws.open.containsKey(key) || ws.classes.contains(key) || ws.hasRecord(key);
    }

    /** A block may have changed (any thread; checked on the next tick). */
    void touch(World world, int x, int y, int z) {
        if (stopped) return;
        pendingTouches.computeIfAbsent(world.getUID(), id -> ConcurrentHashMap.newKeySet()).add(packTouch(x, y, z));
    }

    private static long packTouch(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void processTouches() {
        if (pendingTouches.isEmpty()) return;
        for (Map.Entry<UUID, Set<Long>> perWorld : pendingTouches.entrySet()) {
            UUID id = perWorld.getKey();
            WorldState ws = worlds.get(id);
            World world = Bukkit.getWorld(id);
            for (Iterator<Long> it = perWorld.getValue().iterator(); it.hasNext(); ) {
            long p = it.next();
            it.remove();
            if (ws == null || world == null) continue;
            int x = (int) (p >> 38), z = (int) ((p << 26) >> 38), y = (int) ((p << 52) >> 52);
            long key = RoomJoin.key(x >> 4, z >> 4);
            OpenChunk cells = ws.open.get(key);
            ChunkClasses cached = ws.classes.contains(key) ? ws.classes.get(key) : null;
            if (cells == null && cached == null) {
                // an offered record without cells: Core cannot tell what changed, the audit looks at once
                if (ws.hasRecord(key)) ws.urgentAudits.add(key);
                continue;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            applyTouch(ws, world, key, x, y, z, cells, cached, blocks.of(world.getBlockAt(x, y, z).getType()));
            }
        }
    }

    /**
     * A chunk unloads (server thread, inside the unload event, the chunk can still be read): block changes noted in
     * this tick are applied now. Before, they were dropped when the chunk unloaded before the next tick, and the
     * chunk kept its stamp although its blocks had changed.
     */
    private void touchesBeforeUnload(WorldState ws, World world, Chunk chunk) {
        Set<Long> pending = pendingTouches.get(ws.id);
        if (pending == null || pending.isEmpty()) return;
        long key = RoomJoin.key(chunk.getX(), chunk.getZ());
        for (Iterator<Long> it = pending.iterator(); it.hasNext(); ) {
            long p = it.next();
            int x = (int) (p >> 38), z = (int) ((p << 26) >> 38), y = (int) ((p << 52) >> 52);
            if (RoomJoin.key(x >> 4, z >> 4) != key) continue;
            it.remove();
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            OpenChunk cells = ws.open.get(key);
            ChunkClasses cached = ws.classes.contains(key) ? ws.classes.get(key) : null;
            byte now = blocks.of(chunk.getBlock(x & 15, y, z & 15).getType());
            if (cells == null && cached == null) {
                // a record without cells: what changed is unknown, so the stored record goes stale
                if (ws.hasRecord(key)) invalidate(ws, world, chunk.getX(), chunk.getZ());
                continue;
            }
            applyTouch(ws, world, key, x, y, z, cells, cached, now);
        }
    }

    private void applyTouch(WorldState ws, World world, long key, int x, int y, int z, OpenChunk cells, ChunkClasses cached, byte now) {
        boolean open = CellClass.open(now);
        if (cached != null) ws.classes.update(key, cached.with(x & 15, y - world.getMinHeight(), z & 15, now));
        boolean before = cells != null ? cells.isOpen(x, y, z) : CellClass.open(cached.at(x & 15, y - world.getMinHeight(), z & 15));
        if (before == open) return;
        if (cells != null) ws.open.put(key, cells.with(x, y, z, open));
        changed(ws, world, x, z);
    }

    /** Cell class per state id of the shared state table, -1 while unknown (server thread). */
    private byte[] classById = new byte[0];
    private Vystorm.vystorm_core.blocks.StateTable classTable;
    final AtomicLong editEvents = new AtomicLong();
    final AtomicLong editCells = new AtomicLong();
    final AtomicLong editNanos = new AtomicLong();

    private byte classOfState(Vystorm.vystorm_core.blocks.StateTable table, int id) {
        if (table != classTable) { classTable = table; classById = new byte[0]; }
        if (id < 0) return CellClass.OPAQUE;
        if (id >= classById.length) {
            int old = classById.length;
            classById = java.util.Arrays.copyOf(classById, Math.max(id + 1, Math.max(256, old * 2)));
            java.util.Arrays.fill(classById, old, classById.length, (byte) -1);
        }
        byte c = classById[id];
        if (c < 0) classById[id] = c = blocks.of(table.material(id));
        return c;
    }

    /**
     * A bulk edit wrote cells of one chunk ({@code BlocksEditedEvent}, server thread). Same rule as single block
     * changes, applied to all cells in one pass: the open cells and classes follow at once, and the chunk and the
     * neighbours within the margin of a cell that turned open or opaque go stale and are surveyed again after the
     * usual pause. Chunks the survey does not track cost one lookup.
     */
    void edited(Vystorm.vystorm_core.blocks.EditedChunk chunk) {
        if (stopped || chunk.size() == 0) return;
        World world = chunk.world();
        WorldState ws = worlds.get(world.getUID());
        if (ws == null) return;
        long start = System.nanoTime();
        int cx = chunk.chunkX(), cz = chunk.chunkZ();
        long key = RoomJoin.key(cx, cz);
        OpenChunk cells = ws.open.get(key);
        ChunkClasses cached = ws.classes.contains(key) ? ws.classes.get(key) : null;
        if (cells == null && cached == null && !ws.hasRecord(key)) return;
        Vystorm.vystorm_core.blocks.StateTable table = chunk instanceof Vystorm.vystorm_core.blocks.internal.EditedCells ec
                ? ec.states() : Vystorm.vystorm_core.blocks.BlockWrites.states();
        int minY = world.getMinHeight(), height = world.getMaxHeight() - minY;
        SurveyEdits.Result r = SurveyEdits.apply(chunk, id -> classOfState(table, id), cells, cached, minY, height, ws.rule.margin());
        if (cached != null && r.classes() != cached) ws.classes.update(key, r.classes());
        if (cells != null && r.cells() != cells) ws.open.put(key, r.cells());
        r.forEachChunk(cx, cz, (x, z) -> invalidate(ws, world, x, z));
        editEvents.incrementAndGet();
        editCells.addAndGet(chunk.size());
        editNanos.addAndGet(System.nanoTime() - start);
    }

    /** An opaque cell turned open or the other way round at x, z: the chunk and the neighbours within reach. */
    private void changed(WorldState ws, World world, int x, int z) {
        int m = ws.rule.margin(), lx = x & 15, lz = z & 15, cx = x >> 4, cz = z >> 4;
        int dx0 = lx < m ? -1 : 0, dx1 = lx > 15 - m ? 1 : 0, dz0 = lz < m ? -1 : 0, dz1 = lz > 15 - m ? 1 : 0;
        for (int dz = dz0; dz <= dz1; dz++) for (int dx = dx0; dx <= dx1; dx++) invalidate(ws, world, cx + dx, cz + dz);
    }

    /** Marks a chunk stale and schedules a new survey (server thread). */
    private void invalidate(WorldState ws, World world, int cx, int cz) {
        long key = RoomJoin.key(cx, cz);
        ws.bump(key);
        boolean known = ws.hasRecord(key) || ws.open.containsKey(key);
        if (world.isChunkLoaded(cx, cz)) world.getChunkAt(cx, cz).getPersistentDataContainer().remove(stampKey);
        else files.execute(() -> region(ws, cx, cz).put(RegionRecords.index(cx, cz), null)); // no stamp to clear: drop the record
        boolean fresh = ws.stale.add(key);
        if (logChanges && fresh && known) core.getLogger().info("World survey: chunk " + cx + "," + cz + " in " + ws.name + " is stale (blocks changed)");
        if (fresh || known) fire(l -> l.invalidated(ws.id, cx, cz));
        schedule(ws, key);
    }

    private void schedule(WorldState ws, long key) { ws.due.put(key, tick + DEBOUNCE_TICKS); }

    public void invalidate(World world, int x, int y, int z) {
        runOnServer(() -> {
            WorldState ws = state(world);
            ws.dropClasses(RoomJoin.key(x >> 4, z >> 4));
            changed(ws, world, x, z);
        });
    }

    public void invalidateChunk(World world, int cx, int cz) {
        runOnServer(() -> {
            WorldState ws = state(world);
            ws.dropClasses(RoomJoin.key(cx, cz));
            invalidate(ws, world, cx, cz);
        });
    }

    private void runOnServer(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else tasks.now(r);
    }

    private void processDue() {
        for (WorldState ws : worlds.values()) {
            if (ws.due.isEmpty()) continue;
            World world = null;
            for (Iterator<Map.Entry<Long, Long>> it = ws.due.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Long> e = it.next();
                if (e.getValue() > tick) continue;
                it.remove();
                long key = e.getKey();
                if (!ws.loaded.contains(key)) continue; // stays stale; surveyed again when asked
                if (world == null) world = Bukkit.getWorld(ws.id);
                if (world == null) break;
                request(ws, world, RoomJoin.keyX(key), RoomJoin.keyZ(key), false, true);
            }
        }
    }

    // ------------------------------------------------------------------ the snapshot pump (server thread)

    private void enqueue(SnapshotRequest r) {
        if (Bukkit.isPrimaryThread()) queue.add(r);
        else tasks.now(() -> queue.add(r));
    }

    int queued() { return queue.size(); }

    /** Snapshot budget of this tick: the running job's options, a quarter while players are online and it yields. */
    private double[] budget() {
        int count = snapshotsPerTick;
        double ms = millisPerTick;
        boolean yield = false;
        for (PrecomputeJob job : jobs.values())
            if (job.state() == SurveyJob.State.RUNNING) {
                count = Math.max(count, job.options().snapshotsPerTick());
                ms = Math.max(ms, job.options().millisPerTick());
                yield |= job.options().yieldToPlayers();
            }
        if (yield && !Bukkit.getOnlinePlayers().isEmpty()) {
            count = Math.max(1, count / 4);
            ms = ms / 4;
        }
        return new double[] {count, ms};
    }

    /**
     * Takes snapshots within the budget. The budget follows the previous tick: after a tick of more than 60 ms a
     * quarter, after more than 100 ms nothing (the server is catching up; the queue waits a tick).
     */
    private void pump(long lastTickMillis) {
        if (queue.isEmpty()) return;
        double[] b = budget();
        if (lastTickMillis > 100) { pumpSkips.incrementAndGet(); return; }
        if (lastTickMillis > 60) { b[0] = Math.max(1, (int) b[0] / 4); b[1] = b[1] / 4; }
        int maxCount = (int) b[0];
        long maxNanos = (long) (b[1] * 1_000_000);
        long t0 = System.nanoTime();
        int taken = 0;
        int rounds = queue.size();
        while (rounds-- > 0 && !queue.isEmpty() && taken < maxCount && System.nanoTime() - t0 < maxNanos) {
            SnapshotRequest r = queue.poll();
            if (r.future.isDone()) continue;
            int state = r.advance(this, maxCount - taken);
            taken += r.lastTaken;
            if (state == SnapshotRequest.READY) r.future.complete(r);
            else if (state == SnapshotRequest.FAILED) { releaseTicket(r); r.future.completeExceptionally(new IllegalStateException(r.failure)); }
            else queue.add(r);
        }
        snapshots.addAndGet(taken);
        long spent = System.nanoTime() - t0;
        pumpNanos.addAndGet(spent);
        pumpMaxNanos.accumulateAndGet(spent, Math::max);
    }

    // ------------------------------------------------------------------ files (file thread)

    static long regionKey(int cx, int cz) { return RoomJoin.key(cx >> 5, cz >> 5); }

    /** The records of the region of a chunk, read from disk on first use (file thread only). */
    RegionRecords region(WorldState ws, int cx, int cz) {
        long rk = regionKey(cx, cz);
        ws.regionUse.put(rk, System.currentTimeMillis());
        RegionRecords r = ws.regions.get(rk);
        if (r != null) return r;
        Path file = ws.dir.resolve(RegionRecords.fileName(cx, cz));
        r = new RegionRecords();
        if (Files.isRegularFile(file)) {
            try {
                r = RegionRecords.decode(Files.readAllBytes(file));
            } catch (IOException | SurveyCodec.FormatException damaged) {
                Path aside = AtomicFiles.moveAside(file);
                core.getLogger().warning("World survey file " + file.getFileName() + " is unreadable (" + damaged.getMessage()
                        + ") and was moved aside" + (aside == null ? "" : " to " + aside.getFileName()) + "; its chunks are surveyed again");
                r = new RegionRecords();
            }
        }
        ws.regions.put(rk, r);
        return r;
    }

    private void flushAll() {
        long now = System.currentTimeMillis();
        for (WorldState ws : worlds.values()) {
            for (Iterator<Map.Entry<Long, RegionRecords>> it = ws.regions.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, RegionRecords> e = it.next();
                RegionRecords r = e.getValue();
                if (r.dirty()) {
                    int rx = RoomJoin.keyX(e.getKey()), rz = RoomJoin.keyZ(e.getKey());
                    Path file = ws.dir.resolve("r." + rx + "." + rz + ".vsurvey");
                    byte[] bytes = r.encodeAndClean();
                    try {
                        Files.createDirectories(ws.dir);
                        AtomicFiles.write(file, bytes);
                    } catch (IOException failure) {
                        r.markDirty();
                        core.getLogger().log(Level.WARNING, "World survey file " + file.getFileName() + " not written", failure);
                    }
                } else {
                    Long used = ws.regionUse.get(e.getKey());
                    if (used == null || now - used > 120_000) {
                        it.remove();
                        ws.regionUse.remove(e.getKey());
                    }
                }
            }
            countFiles(ws);
        }
    }

    /** Counts the files of a world (file thread, after each flush; read by the status). */
    private static void countFiles(WorldState ws) {
        long[] out = new long[2];
        if (Files.isDirectory(ws.dir)) {
            try (var list = Files.list(ws.dir)) {
                list.filter(p -> p.getFileName().toString().endsWith(".vsurvey")).forEach(p -> {
                    out[0]++;
                    try { out[1] += Files.size(p); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
        ws.files = out;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        tick++;
        long start = System.nanoTime();
        long gc0 = gcMillis();
        long lastTickMillis = lastTickStart == 0 ? 50 : (start - lastTickStart) / 1_000_000;
        lastTickStart = start;
        if (lastTickMillis > 100) recordSlowTick(lastTickMillis);
        lastTickLoads = 0;
        try {
            limitClassCaches();
            processTouches();
            processDue();
            for (PrecomputeJob job : jobs.values()) job.tick();
            processAudits(lastTickMillis);
            if (tick % 20 == 7) processMissing();
            if (tick % 40 == 3) prefetchRegions();
            pump(lastTickMillis);
            if (tick % FLUSH_TICKS == 0) files.execute(this::flushAll);
            if (tick % 20 == 0) bars.update();
        } catch (RuntimeException failure) {
            core.getLogger().log(Level.WARNING, "World survey tick failed", failure);
        }
        lastOwnNanos = System.nanoTime() - start;
        lastOwnGcMillis = gcMillis() - gc0;
        if (lastOwnNanos > ownTickMaxNanos.get()) { ownTickMaxNanos.set(lastOwnNanos); ownTickMaxGcMillis = lastOwnGcMillis; }
    }

    /** Keeps the five slowest ticks with the survey's own share of the tick before them (evidence for spikes). */
    private void recordSlowTick(long millis) {
        synchronized (slowTicks) {
            slowTicks.add(new long[] {millis, lastOwnNanos / 1000, lastTickLoads, lastOwnGcMillis});
            slowTicks.sort((a, b) -> Long.compare(b[0], a[0]));
            while (slowTicks.size() > 5) slowTicks.remove(slowTicks.size() - 1);
        }
    }

    long currentTick() { return tick; }

    /**
     * Class cache limits: Core's settings normally; while a job runs enough entries for three rings of its area (the
     * previous ring's chunks are neighbours again one ring later), at most 4096 chunks or 64 MB, kept 10 minutes.
     */
    private void limitClassCaches() {
        if (tick % 20 != 1) return;
        for (WorldState ws : worlds.values()) {
            PrecomputeJob job = jobs.get(ws.id);
            if (job != null && job.state() == SurveyJob.State.RUNNING)
                ws.classes.limits(Math.max(classCacheSize, Math.min(4096, 24 * job.chunkRadius() + 64)), 64L << 20, 600_000);
            else ws.classes.limits(classCacheSize, 32L << 20, CLASS_CACHE_MILLIS);
        }
    }

    private static long cpu() {
        try { return THREADS.getCurrentThreadCpuTime(); } catch (UnsupportedOperationException | SecurityException none) { return 0; }
    }

    /** Starts the counters of the status and the work line again (a new job). */
    void resetStats() {
        for (AtomicLong a : new AtomicLong[] {surveys, surveyNanos, snapshots, pumpNanos, storedBytes, failures, pumpMaxNanos,
                classified, classifyNanos, engineNanos, chunkLoads, pumpSkips, assembleNanos, encodeNanos, ownTickMaxNanos,
                loadCallNanos, loadCallMaxNanos, cpuNanos, loadBackoffs, offeredAtLoad, offeredLate, staleAtLoad, loadedWithoutRecord,
                notYet, lookups, lookupNanos, audited, auditFindings, autoSurveys, lateNanos, lateCount, loadsInArea, offeredInArea,
                touchAudits, touchFindings, editEvents, editCells, editNanos}) a.set(0);
        synchronized (slowTicks) { slowTicks.clear(); }
        for (WorldState ws : worlds.values()) ws.classes.resetCounts();
    }

    /** One line for the log: what chunk loads found (status). */
    String loadLine() {
        long n = Math.max(1, lookups.get());
        return String.format(java.util.Locale.ROOT, "%d chunk loads (%d of them in a surveyed job area, %d of those offered in the load event): %d offered in the load event, %d after their region file was read (%.1f ms later on average), %d stale, %d without record, %d not yet; lookup %.4f ms per load; %d audited by sample, %d found stale; %d audited after a block event, %d of them changed; %d surveys started for chunks near players; %d chunks of bulk edits with %d cells looked at in %.2f ms",
                lookups.get(), loadsInArea.get(), offeredInArea.get(), offeredAtLoad.get(), offeredLate.get(), lateNanos.get() / 1e6 / Math.max(1, lateCount.get()), staleAtLoad.get(), loadedWithoutRecord.get(), notYet.get(),
                lookupNanos.get() / 1e6 / n, audited.get(), auditFindings.get(), touchAudits.get(), touchFindings.get(), autoSurveys.get(), editEvents.get(), editCells.get(), editNanos.get() / 1e6);
    }

    /** One line for the log: where the time of a survey goes (status and job end). */
    String breakdown() {
        long n = Math.max(1, surveys.get()), c = Math.max(1, classified.get()), sn = Math.max(1, snapshots.get());
        long hits = 0, misses = 0;
        for (WorldState ws : worlds.values()) { hits += ws.classes.hits(); misses += ws.classes.misses(); }
        return String.format(java.util.Locale.ROOT,
                "%d surveys: %.2f ms per survey on a worker, %.2f ms of it CPU time (classes %.2f ms for %.2f snapshots, %.2f ms per snapshot; assemble %.2f, survey %.2f, encode %.2f ms); "
                        + "class cache %d hits, %d misses; %d snapshots on the server thread, %.3f ms each, longest pump %.2f ms, %d pumps skipped after long ticks; %d chunk loads",
                surveys.get(), surveyNanos.get() / 1e6 / n, cpuNanos.get() / 1e6 / n, classifyNanos.get() / 1e6 / n, (double) classified.get() / n,
                classifyNanos.get() / 1e6 / c, assembleNanos.get() / 1e6 / n,
                (engineNanos.get() - assembleNanos.get() - encodeNanos.get()) / 1e6 / n, encodeNanos.get() / 1e6 / n, hits, misses, snapshots.get(),
                pumpNanos.get() / 1e6 / sn, pumpMaxNanos.get() / 1e6, pumpSkips.get(), chunkLoads.get())
                + String.format(java.util.Locale.ROOT, ", %.2f ms in load calls (longest %.2f ms, %d pauses after slow ones); longest own tick %.2f ms (%d ms of it garbage collection); slowest ticks (ms, survey's share of that tick in ms, chunk loads it asked for, gc ms inside the survey's share): %s",
                loadCallNanos.get() / 1e6, loadCallMaxNanos.get() / 1e6, loadBackoffs.get(), ownTickMaxNanos.get() / 1e6, ownTickMaxGcMillis, slowTickText());
    }

    private static long gcMillis() {
        long n = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, gc.getCollectionTime());
        return n;
    }

    private String slowTickText() {
        synchronized (slowTicks) {
            StringBuilder b = new StringBuilder();
            for (long[] t : slowTicks) b.append(b.length() == 0 ? "" : ", ").append(t[0]).append('/').append(String.format(java.util.Locale.ROOT, "%.2f", t[1] / 1000.0)).append('/').append(t[2]).append(" gc ").append(t[3]);
            return b.length() == 0 ? "none" : b.toString();
        }
    }

    // ------------------------------------------------------------------ listeners

    private record Registration(Plugin plugin, SurveyListener listener, boolean serverThread) {}

    public AutoCloseable listen(Plugin plugin, SurveyListener listener, boolean serverThread) {
        Registration r = new Registration(plugin, listener, serverThread);
        listeners.add(r);
        return () -> listeners.remove(r);
    }

    private void fire(java.util.function.Consumer<SurveyListener> call) {
        for (Registration r : listeners) {
            Runnable run = () -> {
                try { call.accept(r.listener); }
                catch (RuntimeException | LinkageError failure) {
                    core.getLogger().log(Level.WARNING, "World survey listener of " + r.plugin.getName() + " failed", failure);
                }
            };
            if (r.serverThread) {
                if (Bukkit.isPrimaryThread()) run.run();
                else tasks.now(run);
            } else if (!workers.isShutdown()) workers.execute(run);
        }
    }

    int listenerCount() { return listeners.size(); }

    // ------------------------------------------------------------------ state per world

    static final class WorldState {
        final UUID id;
        final String name;
        final Path dir;
        volatile SurveyRule rule;
        final Map<Long, ChunkSurvey> records = new ConcurrentHashMap<>();
        /** Records offered at chunk load and not asked for yet: decoded on first use. */
        final Map<Long, byte[]> lazy = new ConcurrentHashMap<>();

        /** The record of a loaded chunk (decoded now if it was only offered). Any thread. */
        ChunkSurvey record(long key) {
            ChunkSurvey s = records.get(key);
            if (s != null) return s;
            byte[] bytes = lazy.get(key);
            if (bytes == null) return null;
            try {
                ChunkSurvey decoded = SurveyCodec.decode(bytes, id, RoomJoin.keyX(key), RoomJoin.keyZ(key));
                ChunkSurvey known = records.putIfAbsent(key, decoded);
                lazy.remove(key, bytes);
                return known != null ? known : decoded;
            } catch (SurveyCodec.FormatException damaged) {
                lazy.remove(key, bytes);
                stale.add(key);
                return null;
            }
        }

        boolean hasRecord(long key) { return records.containsKey(key) || lazy.containsKey(key); }
        final Set<Long> stale = ConcurrentHashMap.newKeySet();
        final Map<Long, OpenChunk> open = new ConcurrentHashMap<>();
        final Set<Long> loaded = ConcurrentHashMap.newKeySet();
        final Map<Long, CompletableFuture<ChunkSurvey>> inFlight = new ConcurrentHashMap<>();
        final Map<Long, RegionRecords> regions = new ConcurrentHashMap<>();
        final Map<Long, Long> regionUse = new ConcurrentHashMap<>();
        final Map<Long, Long> versions = new ConcurrentHashMap<>();
        final Map<Long, Long> due = new HashMap<>();
        final Set<Long> tickets = ConcurrentHashMap.newKeySet();
        /** Stamps read from the chunks' persistent data at load (server thread). */
        final Map<Long, Long> pdcStamps = new HashMap<>();
        final Map<Long, Long> waitingSince = new HashMap<>();
        /** Chunks waiting for their region file, region files being read. */
        final Map<Long, List<Long>> waitingForRegion = new HashMap<>();
        final Set<Long> regionLoading = ConcurrentHashMap.newKeySet();
        /** Offered records to audit (sampled) and the ones touched by a block event (first). Server thread. */
        final Set<Long> audits = new java.util.LinkedHashSet<>();
        final Set<Long> urgentAudits = new java.util.LinkedHashSet<>();
        final Set<Long> auditing = ConcurrentHashMap.newKeySet();
        /** Futures of openCells().request(..) waiting for a chunk's cells. */
        final CellWaiters cellWaiters = new CellWaiters();
        final MissingQueue missing = new MissingQueue();
        /** Files and bytes on disk after the last flush. */
        volatile long[] files = new long[2];
        /** Block classes of recently snapshotted chunks (see {@link ClassCache}). */
        final ClassCache classes = new ClassCache();

        WorldState(World world, Path dir, SurveyRule rule) {
            this.id = world.getUID();
            this.name = world.getName();
            this.dir = dir;
            this.rule = rule;
        }

        long version(long key) { return versions.getOrDefault(key, 0L); }

        void bump(long key) { versions.merge(key, 1L, Long::sum); }

        void dropClasses(long key) { classes.remove(key); }
    }

    // ------------------------------------------------------------------ snapshot requests

    static final class SnapshotRequest {
        static final int READY = 0, WAITING = 1, FAILED = 2;
        final WorldState ws;
        final World world;
        final int cx, cz;
        final boolean generate;
        final boolean centreOnly;
        final ChunkClasses[] classes = new ChunkClasses[9];
        final ChunkSnapshot[] snaps = new ChunkSnapshot[9];
        final long[] versions = new long[9];
        final boolean[] loading = new boolean[9];
        final CompletableFuture<SnapshotRequest> future = new CompletableFuture<>();
        volatile String failure;
        volatile boolean ticketed;
        int lastTaken;

        SnapshotRequest(WorldState ws, World world, int cx, int cz, boolean generate, boolean centreOnly) {
            this.ws = ws;
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.generate = generate;
            this.centreOnly = centreOnly;
        }

        boolean wanted(int i) { return !centreOnly || i == 4; }

        long key(int i) { return RoomJoin.key(cx + (i % 3) - 1, cz + (i / 3) - 1); }

        /** Takes what the budget allows (server thread). */
        int advance(SurveyRuntime rt, int budget) {
            lastTaken = 0;
            if (failure != null) return FAILED;
            boolean complete = true;
            // centre first: it carries the ticket that keeps it loaded until the commit
            for (int n = 0; n < 9; n++) {
                int i = (n + 4) % 9;
                if (!wanted(i) || classes[i] != null || snaps[i] != null) continue;
                int x = cx + (i % 3) - 1, z = cz + (i / 3) - 1;
                long key = key(i);
                boolean loaded = world.isChunkLoaded(x, z);
                if (!centreOnly && (i != 4 || loaded)) {
                    // an unloaded chunk does not change and a loaded one is kept up to date by the block watcher,
                    // so cached classes stand in for a new snapshot (and a new chunk load)
                    ChunkClasses cached = ws.classes.get(key);
                    if (cached != null) {
                        classes[i] = cached;
                        versions[i] = ws.version(key);
                        if (i == 4) ticket(rt, world.getChunkAt(x, z));
                        continue;
                    }
                }
                if (loaded) {
                    if (lastTaken >= budget) { complete = false; continue; }
                    take(rt, i, world.getChunkAt(x, z));
                    continue;
                }
                // a load check never loads a chunk again that unloaded in the meantime
                if (centreOnly) { failure = "chunk " + x + "," + z + " unloaded"; return FAILED; }
                complete = false;
                if (loading[i]) continue;
                loading[i] = true;
                final int index = i;
                if (!rt.mayLoad(generate)) { loading[i] = false; continue; }
                rt.chunkLoads.incrementAndGet();
                rt.lastTickLoads++;
                long lc = System.nanoTime();
                CompletableFuture<Chunk> load = PlatformCompat.getChunkAtAsync(world, x, z, generate);
                long spent = System.nanoTime() - lc;
                rt.loadCallNanos.addAndGet(spent);
                rt.loadCallMaxNanos.accumulateAndGet(spent, Math::max);
                rt.loadTook(spent);
                load.whenComplete((chunk, error) -> rt.runOnServer(() -> {
                    loading[index] = false;
                    if (error != null || chunk == null) failure = "chunk " + x + "," + z + " is not generated";
                    else if (snaps[index] == null && classes[index] == null) take(rt, index, chunk);
                }));
            }
            if (failure != null) return FAILED;
            return complete ? READY : WAITING;
        }

        private void take(SurveyRuntime rt, int i, Chunk chunk) {
            snaps[i] = chunk.getChunkSnapshot(false, false, false);
            versions[i] = ws.version(key(i));
            lastTaken++;
            if (i == 4) ticket(rt, chunk);
        }

        /** Keeps the centre loaded until the commit stamps it. */
        private void ticket(SurveyRuntime rt, Chunk chunk) {
            if (centreOnly || ticketed) return;
            try {
                if (chunk.addPluginChunkTicket(rt.core)) { ticketed = true; ws.tickets.add(key(4)); }
            } catch (RuntimeException | LinkageError unsupported) { }
        }
    }
}
