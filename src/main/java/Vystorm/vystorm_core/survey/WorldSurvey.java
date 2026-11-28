package Vystorm.vystorm_core.survey;

import Vystorm.vystorm_core.survey.internal.SurveyRuntime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * Static entry of the world survey ({@code api.survey()} is the manager view, {@link WorldSurveyInterface}). Holds
 * the switch, the plugins that require the survey and the running runtime. Thread safe.
 *
 * @since 0.68.0
 */
public final class WorldSurvey {
    private static final Set<String> REQUIRED = ConcurrentHashMap.newKeySet();
    private static volatile boolean enabled;
    private static volatile Plugin core;
    private static volatile ConfigurationSection settings;
    private static volatile SurveyRuntime runtime;

    private WorldSurvey() {}

    public static boolean active() { return runtime != null; }

    public static boolean enabled() { return enabled; }

    /**
     * Core internal: reads {@code survey} of Core's config at start and on every reload and starts or stops the
     * runtime. Server thread.
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static synchronized void configure(Plugin owner, ConfigurationSection config) {
        core = owner;
        settings = config == null ? null : config.getConfigurationSection("survey");
        enabled = config != null && config.getBoolean("survey.enabled", false);
        SurveyRuntime running = runtime;
        if (running != null) running.configure(settings);
        apply();
    }

    /**
     * Core internal: stops the runtime (Core disables). Server thread.
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static synchronized void shutdown() {
        SurveyRuntime running = runtime;
        runtime = null;
        if (running != null) running.stop();
        REQUIRED.clear();
        core = null;
    }

    public static synchronized void require(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (!plugin.isEnabled()) throw new IllegalArgumentException("Plugin " + plugin.getName() + " is not enabled");
        if (REQUIRED.add(plugin.getName())) apply();
    }

    public static synchronized void release(Plugin plugin) {
        if (plugin == null) return;
        SurveyRuntime running = runtime;
        if (running != null) running.release(plugin);
        if (REQUIRED.remove(plugin.getName())) apply();
    }

    /** Names of the plugins that require the survey. */
    public static Set<String> requiredBy() { return Set.copyOf(REQUIRED); }

    private static void apply() {
        boolean want = enabled || !REQUIRED.isEmpty();
        Plugin owner = core;
        if (want && runtime == null && owner != null && owner.isEnabled()) {
            try {
                SurveyRuntime started = new SurveyRuntime(owner, settings);
                started.start();
                runtime = started;
            } catch (RuntimeException | LinkageError failure) {
                owner.getLogger().log(Level.WARNING, "World survey not started", failure);
            }
        } else if (!want && runtime != null) {
            SurveyRuntime running = runtime;
            runtime = null;
            running.stop();
        }
    }

    /**
     * Core internal: the running runtime, or null.
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static SurveyRuntime runtime() { return runtime; }

    public static SurveyRooms rooms() { SurveyRuntime r = runtime; return r == null ? OFF_ROOMS : r.rooms(); }

    public static OpenCells openCells() { SurveyRuntime r = runtime; return r == null ? OFF_CELLS : r.openCells(); }

    public static SurveyPrecompute precompute() { SurveyRuntime r = runtime; return r == null ? OFF_JOBS : r.precompute(); }

    public static SurveyRule rule(World world) {
        SurveyRuntime r = runtime;
        return r == null ? SurveyRuntime.defaultRule(world) : r.rule(world);
    }

    public static void rule(World world, SurveyRule rule) {
        SurveyRuntime r = runtime;
        if (r == null) throw new IllegalStateException(OFF);
        r.rule(world, rule);
    }

    public static AutoCloseable listen(Plugin plugin, SurveyListener listener, boolean serverThread) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(listener, "listener");
        SurveyRuntime r = runtime;
        if (r == null) throw new IllegalStateException(OFF);
        return r.listen(plugin, listener, serverThread);
    }

    public static void invalidate(World world, int x, int y, int z) {
        SurveyRuntime r = runtime;
        if (r != null) r.invalidate(world, x, y, z);
    }

    public static void invalidateChunk(World world, int chunkX, int chunkZ) {
        SurveyRuntime r = runtime;
        if (r != null) r.invalidateChunk(world, chunkX, chunkZ);
    }

    static final String OFF = "The world survey is off: switch survey.enabled on in Core's config.yml or call api.survey().require(plugin)";

    private static final SurveyRooms OFF_ROOMS = new SurveyRooms() {
        @Override public Optional<ChunkSurvey> cached(World world, int chunkX, int chunkZ) { return Optional.empty(); }

        @Override public CompletableFuture<ChunkSurvey> request(World world, int chunkX, int chunkZ) { return CompletableFuture.failedFuture(new IllegalStateException(OFF)); }

        @Override public Optional<RoomId> roomAt(World world, int x, int y, int z) { return Optional.empty(); }

        @Override public Optional<CellKind> kindAt(World world, int x, int y, int z) { return Optional.empty(); }

        @Override public Set<RoomId> adjacent(RoomId room) { return Set.of(); }

        @Override public CompletableFuture<RoomComponent> component(RoomId room, int maxChunks) { return CompletableFuture.failedFuture(new IllegalStateException(OFF)); }

        @Override public CompletableFuture<Boolean> isSkyConnected(RoomId room) { return CompletableFuture.failedFuture(new IllegalStateException(OFF)); }
    };

    private static final OpenCells OFF_CELLS = new OpenCells() {
        @Override public Optional<OpenChunk> chunk(World world, int chunkX, int chunkZ) { return Optional.empty(); }

        @Override public int tracked(World world) { return 0; }
    };

    private static final SurveyPrecompute OFF_JOBS = new SurveyPrecompute() {
        @Override public SurveyJob start(World world, int centerX, int centerZ, int radius, JobOptions options) { throw new IllegalStateException(OFF); }

        @Override public Optional<SurveyJob> job(World world) { return Optional.empty(); }

        @Override public List<SurveyJob> jobs() { return List.of(); }
    };
}
