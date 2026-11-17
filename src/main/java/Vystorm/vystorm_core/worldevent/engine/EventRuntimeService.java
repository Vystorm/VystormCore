package Vystorm.vystorm_core.worldevent.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Owns strategic event lifecycle: active instances, cooldowns, conflicts and
 * compatibility/exclusive groups. The service is free of Bukkit world access.
 */
public final class EventRuntimeService {
    private final Clock clock;
    private final RandomGenerator random;
    private final ConcurrentHashMap<UUID, ActiveEvent> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CooldownKey, Instant> cooldowns = new ConcurrentHashMap<>();
    private volatile Consumer<ActiveEvent> expiryListener = event -> { };

    public EventRuntimeService(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Wird für jedes abgelaufene Event genau einmal aufgerufen, egal ob der Ablauf über {@link #expire()} oder
     * nebenbei in {@code start}, {@code blockedEventIds} oder {@code snapshot} erkannt wird.
     */
    public void onExpired(Consumer<ActiveEvent> listener) {
        this.expiryListener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized StartResult start(EventDefinition definition, EventScope scope, String scopeKey) {
        return start(definition, scope, scopeKey, false);
    }

    /**
     * {@code external}: ein anderes Plugin meldet ein bereits laufendes Ereignis (z. B. ein Blutmond aus dessen
     * eigenem Nachtwurf). Der Cooldown gilt dann nicht, Scope- und Gleichzeitigkeitsregeln schon.
     */
    public synchronized StartResult start(EventDefinition definition, EventScope scope, String scopeKey, boolean external) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(scope, "scope");
        String normalizedScopeKey = normalizeScopeKey(scopeKey);
        Instant now = clock.instant();
        expire(now);

        Optional<String> blocked = blockedReason(definition, scope, normalizedScopeKey, now, external);
        if (blocked.isPresent()) return StartResult.blocked(blocked.get());

        Duration duration = chooseDuration(definition.minimumDuration(), definition.maximumDuration());
        ActiveEvent running = new ActiveEvent(
                UUID.randomUUID(), definition, scope, normalizedScopeKey, now, now.plus(duration));
        active.put(running.instanceId(), running);
        cooldowns.put(new CooldownKey(definition.id(), scope, normalizedScopeKey), now.plus(definition.cooldown()));
        return StartResult.started(running);
    }

    public synchronized Optional<String> blockedReason(
            EventDefinition definition,
            EventScope scope,
            String scopeKey) {
        return blockedReason(definition, scope, normalizeScopeKey(scopeKey), clock.instant(), false);
    }

    private Optional<String> blockedReason(EventDefinition definition, EventScope scope, String scopeKey, Instant now) {
        return blockedReason(definition, scope, scopeKey, now, false);
    }

    private Optional<String> blockedReason(
            EventDefinition definition,
            EventScope scope,
            String scopeKey,
            Instant now,
            boolean ignoreCooldown) {
        if (!definition.scopes().contains(scope)) return Optional.of("scope");
        Instant cooldownUntil = cooldowns.get(new CooldownKey(definition.id(), scope, scopeKey));
        if (!ignoreCooldown && cooldownUntil != null && cooldownUntil.isAfter(now)) return Optional.of("cooldown");

        List<ActiveEvent> relevant = relevantActive(scope, scopeKey).stream().filter(event -> !event.expired(now)).toList();
        long sameInstances = relevant.stream().filter(event -> event.definition().id().equals(definition.id())).count();
        if (sameInstances >= definition.maxConcurrent()) return Optional.of("max-concurrent");

        for (ActiveEvent running : relevant) {
            EventDefinition other = running.definition();
            if (definition.conflicts().contains(other.id()) || other.conflicts().contains(definition.id())) {
                return Optional.of("conflict:" + other.id());
            }
            Set<String> overlap = intersection(definition.compatibilityGroups(), other.compatibilityGroups());
            if (!overlap.isEmpty()
                    && !definition.synergies().contains(other.id())
                    && !other.synergies().contains(definition.id())) {
                return Optional.of("compatibility-group:" + overlap.iterator().next());
            }
        }
        return Optional.empty();
    }

    /** Concrete gates without expiry callbacks or changes to live state. */
    public synchronized Map<String,String> diagnosticBlocks(Collection<EventDefinition> definitions, EventScope scope, String scopeKey) {
        String key=normalizeScopeKey(scopeKey); Instant now=clock.instant();
        Map<String,String> result=new java.util.TreeMap<>();
        for (EventDefinition definition : definitions) blockedReason(definition,scope,key,now).ifPresent(reason -> result.put(definition.id(),reason));
        return Map.copyOf(result);
    }

    /** Returns event ids that should be removed before weighted selection. */
    public synchronized Set<String> blockedEventIds(
            Collection<EventDefinition> definitions,
            EventScope scope,
            String scopeKey) {
        Objects.requireNonNull(definitions, "definitions");
        String normalizedScopeKey = normalizeScopeKey(scopeKey);
        Instant now = clock.instant();
        expire(now);
        LinkedHashSet<String> blocked = new LinkedHashSet<>();
        for (EventDefinition definition : definitions) {
            if (blockedReason(definition, scope, normalizedScopeKey, now).isPresent()) blocked.add(definition.id());
        }
        return Set.copyOf(blocked);
    }

    public synchronized boolean stop(UUID instanceId) {
        return active.remove(Objects.requireNonNull(instanceId, "instanceId")) != null;
    }

    public synchronized int expire() {
        return expire(clock.instant());
    }

    private int expire(Instant now) {
        ArrayList<ActiveEvent> removed = new ArrayList<>();
        for (Map.Entry<UUID, ActiveEvent> entry : active.entrySet()) {
            if (entry.getValue().expired(now) && active.remove(entry.getKey(), entry.getValue())) removed.add(entry.getValue());
        }
        cooldowns.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        Consumer<ActiveEvent> listener = expiryListener;
        for (ActiveEvent event : removed) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                // Ein fehlerhafter Listener darf den Ablauf der übrigen Events nicht verhindern; Core loggt gedrosselt.
                Vystorm.vystorm_core.lifecycle.ProviderFailures.report("Core/WorldEvents", event.definition().id(), "expired", failure);
            }
        }
        return removed.size();
    }

    public List<ActiveEvent> activeEvents() {
        return List.copyOf(active.values());
    }

    public List<ActiveEvent> activeEvents(EventScope scope, String scopeKey) {
        return List.copyOf(relevantActive(scope, normalizeScopeKey(scopeKey)));
    }

    public Optional<Instant> cooldownUntil(String eventId, EventScope scope, String scopeKey) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(scope, "scope");
        return Optional.ofNullable(cooldowns.get(new CooldownKey(
                eventId.trim().toLowerCase(Locale.ROOT), scope, normalizeScopeKey(scopeKey))));
    }

    public synchronized RuntimeSnapshot snapshot() {
        expire(clock.instant());
        return copySnapshot();
    }

    /** Immutable diagnostic copy without expiry or listener callbacks; safe for scratch simulations. */
    public synchronized RuntimeSnapshot snapshotReadOnly() {
        return copySnapshot();
    }

    private RuntimeSnapshot copySnapshot() {
        List<PersistedActiveEvent> activeSnapshot = active.values().stream()
                .map(event -> new PersistedActiveEvent(
                        event.instanceId(),
                        event.definition().id(),
                        event.scope(),
                        event.scopeKey(),
                        event.startedAt(),
                        event.endsAt()))
                .toList();
        List<CooldownState> cooldownSnapshot = cooldowns.entrySet().stream()
                .map(entry -> new CooldownState(
                        entry.getKey().eventId(),
                        entry.getKey().scope(),
                        entry.getKey().scopeKey(),
                        entry.getValue()))
                .toList();
        return new RuntimeSnapshot(activeSnapshot, cooldownSnapshot);
    }

    /** Restores only still valid entries that are known to the current catalog. */
    public synchronized RestoreResult restore(RuntimeSnapshot snapshot, EventRegistry registry) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(registry, "registry");
        active.clear();
        cooldowns.clear();
        Instant now = clock.instant();
        int restoredActive = 0;
        int restoredCooldowns = 0;
        int skipped = 0;
        ArrayList<ActiveEvent> expiredWhileOffline = new ArrayList<>();

        for (PersistedActiveEvent persisted : snapshot.activeEvents()) {
            Optional<EventDefinition> definition = registry.find(persisted.eventId());
            if (definition.isEmpty() || !definition.get().scopes().contains(persisted.scope())) {
                skipped++;
                continue;
            }
            if (!persisted.endsAt().isAfter(now)) {
                // Während der Server aus war abgelaufen: das Ende muss noch gemeldet werden.
                expiredWhileOffline.add(new ActiveEvent(persisted.instanceId(), definition.get(), persisted.scope(),
                        persisted.scopeKey(), persisted.startedAt(), persisted.endsAt()));
                skipped++;
                continue;
            }
            ActiveEvent event = new ActiveEvent(
                    persisted.instanceId(),
                    definition.get(),
                    persisted.scope(),
                    persisted.scopeKey(),
                    persisted.startedAt(),
                    persisted.endsAt());
            active.put(event.instanceId(), event);
            restoredActive++;
        }

        for (CooldownState persisted : snapshot.cooldowns()) {
            if (!persisted.until().isAfter(now) || registry.find(persisted.eventId()).isEmpty()) {
                skipped++;
                continue;
            }
            cooldowns.put(new CooldownKey(
                    persisted.eventId(), persisted.scope(), normalizeScopeKey(persisted.scopeKey())), persisted.until());
            restoredCooldowns++;
        }
        return new RestoreResult(restoredActive, restoredCooldowns, skipped, expiredWhileOffline);
    }

    public synchronized void clear() {
        active.clear();
        cooldowns.clear();
    }

    private List<ActiveEvent> relevantActive(EventScope requestedScope, String scopeKey) {
        ArrayList<ActiveEvent> relevant = new ArrayList<>();
        for (ActiveEvent event : active.values()) {
            if (requestedScope == EventScope.GLOBAL) {
                if (event.scope() == EventScope.GLOBAL) relevant.add(event);
                continue;
            }
            if (event.scope() == EventScope.GLOBAL
                    || (event.scope() == requestedScope && event.scopeKey().equals(scopeKey))) {
                relevant.add(event);
            }
        }
        return relevant;
    }

    private Duration chooseDuration(Duration minimum, Duration maximum) {
        long minMillis = minimum.toMillis();
        long maxMillis = maximum.toMillis();
        if (maxMillis <= minMillis) return minimum;
        long range = maxMillis - minMillis;
        long offset = random.nextLong(range + 1L);
        return Duration.ofMillis(minMillis + offset);
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static String normalizeScopeKey(String scopeKey) {
        String normalized = Objects.requireNonNull(scopeKey, "scopeKey").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("scopeKey must not be blank");
        return normalized;
    }

    private record CooldownKey(String eventId, EventScope scope, String scopeKey) {
        private CooldownKey {
            eventId = eventId.trim().toLowerCase(Locale.ROOT);
        }
    }

    public record PersistedActiveEvent(
            UUID instanceId,
            String eventId,
            EventScope scope,
            String scopeKey,
            Instant startedAt,
            Instant endsAt) {
        public PersistedActiveEvent {
            Objects.requireNonNull(instanceId, "instanceId");
            eventId = Objects.requireNonNull(eventId, "eventId").trim().toLowerCase(Locale.ROOT);
            Objects.requireNonNull(scope, "scope");
            scopeKey = normalizeScopeKey(scopeKey);
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endsAt, "endsAt");
        }
    }

    public record CooldownState(String eventId, EventScope scope, String scopeKey, Instant until) {
        public CooldownState {
            eventId = Objects.requireNonNull(eventId, "eventId").trim().toLowerCase(Locale.ROOT);
            Objects.requireNonNull(scope, "scope");
            scopeKey = normalizeScopeKey(scopeKey);
            Objects.requireNonNull(until, "until");
        }
    }

    public record RuntimeSnapshot(List<PersistedActiveEvent> activeEvents, List<CooldownState> cooldowns) {
        public RuntimeSnapshot {
            activeEvents = List.copyOf(Objects.requireNonNull(activeEvents, "activeEvents"));
            cooldowns = List.copyOf(Objects.requireNonNull(cooldowns, "cooldowns"));
        }
    }

    /** {@code expiredWhileOffline}: gespeicherte Events, die während der Server aus war endeten (Ende noch melden). */
    public record RestoreResult(int activeEvents, int cooldowns, int skippedEntries, List<ActiveEvent> expiredWhileOffline) {
        public RestoreResult {
            expiredWhileOffline = List.copyOf(Objects.requireNonNull(expiredWhileOffline, "expiredWhileOffline"));
        }

        public RestoreResult(int activeEvents, int cooldowns, int skippedEntries) {
            this(activeEvents, cooldowns, skippedEntries, List.of());
        }
    }

    public record StartResult(Optional<ActiveEvent> event, Optional<String> blockedReason) {
        public StartResult {
            event = Objects.requireNonNull(event, "event");
            blockedReason = Objects.requireNonNull(blockedReason, "blockedReason");
            if (event.isPresent() == blockedReason.isPresent()) {
                throw new IllegalArgumentException("exactly one result branch must be present");
            }
        }

        public static StartResult started(ActiveEvent event) {
            return new StartResult(Optional.of(event), Optional.empty());
        }

        public static StartResult blocked(String reason) {
            return new StartResult(Optional.empty(), Optional.of(Objects.requireNonNull(reason, "reason")));
        }

        public boolean started() {
            return event.isPresent();
        }
    }
}
