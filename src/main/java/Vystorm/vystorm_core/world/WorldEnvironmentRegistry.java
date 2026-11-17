package Vystorm.vystorm_core.world;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central world-environment detection registry shared by all Vystorm systems.
 *
 * Custom world generators can publish a detector here so WorldDirector, SpawnDirector,
 * WorldForge and later plugins all see the same semantic world profile. A safe built-in
 * fallback still recognizes Bukkit environments and unknown custom ChunkGenerators.
 *
 * Detectors must be side-effect free and must never load chunks.
 * <p>Preferred access: {@code api.worldGenerator().environments()}.
 */
public final class WorldEnvironmentRegistry {
    public enum DimensionKind { OVERWORLD, NETHER, END, AETHER, CUSTOM }
    public enum TerrainKind { GROUND, UNDERGROUND, FLOATING, CEILING, VOID, MIXED }

    public record WorldProfile(
            String id,
            DimensionKind dimension,
            Set<TerrainKind> terrain,
            Set<String> tags,
            String generatorId,
            String generatorClass,
            boolean customGeneration,
            String providerId,
            int priority,
            Map<String, String> attributes) {
        public WorldProfile {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("profile id missing");
            Objects.requireNonNull(dimension, "dimension");
            terrain = Set.copyOf(Objects.requireNonNull(terrain, "terrain"));
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
            generatorId = generatorId == null ? "" : generatorId;
            generatorClass = generatorClass == null ? "" : generatorClass;
            providerId = providerId == null ? "builtin" : providerId;
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }

        public boolean hasTerrain(TerrainKind kind) { return terrain.contains(kind); }
        public boolean hasTag(String tag) { return tags.stream().anyMatch(value -> value.equalsIgnoreCase(tag)); }
    }

    public interface Detector {
        String id();
        default int priority() { return 0; }
        boolean supports(World world);
        WorldProfile detect(World world);
    }

    public record Registration(UUID id, Plugin owner, Detector detector, long order) {}

    private record RegisteredGenerator(String id, ChunkGenerator generator) {}

    private static final WorldEnvironmentRegistry INSTANCE = new WorldEnvironmentRegistry();

    private final Map<UUID, Registration> registrations = new ConcurrentHashMap<>();
    /** Unveränderliche, sortierte Momentaufnahme; wird beim Registrieren atomar getauscht. */
    private volatile List<Registration> ordered = List.of();
    private final Map<String, RegisteredGenerator> generators = new ConcurrentHashMap<>();
    private final Map<UUID, WorldProfile> explicitProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, Plugin> explicitOwners = new ConcurrentHashMap<>();
    private final AtomicLong order = new AtomicLong();

    private WorldEnvironmentRegistry() {}

    public static WorldEnvironmentRegistry get() { return INSTANCE; }

    public synchronized Registration register(Plugin owner, Detector detector) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(detector, "detector");
        if (detector.id() == null || detector.id().isBlank()) throw new IllegalArgumentException("detector id missing");
        registrations.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner)
                && entry.getValue().detector().id().equalsIgnoreCase(detector.id()));
        Registration registration = new Registration(UUID.randomUUID(), owner, detector, order.incrementAndGet());
        registrations.put(registration.id(), registration);
        rebuild();
        return registration;
    }

    public synchronized boolean unregister(UUID registrationId) {
        boolean removed = registrations.remove(registrationId) != null;
        if (removed) rebuild();
        return removed;
    }

    public synchronized int unregisterOwner(Plugin owner) {
        int before = registrations.size();
        registrations.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        explicitOwners.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(owner)) return false;
            explicitProfiles.remove(entry.getKey());
            return true;
        });
        rebuild();
        return before - registrations.size();
    }

    /** Used by Core's generator registry so named generators can be recognized later. */
    public void generatorRegistered(String id, ChunkGenerator generator) {
        if (id == null || id.isBlank() || generator == null) return;
        generators.put(id.toLowerCase(Locale.ROOT), new RegisteredGenerator(id, generator));
    }

    public void generatorUnregistered(String id) {
        if (id != null) generators.remove(id.toLowerCase(Locale.ROOT));
    }

    /** Explicit per-world profile wins over all detector/fallback results. */
    public void setExplicitProfile(Plugin owner, World world, WorldProfile profile) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(profile, "profile");
        explicitProfiles.put(world.getUID(), profile);
        explicitOwners.put(world.getUID(), owner);
    }

    public void clearExplicitProfile(World world) {
        if (world == null) return;
        explicitProfiles.remove(world.getUID());
        explicitOwners.remove(world.getUID());
    }

    public WorldProfile detect(World world) {
        Objects.requireNonNull(world, "world");
        WorldProfile explicit = explicitProfiles.get(world.getUID());
        if (explicit != null) return explicit;

        for (Registration registration : ordered) {
            Detector detector = registration.detector();
            try {
                if (!detector.supports(world)) continue;
                WorldProfile profile = detector.detect(world);
                if (profile != null) return normalize(profile, detector.id(), detector.priority(), world);
            } catch (RuntimeException failure) {
                registration.owner().getLogger().warning("World detector " + detector.id() + " failed for " + world.getName() + ": " + failure.getMessage());
            }
        }
        return fallback(world);
    }

    public Optional<String> registeredGeneratorId(World world) {
        ChunkGenerator active = world == null ? null : world.getGenerator();
        if (active == null) return Optional.empty();
        return generators.values().stream().filter(entry -> entry.generator() == active)
                .map(RegisteredGenerator::id).findFirst();
    }

    public Collection<Registration> registrations() { return List.copyOf(ordered); }

    private WorldProfile fallback(World world) {
        ChunkGenerator generator = world.getGenerator();
        String generatorId = registeredGeneratorId(world).orElse("");
        String generatorClass = generator == null ? "" : generator.getClass().getName();
        boolean custom = generator != null;

        DimensionKind dimension = switch (world.getEnvironment()) {
            case NETHER -> DimensionKind.NETHER;
            case THE_END -> DimensionKind.END;
            case NORMAL -> DimensionKind.OVERWORLD;
            default -> DimensionKind.CUSTOM;
        };

        Set<TerrainKind> terrain = new LinkedHashSet<>();
        terrain.add(TerrainKind.GROUND);
        Set<String> tags = new LinkedHashSet<>();
        tags.add(dimension.name());
        if (custom) tags.add("CUSTOM_GENERATION");
        if (!generatorId.isBlank()) tags.add("CORE_GENERATOR");

        String id = custom ? "custom:" + (generatorId.isBlank() ? generator.getClass().getSimpleName().toLowerCase(Locale.ROOT) : generatorId.toLowerCase(Locale.ROOT))
                : "vanilla:" + dimension.name().toLowerCase(Locale.ROOT);
        return new WorldProfile(id, dimension, terrain, tags, generatorId, generatorClass, custom,
                "core-fallback", Integer.MIN_VALUE, Map.of("world", world.getName(), "environment", world.getEnvironment().name()));
    }

    private static WorldProfile normalize(WorldProfile profile, String detectorId, int priority, World world) {
        String generatorClass = profile.generatorClass().isBlank() && world.getGenerator() != null
                ? world.getGenerator().getClass().getName() : profile.generatorClass();
        return new WorldProfile(profile.id(), profile.dimension(), profile.terrain(), profile.tags(), profile.generatorId(),
                generatorClass, profile.customGeneration(), detectorId, priority, profile.attributes());
    }

    private void rebuild() {
        List<Registration> next = new ArrayList<>(registrations.values());
        next.sort(Comparator.<Registration>comparingInt(entry -> entry.detector().priority()).reversed()
                .thenComparingLong(Registration::order));
        ordered = List.copyOf(next);
    }
}
