package Vystorm.vystorm_core.worlds;

import Vystorm.vystorm_core.world.WorldEnvironmentRegistry;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

/**
 * Beschreibung einer Plugin-Welt für {@link ManagedWorlds#register}. Unveränderlich; Bau über {@link #builder}.
 *
 * <p>Die Welt ist Wegwerfware: Ihr Ordner trägt die Markierung {@code vystorm-world.yml} (ID, Besitzer,
 * {@link #layoutHash()}). Weicht der Hash ab, löscht Core vor dem Laden {@code region/}, {@code entities/} und
 * {@code poi/}, und der Generator baut die Welt neu. Ordner ohne Markierung, aber mit Chunk-Daten, und schon geladene
 * Welten fasst Core nie an.</p>
 *
 * @since 0.26.0
 */
public final class WorldSpec {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final String id, worldName, layoutHash, generatorId, adminPermission, legacyHashFile;
    private final ChunkGenerator generator;
    private final World.Environment environment;
    private final boolean generateStructures, lockWeather, autoSave;
    private final Map<GameRule<?>, Object> gameRules;
    private final Long time;
    private final Difficulty difficulty;
    private final int viewDistance, simulationDistance;
    private final double borderSize;
    private final SpawnPoint spawn;
    private final WorldEnvironmentRegistry.WorldProfile profile;
    private final RegionSpec region;
    private final Set<Protection> protections;
    private final Visibility visibility;
    private final VisitPolicy policy;
    private final VisitHooks hooks;

    private WorldSpec(Builder b) {
        id = b.id;worldName = b.worldName;generator = b.generator;layoutHash = b.layoutHash;
        environment = b.environment;generateStructures = b.generateStructures;lockWeather = b.lockWeather;autoSave = b.autoSave;
        gameRules = Collections.unmodifiableMap(new LinkedHashMap<>(b.gameRules));
        time = b.time;difficulty = b.difficulty;viewDistance = b.viewDistance;simulationDistance = b.simulationDistance;
        borderSize = b.borderSize;spawn = b.spawn;profile = b.profile;generatorId = b.generatorId;region = b.region;
        protections = Collections.unmodifiableSet(b.protections.isEmpty() ? EnumSet.noneOf(Protection.class) : EnumSet.copyOf(b.protections));
        visibility = b.visibility;policy = b.policy;hooks = b.hooks;adminPermission = b.adminPermission;legacyHashFile = b.legacyHashFile;
    }

    /**
     * @param id        eindeutige ID {@code plugin:name} (klein, z. B. {@code vystormskills:talentwelt})
     * @param worldName Bukkit-Weltname (Ordnername)
     * @param generator Generator, aus dem die Welt bei jedem Neuaufbau entsteht
     */
    public static Builder builder(String id, String worldName, ChunkGenerator generator) { return new Builder(id, worldName, generator); }

    public String id() { return id; }
    public String worldName() { return worldName; }
    public ChunkGenerator generator() { return generator; }
    public World.Environment environment() { return environment; }
    public boolean generateStructures() { return generateStructures; }
    /** Inhalt/Layout-Fingerabdruck; ändert er sich, wird die Welt neu aufgebaut. */
    public String layoutHash() { return layoutHash; }
    public Map<GameRule<?>, Object> gameRules() { return gameRules; }
    /** Feste Tageszeit in Ticks, {@code null} = unverändert. */
    public Long time() { return time; }
    public boolean lockWeather() { return lockWeather; }
    public boolean autoSave() { return autoSave; }
    public Difficulty difficulty() { return difficulty; }
    public int viewDistance() { return viewDistance; }
    public int simulationDistance() { return simulationDistance; }
    /** Durchmesser der Weltgrenze um (0, 0); 0 = unverändert. */
    public double borderSize() { return borderSize; }
    public SpawnPoint spawn() { return spawn; }
    public WorldEnvironmentRegistry.WorldProfile profile() { return profile; }
    /** Name im Core-Generator-Register ({@code api.worldGenerator()}), {@code null} = keiner. */
    public String generatorId() { return generatorId; }
    public RegionSpec region() { return region; }
    public Set<Protection> protections() { return protections; }
    public boolean protects(Protection protection) { return protections.contains(protection); }
    public Visibility visibility() { return visibility; }
    public VisitPolicy policy() { return policy; }
    public VisitHooks hooks() { return hooks; }
    /** Recht, mit dem Admins (Kreativ/Zuschauer, ohne Besuch) den Schutz umgehen und hinein teleportieren dürfen. */
    public String adminPermission() { return adminPermission; }
    /**
     * Name einer älteren Hash-Datei im Weltordner (nur Text = Hash), die als Markierung gilt, solange
     * {@code vystorm-world.yml} fehlt – für Plugins, die ihre Welt vor 0.26.0 selbst verwaltet haben.
     */
    public String legacyHashFile() { return legacyHashFile; }

    @Override
    public String toString() { return "WorldSpec[" + id + " → " + worldName + ", hash " + layoutHash + "]"; }

    public static final class Builder {
        private final String id, worldName;
        private final ChunkGenerator generator;
        private String layoutHash = "", generatorId, adminPermission, legacyHashFile;
        private World.Environment environment = World.Environment.NORMAL;
        private boolean generateStructures, lockWeather, autoSave;
        private final Map<GameRule<?>, Object> gameRules = new LinkedHashMap<>();
        private Long time;
        private Difficulty difficulty;
        private int viewDistance, simulationDistance;
        private double borderSize;
        private SpawnPoint spawn;
        private WorldEnvironmentRegistry.WorldProfile profile;
        private RegionSpec region;
        private final Set<Protection> protections = EnumSet.noneOf(Protection.class);
        private Visibility visibility = Visibility.ALL;
        private VisitPolicy policy = VisitPolicy.builder().build();
        private VisitHooks hooks = VisitHooks.NONE;

        private Builder(String id, String worldName, ChunkGenerator generator) {
            if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("world id must look like plugin:name (lowercase): " + id);
            if (worldName == null || !NAME.matcher(worldName).matches()) throw new IllegalArgumentException("invalid world name: " + worldName);
            this.id = id;this.worldName = worldName;this.generator = Objects.requireNonNull(generator, "generator");
        }

        public Builder layoutHash(String value) { layoutHash = value == null ? "" : value.strip();return this; }
        public Builder environment(World.Environment value) { environment = Objects.requireNonNull(value);return this; }
        public Builder generateStructures(boolean value) { generateStructures = value;return this; }
        public <T> Builder gameRule(GameRule<T> rule, T value) { gameRules.put(Objects.requireNonNull(rule), Objects.requireNonNull(value));return this; }
        public Builder time(long ticks) { time = ticks;return this; }
        public Builder lockWeather(boolean value) { lockWeather = value;return this; }
        /** Standard {@code false}: die Welt wird nie gespeichert (Neuaufbau aus dem Generator). */
        public Builder autoSave(boolean value) { autoSave = value;return this; }
        public Builder difficulty(Difficulty value) { difficulty = value;return this; }
        public Builder viewDistance(int value) { viewDistance = value;return this; }
        public Builder simulationDistance(int value) { simulationDistance = value;return this; }
        public Builder borderSize(double value) { borderSize = value;return this; }
        public Builder spawn(SpawnPoint value) { spawn = value;return this; }
        public Builder profile(WorldEnvironmentRegistry.WorldProfile value) { profile = value;return this; }
        public Builder generatorId(String value) { generatorId = value;return this; }
        public Builder region(RegionSpec value) { region = value;return this; }
        public Builder protect(Protection... values) { protections.addAll(java.util.List.of(values));return this; }
        public Builder protectAll() { protections.addAll(EnumSet.allOf(Protection.class));return this; }
        public Builder visibility(Visibility value) { visibility = Objects.requireNonNull(value);return this; }
        public Builder policy(VisitPolicy value) { policy = Objects.requireNonNull(value);return this; }
        public Builder hooks(VisitHooks value) { hooks = Objects.requireNonNull(value);return this; }
        public Builder adminPermission(String value) { adminPermission = value;return this; }
        public Builder legacyHashFile(String value) { legacyHashFile = value;return this; }

        public WorldSpec build() { return new WorldSpec(this); }
    }
}
