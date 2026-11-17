package Vystorm.vystorm_core.worldevent;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.schedule.TaskGroup;
import Vystorm.vystorm_core.worldevent.engine.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Owner-bound event registration/execution independent of content or a storyteller plugin.
 * Mutations and handler callbacks run on the server thread. Event domains isolate storage/ownership;
 * conflict checks and execution handlers span all domains. No world/chunk scanning is performed.
 * <p>Preferred access: {@code api.events()}.
 */
public final class WorldEvents {
    private static final WorldEvents INSTANCE = new WorldEvents(Clock.systemUTC(), RandomGenerator.getDefault());
    public static WorldEvents get() { return INSTANCE; }
    private final Clock clock;
    private final RandomGenerator random;
    private final Map<String, Domain> domains = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, HandlerRegistration> handlers = new java.util.concurrent.ConcurrentHashMap<>();
    private Path directory;
    private TaskGroup tasks;
    private record HandlerRegistration(Plugin owner, WorldEventHandler handler) { }

    public WorldEvents(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock); this.random = Objects.requireNonNull(random);
    }
    WorldEvents(Clock clock, RandomGenerator random, Path directory) { this(clock, random); this.directory = directory; }
    public void initialize(Plugin core) {
        thread();
        directory = core.getDataFolder().toPath().resolve("worldevents");
        if (tasks != null) tasks.close();
        tasks = TaskGroup.of(core, "Core/WorldEvents");
        tasks.timer(20, 20, this::tick);
    }
    private static void thread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) throw new IllegalStateException("World events require the server thread");
    }
    private static String key(String id) {
        String value = Objects.requireNonNull(id).toLowerCase(Locale.ROOT).trim();
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,95}")) throw new IllegalArgumentException("Invalid event domain: " + id);
        return value;
    }
    /** Persistent domain stored atomically by Core; registering plugins need no WorldForge dependency. */
    public Domain domain(Plugin owner, String id) { return open(owner, id, false); }
    /** Integration seam for an existing durable store. Bind checkpoint before allowing starts; no files are reset. */
    public Domain externalDomain(Plugin owner, String id) { return open(owner, id, true); }
    private Domain open(Plugin owner, String id, boolean external) {
        thread(); Objects.requireNonNull(owner); String name = key(id);
        Domain existing = domains.get(name);
        if (existing != null) {
            if (existing.owner != owner || existing.external != external) throw new IllegalStateException("Event domain already owned: " + name);
            return existing;
        }
        Domain created = new Domain(owner, name, external);
        domains.put(name, created); return created;
    }
    /** Registers the execution provider for an event ID across catalog domains. Duplicate owners cannot replace it. */
    public AutoCloseable handler(Plugin owner, String eventId, WorldEventHandler handler) {
        thread(); Objects.requireNonNull(owner); Objects.requireNonNull(handler);
        String id = Objects.requireNonNull(eventId).trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || handlers.containsKey(id)) throw new IllegalArgumentException("Duplicate/empty event handler: " + id);
        HandlerRegistration registration = new HandlerRegistration(owner, handler); handlers.put(id, registration);
        for (ActiveEvent event : activeEvents()) if (event.definition().id().equals(id)) invoke(registration, event, null, true);
        return () -> { thread(); handlers.remove(id, registration); };
    }
    /** Chooses the sole matching catalog. Ambiguous IDs require the caller to choose a domain explicitly. */
    public EventRuntimeService.StartResult start(String eventId, EventScope scope, String scopeKey) {
        thread(); var matches = domains.values().stream().filter(d -> d.registry.find(eventId).isPresent()).toList();
        if (matches.size() != 1) return EventRuntimeService.StartResult.blocked(matches.isEmpty() ? "unknown-event" : "ambiguous-domain");
        return matches.getFirst().start(eventId, scope, scopeKey);
    }
    public boolean stop(UUID instanceId) {
        thread(); for (Domain domain : List.copyOf(domains.values())) if (domain.stop(instanceId)) return true; return false;
    }
    public List<ActiveEvent> activeEvents() { return domains.values().stream().flatMap(d -> d.runtime.activeEvents().stream()).toList(); }
    public List<WorldEventView> active() { return activeEvents().stream().map(WorldEvents::view).toList(); }
    public boolean available() { return !domains.isEmpty(); }
    public List<WorldEventView> activeAt(UUID world, int x, int y, int z) {
        List<WorldEventView> result = new ArrayList<>();
        for (Domain d : domains.values()) {
            try {
                result.addAll(d.source == null ? d.runtime.activeEvents().stream().filter(e -> matches(e, world, x, y, z)).map(WorldEvents::view).toList() : d.source.activeAt(world, x, y, z));
            } catch (RuntimeException error) { failure(d.owner, "activeAt", error); }
        }
        return List.copyOf(result);
    }

    public double modifier(String key, UUID world, int x, int y, int z) {
        double value = 1;
        for (Domain domain : domains.values()) if (domain.source != null) {
            try { value *= domain.source.modifier(key, world, x, y, z); }
            catch (RuntimeException error) { failure(domain.owner, "modifier", error); }
        }
        return WorldEventServices.clamp(value);
    }
    private static boolean matches(ActiveEvent event, UUID world, int x, int y, int z) {
        return switch (event.scope()) {
            case GLOBAL -> true;
            case WORLD -> event.scopeKey().equalsIgnoreCase(world.toString()) || Bukkit.getWorld(world) != null && event.scopeKey().equalsIgnoreCase(Bukkit.getWorld(world).getName());
            case REGIONAL -> RegionService.get().at(world, x, y, z).stream().map(Region::key).anyMatch(event.scopeKey()::equalsIgnoreCase);
            case LOCAL -> false; // Local scopes require the domain's explicit spatial source.
        };
    }
    public static WorldEventView view(ActiveEvent event) {
        var d = event.definition(); return new WorldEventView(event.instanceId(), d.id(), d.category(), d.type().name(), event.scope().name(), event.scopeKey(), d.tags(), event.startedAt(), event.endsAt(), d.pressureValue());
    }
    private static void failure(Plugin owner, String action, Throwable error) { ProviderFailures.report("Core/WorldEvents", owner.getName(), action, error); }
    private boolean invoke(HandlerRegistration registration, ActiveEvent event, EventEndReason reason, boolean resume) {
        if (registration == null) return true;
        if (reason == null && !registration.owner.isEnabled()) return false;
        try {
            if (resume) registration.handler.resume(event);
            else if (reason == null) registration.handler.start(event);
            else registration.handler.stop(event, reason);
            return true;
        } catch (RuntimeException error) { failure(registration.owner, resume ? "resume" : reason == null ? "start" : "stop", error); return false; }
    }
    public void tick() {
        thread(); for (Domain domain : List.copyOf(domains.values())) if (domain.ready && !domain.faulted) {
            try { domain.drainOfflineEnds(); domain.runtime.expire(); } catch (RuntimeException error) { domain.faulted = true; failure(domain.owner, "expire", error); }
        }
    }
    public void release(Plugin owner) {
        thread();
        for (Domain domain : List.copyOf(domains.values())) if (domain.owner == owner) {
            try { domain.close(); } catch (RuntimeException error) { failure(owner, "release", error); }
        }
        for (ActiveEvent event : activeEvents()) {
            HandlerRegistration handler = handlers.get(event.definition().id());
            if (handler != null && handler.owner == owner) {
                try { stop(event.instanceId()); } catch (RuntimeException error) { failure(owner, "release", error); }
            }
        }
        handlers.entrySet().removeIf(e -> e.getValue().owner == owner);
    }
    public void close() {
        thread(); if (tasks != null) { tasks.close(); tasks = null; }
        for (Domain domain : List.copyOf(domains.values())) {
            try { domain.close(); } catch (RuntimeException error) { failure(domain.owner, "close", error); }
        }
        handlers.clear();
    }
    private Optional<String> foreignBlock(Domain source, EventDefinition definition, EventScope scope, String scopeKey, boolean externalStart) {
        var combined = new EventRuntimeService(clock, random); var catalog = new EventRegistry(); catalog.replace(definition);
        var active = new ArrayList<EventRuntimeService.PersistedActiveEvent>();
        var cooldowns = new ArrayList<EventRuntimeService.CooldownState>();
        for (Domain domain : domains.values()) {
            domain.registry.all().forEach(catalog::replace);
            active.addAll(domain.runtime.snapshotReadOnly().activeEvents());
            if (!externalStart) cooldowns.addAll(domain.runtime.snapshotReadOnly().cooldowns());
        }
        cooldowns.sort(java.util.Comparator.comparing(EventRuntimeService.CooldownState::until));
        combined.restore(new EventRuntimeService.RuntimeSnapshot(active, cooldowns), catalog);
        return combined.blockedReason(definition, scope, scopeKey);
    }

    public final class Domain implements AutoCloseable {
        private final Plugin owner;
        private final String id;
        private final boolean external;
        private final EventRegistry registry = new EventRegistry();
        private final EventRuntimeService runtime = new EventRuntimeService(clock, random);
        private final EventLifecycleRegistry lifecycle;
        private Runnable checkpoint;
        private volatile WorldEventSource source;
        private boolean ready, faulted, closed;
        private final List<ActiveEvent> pendingOffline = new ArrayList<>();
        private final Set<UUID> notifiedOffline = new HashSet<>();
        private Domain(Plugin owner, String id, boolean external) {
            this.owner = owner; this.id = id; this.external = external;
            lifecycle = new EventLifecycleRegistry((listener, error) -> failure(owner, listener, error));
            if (!external) {
                if (directory == null) throw new IllegalStateException("Core world events have not initialized");
                EventDomainFile file = new EventDomainFile(directory.resolve(id + ".json"));
                pendingOffline.addAll(file.load(registry, runtime));
                checkpoint = () -> file.save(registry, runtime, pendingOffline); ready = true;
            }
            runtime.onExpired(event -> { save(); ended(event, EventEndReason.EXPIRED); });
        }
        public String id() { return id; }
        public Plugin owner() { return owner; }
        /** Advanced catalog integration; changes must be followed by checkpoint(). */
        public EventRegistry registry() { return registry; }
        /** Read/restore seam for existing storage adapters; execute events via Domain.start/stop. */
        public EventRuntimeService runtime() { return runtime; }
        public EventLifecycleRegistry lifecycle() { return lifecycle; }
        public void checkpoint(Runnable checkpoint) { thread(); if (!external) throw new IllegalStateException("Core owns this store"); this.checkpoint = Objects.requireNonNull(checkpoint); }
        public void ready() { thread(); if (checkpoint == null) throw new IllegalStateException("No durable checkpoint"); ready = true; }
        public void source(WorldEventSource source) { thread(); this.source = Objects.requireNonNull(source); }
        public void register(EventDefinition definition) { thread(); registry.replace(definition); save(); }
        public void checkpoint() { thread(); save(); }
        private void save() {
            if (checkpoint == null) throw new IllegalStateException("Event store not ready");
            try { checkpoint.run(); } catch (RuntimeException error) { faulted = true; throw error; }
        }
        public EventRuntimeService.StartResult start(String eventId, EventScope scope, String scopeKey) { return start(eventId, scope, scopeKey, false); }
        /** Report an externally running event without re-executing its handler or applying its cooldown gate. */
        public EventRuntimeService.StartResult start(String eventId, EventScope scope, String scopeKey, boolean externalStart) {
            thread();
            if (closed || faulted || !ready || !owner.isEnabled()) return EventRuntimeService.StartResult.blocked("provider-unavailable");
            var definition = registry.find(eventId); if (definition.isEmpty()) return EventRuntimeService.StartResult.blocked("unknown-event");
            var executor = handlers.get(definition.get().id());
            if (executor != null && !executor.owner.isEnabled()) return EventRuntimeService.StartResult.blocked("executor-unavailable");
            var foreign = foreignBlock(this, definition.get(), scope, scopeKey, externalStart);
            if (foreign.isPresent()) return EventRuntimeService.StartResult.blocked(foreign.get());
            var before = runtime.snapshot(); var result = runtime.start(definition.get(), scope, scopeKey, externalStart);
            if (!result.started()) return result;
            try { save(); } catch (RuntimeException error) { runtime.restore(before, registry); throw error; }
            ActiveEvent event = result.event().orElseThrow();
            if (!externalStart && !invoke(handlers.get(event.definition().id()), event, null, false)) {
                stop(event.instanceId()); return EventRuntimeService.StartResult.blocked("handler-failed");
            }
            lifecycle.fireStarted(event); WorldEventServices.fireStarted(view(event)); return result;
        }
        public boolean stop(UUID instanceId) {
            thread(); if (closed || faulted || !ready) return false;
            var event = runtime.activeEvents().stream().filter(e -> e.instanceId().equals(instanceId)).findFirst();
            if (event.isEmpty()) return false;
            var before = runtime.snapshotReadOnly(); runtime.stop(instanceId);
            try { save(); } catch (RuntimeException error) { runtime.restore(before, registry); throw error; }
            ended(event.get(), EventEndReason.MANUAL); return true;
        }
        /** Deliver an offline expiry restored by an external store, without replaying a start. Server thread only. */
        public void endedWhileOffline(ActiveEvent event) {
            thread();
            if (event.endsAt().isAfter(clock.instant())) throw new IllegalArgumentException("Event has not expired");
            if (notifiedOffline.add(event.instanceId())) ended(event,EventEndReason.EXPIRED);
        }
        private void drainOfflineEnds() {
            if (pendingOffline.isEmpty()) return;
            for (ActiveEvent event : List.copyOf(pendingOffline)) endedWhileOffline(event);
            pendingOffline.clear(); save();
        }
        private void ended(ActiveEvent event, EventEndReason reason) {
            invoke(handlers.get(event.definition().id()), event, reason, false);
            lifecycle.fireEnded(event, reason);
            WorldEventServices.fireEnded(view(event), reason == EventEndReason.MANUAL ? WorldEventEndedEvent.Reason.MANUAL : WorldEventEndedEvent.Reason.EXPIRED);
        }
        @Override public void close() {
            thread(); if (closed) return;
            try { if (ready && !faulted) save(); }
            finally {
                closed = true; domains.remove(id, this);
                // Preserve durable instances for restart, but release plugin-owned effects now. No fake expiry.
                for (ActiveEvent event : runtime.activeEvents()) invoke(handlers.get(event.definition().id()), event, EventEndReason.MANUAL, false);
            }
        }
    }

    /** The provider registry for world events of other plugins ({@link WorldEventServices}). @since 0.64.0 */
    public WorldEventServicesInterface services() { return WorldEventServicesInterface.DEFAULT; }
}
