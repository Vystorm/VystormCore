package Vystorm.vystorm_core.runtime;

import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, low-coupling runtime context bus for Vystorm plugins.
 *
 * Providers publish semantic snapshots (world events, spawn pressure, faction/city state,
 * structure/worldgen hints, player activity, etc.). Consumers query by channel without
 * depending on the publishing plugin's API.
 *
 * Provider callbacks must be side-effect free and fast. They must never force-load chunks.
 * <p>Preferred access: {@code api.runtime().context()}.
 */
public final class RuntimeContextHub {
    public enum Channel {
        WORLD_EVENT,
        SPAWN,
        STRUCTURE,
        FACTION,
        CITY,
        PLAYER,
        WORLD_GENERATION,
        INFRASTRUCTURE,
        DUNGEON,
        CUSTOM
    }

    public record Query(
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            Optional<UUID> playerId,
            Set<String> tags,
            Map<String, String> attributes) {
        public Query {
            Objects.requireNonNull(worldId, "worldId");
            playerId = Objects.requireNonNull(playerId, "playerId");
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }

        public static Query world(UUID worldId, int x, int y, int z) {
            return new Query(worldId, x, y, z, Optional.empty(), Set.of(), Map.of());
        }

        public Query withPlayer(UUID player) {
            return new Query(worldId, blockX, blockY, blockZ, Optional.ofNullable(player), tags, attributes);
        }
    }

    public record Snapshot(
            String providerId,
            Channel channel,
            int priority,
            long revision,
            Set<String> tags,
            Map<String, String> values,
            Map<String, Double> scores) {
        public Snapshot {
            if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId missing");
            Objects.requireNonNull(channel, "channel");
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
            scores = Map.copyOf(Objects.requireNonNull(scores, "scores"));
            for (double value : scores.values()) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite context score");
            }
        }

        public static Snapshot empty(String providerId, Channel channel, int priority, long revision) {
            return new Snapshot(providerId, channel, priority, revision, Set.of(), Map.of(), Map.of());
        }
    }

    public record MergedSnapshot(
            Channel channel,
            long revision,
            Set<String> tags,
            Map<String, String> values,
            Map<String, Double> scores,
            List<String> providers) {
        public MergedSnapshot {
            tags = Set.copyOf(tags);
            values = Map.copyOf(values);
            scores = Map.copyOf(scores);
            providers = List.copyOf(providers);
        }
    }

    public interface Provider {
        String id();
        Set<Channel> channels();
        default int priority() { return 0; }
        default long revision() { return 0L; }
        default boolean supports(Channel channel, Query query) { return channels().contains(channel); }
        Snapshot snapshot(Channel channel, Query query);
    }

    public record Registration(UUID id, Plugin owner, Provider provider, long order) {}

    private static final RuntimeContextHub INSTANCE = new RuntimeContextHub();
    private final Map<UUID, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<String, UUID> providerIds = new ConcurrentHashMap<>();
    /** Unveränderliche, sortierte Momentaufnahme; wird beim Registrieren atomar getauscht (Leser sehen nie eine leere Zwischenliste). */
    private volatile List<Registration> ordered = List.of();
    private final AtomicLong order = new AtomicLong();

    private RuntimeContextHub() {}

    public static RuntimeContextHub get() { return INSTANCE; }

    public synchronized Registration register(Plugin owner, Provider provider) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(provider, "provider");
        if (provider.id() == null || provider.id().isBlank()) throw new IllegalArgumentException("provider id missing");
        UUID existing = providerIds.get(provider.id().toLowerCase(Locale.ROOT));
        Registration previous = existing == null ? null : registrations.get(existing);
        if (previous != null) Vystorm.vystorm_core.lifecycle.ProviderFailures.replaced("RuntimeContextHub", "Provider " + provider.id(), previous.owner(), owner);
        unregisterProvider(provider.id());
        Registration registration = new Registration(UUID.randomUUID(), owner, provider, order.incrementAndGet());
        registrations.put(registration.id(), registration);
        providerIds.put(provider.id().toLowerCase(Locale.ROOT), registration.id());
        rebuildOrder();
        return registration;
    }

    public synchronized boolean unregister(UUID registrationId) {
        Registration removed = registrations.remove(registrationId);
        if (removed == null) return false;
        providerIds.remove(removed.provider().id().toLowerCase(Locale.ROOT), registrationId);
        rebuildOrder();
        return true;
    }

    public synchronized int unregisterOwner(Plugin owner) {
        int before = registrations.size();
        registrations.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        providerIds.entrySet().removeIf(entry -> !registrations.containsKey(entry.getValue()));
        rebuildOrder();
        return before - registrations.size();
    }

    public synchronized boolean unregisterProvider(String providerId) {
        UUID id = providerIds.remove(providerId.toLowerCase(Locale.ROOT));
        if (id == null) return false;
        registrations.remove(id);
        rebuildOrder();
        return true;
    }

    public List<Snapshot> query(Channel channel, Query query) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(query, "query");
        List<Snapshot> result = new ArrayList<>();
        for (Registration registration : ordered) {
            if (!registration.owner().isEnabled()) continue;
            Provider provider = registration.provider();
            if (!provider.channels().contains(channel) || !provider.supports(channel, query)) continue;
            try {
                Snapshot snapshot = provider.snapshot(channel, query);
                if (snapshot == null) continue;
                if (snapshot.channel() != channel) throw new IllegalStateException("provider returned wrong channel");
                result.add(snapshot);
            } catch (RuntimeException failure) {
                registration.owner().getLogger().warning("RuntimeContext provider " + provider.id() + " failed for " + channel + ": " + failure.getMessage());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Merges snapshots by provider priority. Higher-priority string values win; scores are additive.
     * Tags are unioned. This is intended for director/planner context, not authoritative persistence.
     */
    public MergedSnapshot merged(Channel channel, Query query) {
        List<Snapshot> snapshots = query(channel, query);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        List<String> providers = new ArrayList<>();
        long revision = 1L;
        for (Snapshot snapshot : snapshots) {
            providers.add(snapshot.providerId());
            tags.addAll(snapshot.tags());
            snapshot.values().forEach(values::putIfAbsent);
            snapshot.scores().forEach((key, value) -> scores.merge(key, value, Double::sum));
            revision = 31L * revision + snapshot.revision();
            revision = 31L * revision + snapshot.providerId().hashCode();
        }
        return new MergedSnapshot(channel, revision, tags, values, scores, providers);
    }

    public Collection<Registration> registrations() { return List.copyOf(ordered); }

    private void rebuildOrder() {
        List<Registration> all = new ArrayList<>(registrations.values());
        all.sort(Comparator.<Registration>comparingInt(r -> r.provider().priority()).reversed()
                .thenComparingLong(Registration::order));
        ordered = List.copyOf(all);
    }
}
