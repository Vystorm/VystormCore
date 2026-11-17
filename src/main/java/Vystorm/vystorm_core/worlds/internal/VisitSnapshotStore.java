package Vystorm.vystorm_core.worlds.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Schnappschüsse als {@code <dir>/<uuid>.yml} ({@code plugins/vystorm_core/visits/}). Schreiben ist synchron und atomar
 * ({@code .tmp}, Datei-{@code fsync}, atomares Umbenennen). Linux synchronisiert auch Verzeichniseinträge;
 * unter Windows unterstützt der JDK-Provider das nicht, daher keine Zusage für deren Erhalt bei Stromausfall.
 * Unlesbare Dateien werden als {@code .broken-<millis>} beiseitegelegt, nie gelöscht.
 *
 * <p><b>Format 2</b> (Schlüssel {@code format: 2}): {@code player, world-id, world-name, owner, created-at,
 * return.{world,x,y,z,yaw,pitch}, game-mode, items, extra-items, held-slot, effects[{type,duration,amplifier,ambient,
 * particles,icon}], health, food, saturation, exhaustion, fire-ticks, freeze-ticks, air, fall-distance, allow-flight,
 * flying, fly-speed, walk-speed, collidable, invulnerable}, optional {@code legacy-tool-key}.
 * <b>Format 1</b> ({@code version: 1}) ist der Talentwelt-Schnappschuss von Vystorm Skills 1.16 (gleiche Feldnamen, ohne
 * Extra-Items, Erschöpfung, Frost, Luft, Laufgeschwindigkeit); Skills ergänzt beim Übernehmen {@code world-id},
 * {@code world-name}, {@code owner} und {@code legacy-tool-key}. Neue Felder bekommen beim Lesen Standardwerte, unbekannte
 * Felder werden ignoriert; ein höheres Format als {@link VisitSnapshot#FORMAT} wird nicht gelesen (Datei bleibt liegen).</p>
 *
 * <p>Threadsicher: {@link #exists} und {@link #load} dürfen aus dem asynchronen Login-Ereignis kommen.</p>
 */
public final class VisitSnapshotStore {
    private final Path dir;
    private final WorldsWarnings warnings;
    @FunctionalInterface interface DirectorySync { void force(Path dir) throws IOException; }
    private final DirectorySync directorySync;

    public VisitSnapshotStore(Path dir) {
        this(dir, message -> Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.high("[ManagedWorlds] " + message),
                VisitSnapshotStore::forceDirectory);
    }

    VisitSnapshotStore(Path dir, java.util.function.Consumer<String> log, DirectorySync directorySync) {
        this.dir = dir;this.warnings = new WorldsWarnings(log);this.directorySync = directorySync;
    }

    static void forceDirectory(Path directory) throws IOException {
        Vystorm.vystorm_core.storage.internal.DirectorySync.force(directory);
    }

    public Path dir() { return dir; }

    private Path file(UUID player) { return dir.resolve(player + ".yml"); }

    public boolean exists(UUID player) { return Files.isRegularFile(file(player)); }

    public synchronized void save(VisitSnapshot s) throws IOException {
        saveBytes(s.player(), serialize(s));
    }

    /** Server-thread serialization; worker receives only immutable bytes and UUID. */
    static byte[] serialize(VisitSnapshot s) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", VisitSnapshot.FORMAT);
        yaml.set("player", s.player().toString());
        yaml.set("world-id", s.worldId());
        yaml.set("world-name", s.worldName());
        yaml.set("owner", s.owner());
        yaml.set("created-at", s.createdAt());
        yaml.set("return.world", s.returnWorld());
        yaml.set("return.x", s.x());yaml.set("return.y", s.y());yaml.set("return.z", s.z());
        yaml.set("return.yaw", (double) s.yaw());yaml.set("return.pitch", (double) s.pitch());
        yaml.set("game-mode", s.gameMode());
        yaml.set("items", s.items());
        yaml.set("extra-items", s.extraItems());
        yaml.set("held-slot", s.heldSlot());
        yaml.set("experience-level", s.experienceLevel());
        yaml.set("experience-total", s.experienceTotal());
        yaml.set("experience-progress", s.experienceProgress());
        List<Map<String, Object>> effects = new ArrayList<>();
        for (VisitSnapshot.Effect e : s.effects()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", e.type());map.put("duration", e.duration());map.put("amplifier", e.amplifier());
            map.put("ambient", e.ambient());map.put("particles", e.particles());map.put("icon", e.icon());
            effects.add(map);
        }
        yaml.set("effects", effects);
        yaml.set("health", s.health());yaml.set("food", s.food());yaml.set("saturation", (double) s.saturation());
        yaml.set("exhaustion", (double) s.exhaustion());
        yaml.set("fire-ticks", s.fireTicks());yaml.set("freeze-ticks", s.freezeTicks());yaml.set("air", s.air());
        yaml.set("fall-distance", (double) s.fallDistance());
        yaml.set("allow-flight", s.allowFlight());yaml.set("flying", s.flying());
        yaml.set("fly-speed", (double) s.flySpeed());yaml.set("walk-speed", (double) s.walkSpeed());
        yaml.set("collidable", s.collidable());yaml.set("invulnerable", s.invulnerable());
        if (s.legacyToolKey() != null) yaml.set("legacy-tool-key", s.legacyToolKey());
        return yaml.saveToString().getBytes(StandardCharsets.UTF_8);
    }

    /** Single writer; never holds the monitor used by main-thread recovery while fsync blocks. */
    void saveBytes(UUID player, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        Path target = file(player), tmp = dir.resolve(player + ".yml.tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
            // Test-only crash window; inert unless a test server sets both -Dvystorm.worlds.snapshotDelayMillis and -Dvystorm.worlds.snapshotDelayTest=true.
            long delay = Long.getLong("vystorm.worlds.snapshotDelayMillis", 0L);
            if (delay > 0 && Boolean.getBoolean("vystorm.worlds.snapshotDelayTest")) {
                java.util.logging.Logger.getLogger("VystormCore").warning("[SnapshotWriteTest] in-flight " + player);
                try { Thread.sleep(Math.min(delay, 30_000)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IOException("snapshot test delay interrupted", interrupted); }
            }
            directorySync.force(dir);
        } finally { Files.deleteIfExists(tmp); }
    }

    /** Gespeicherter Schnappschuss; fehlt er oder ist er unlesbar, leer (unlesbar → {@code .broken-<millis>}). */
    @SuppressWarnings("unchecked")
    public synchronized Optional<VisitSnapshot> load(UUID player) {
        Path path = file(player);
        if (!Files.isRegularFile(path)) return Optional.empty();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException broken) {
            setAside(path, player);
            return Optional.empty();
        }
        int format = yaml.contains("format") ? yaml.getInt("format") : yaml.getInt("version", 0);
        if (format > VisitSnapshot.FORMAT) return Optional.empty();   // neueres Core: Datei nicht anfassen
        try {
            if (format < 1 || !yaml.isString("items") || !yaml.isString("return.world")) throw new IllegalArgumentException("required fields missing");
            List<VisitSnapshot.Effect> effects = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList("effects")) {
                Map<String, Object> m = (Map<String, Object>) raw;
                effects.add(new VisitSnapshot.Effect(String.valueOf(m.get("type")), ((Number) m.get("duration")).intValue(),
                        ((Number) m.get("amplifier")).intValue(), Boolean.TRUE.equals(m.get("ambient")), Boolean.TRUE.equals(m.get("particles")),
                        Boolean.TRUE.equals(m.get("icon"))));
            }
            return Optional.of(new VisitSnapshot(format, player, yaml.getString("world-id", ""), yaml.getString("world-name", ""),
                    yaml.getString("owner", ""), yaml.getLong("created-at"), yaml.getString("return.world"),
                    yaml.getDouble("return.x"), yaml.getDouble("return.y"), yaml.getDouble("return.z"),
                    (float) yaml.getDouble("return.yaw"), (float) yaml.getDouble("return.pitch"),
                    yaml.getString("game-mode", "SURVIVAL"), yaml.getString("items"), yaml.getString("extra-items", ""),
                    yaml.getInt("held-slot"), effects, yaml.getDouble("health", 20), yaml.getInt("food", 20),
                    (float) yaml.getDouble("saturation", 5), (float) yaml.getDouble("exhaustion", 0), yaml.getInt("fire-ticks"),
                    yaml.getInt("freeze-ticks", 0), yaml.getInt("air", -1), (float) yaml.getDouble("fall-distance"),
                    yaml.getBoolean("allow-flight"), yaml.getBoolean("flying"), (float) yaml.getDouble("fly-speed", 0.1),
                    (float) yaml.getDouble("walk-speed", -1), yaml.getBoolean("collidable", true), yaml.getBoolean("invulnerable"),
                    yaml.getString("legacy-tool-key"), yaml.getInt("experience-level", -1),
                    yaml.getInt("experience-total", -1), (float) yaml.getDouble("experience-progress", -1)));
        } catch (RuntimeException broken) {
            setAside(path, player);
            return Optional.empty();
        }
    }

    private void setAside(Path path, UUID player) {
        try { Files.move(path, path.resolveSibling(player + ".yml.broken-" + System.currentTimeMillis() + "-" + UUID.randomUUID())); }
        catch (IOException failure) { warnings.warn("set-aside", "Snapshot " + path + " not set aside: " + failure); }
    }

    public synchronized void delete(UUID player) throws IOException { Files.deleteIfExists(file(player)); }

    /** Alle Spieler mit Schnappschuss (sortiert). */
    public List<UUID> all() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<UUID> result = new ArrayList<>();
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yml")).forEach(n -> {
                try { result.add(UUID.fromString(n.substring(0, n.length() - 4))); } catch (IllegalArgumentException ignored) { }
            });
            Collections.sort(result);
            return result;
        } catch (IOException failure) {
            return List.of();
        }
    }
}
