package Vystorm.vystorm_core.worlds.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Weltordner einer Plugin-Welt: Markierung {@code vystorm-world.yml} (ID, Besitzer, Hash), Entscheidung vor dem Laden und
 * Löschen der erzeugten Daten (Review I3). Gelöscht werden nur {@code region/}, {@code entities/} und {@code poi/} –
 * und nur, wenn die Markierung beweist, dass der Ordner dieser Welt gehört.
 */
public final class WorldFolders {
    public static final String MARKER = "vystorm-world.yml";
    private static final List<String> DATA = List.of("region", "entities", "poi");

    public enum Decision { LOAD, WIPE_AND_LOAD, REFUSE_FOREIGN }

    /** {@code legacy}: aus einer älteren Hash-Datei ({@code WorldSpec.legacyHashFile}) gelesen. */
    public record Marker(String id, String owner, String hash, boolean legacy) {}

    private WorldFolders() {}

    /**
     * @param hasData Ordner enthält Chunk-, Entity- oder POI-Dateien
     * @param marker  gelesene Markierung oder {@code null}
     */
    public static Decision decide(boolean hasData, Marker marker, String id, String hash) {
        if (marker == null) return hasData ? Decision.REFUSE_FOREIGN : Decision.LOAD;
        if (!marker.id().equals(id)) return Decision.REFUSE_FOREIGN;
        return marker.hash().equals(hash) ? Decision.LOAD : Decision.WIPE_AND_LOAD;
    }

    public static boolean hasData(Path folder) {
        for (String sub : DATA) {
            Path dir = folder.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                if (walk.anyMatch(Files::isRegularFile)) return true;
            } catch (IOException unreadable) {
                return true;   // im Zweifel: Daten vorhanden (nie fremde Ordner anfassen)
            }
        }
        return false;
    }

    /** Markierung lesen; ohne {@code vystorm-world.yml} gilt die Legacy-Hash-Datei (falls angegeben) mit {@code id}. */
    public static Optional<Marker> readMarker(Path folder, String id, String legacyHashFile) {
        Path file = folder.resolve(MARKER);
        if (Files.isRegularFile(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                String markerId = yaml.getString("id", ""), hash = yaml.getString("layout-hash", "");
                if (!markerId.isBlank() && yaml.isString("layout-hash"))
                    return Optional.of(new Marker(markerId, yaml.getString("owner", ""), hash, false));
                return Optional.of(new Marker("<unreadable>", "", "", false));
            } catch (IOException | InvalidConfigurationException broken) {
                return Optional.of(new Marker("<unreadable>", "", "", false));   // nie als eigene Welt behandeln
            }
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.of(new Marker("<unreadable>", "", "", false));
        if (legacyHashFile != null && !legacyHashFile.isBlank()) {
            Path legacy = folder.resolve(legacyHashFile);
            try {
                if (Files.isRegularFile(legacy)) return Optional.of(new Marker(id, "", Files.readString(legacy, StandardCharsets.UTF_8).strip(), true));
            } catch (IOException unreadable) {
                return Optional.of(new Marker("<unreadable>", "", "", true));
            }
        }
        return Optional.empty();
    }

    public static void writeMarker(Path folder, Marker marker) throws IOException {
        Files.createDirectories(folder);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", marker.id());
        yaml.set("owner", marker.owner());
        yaml.set("layout-hash", marker.hash());
        yaml.set("written-at", System.currentTimeMillis());
        Path tmp = folder.resolve(MARKER + ".tmp");
        Files.writeString(tmp, yaml.saveToString(), StandardCharsets.UTF_8);
        try { Files.move(tmp, folder.resolve(MARKER), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(tmp, folder.resolve(MARKER), StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Löscht {@code region/}, {@code entities/}, {@code poi/}; liefert jede Datei, die nicht gelöscht werden konnte. */
    public static List<String> wipe(Path folder) {
        List<String> failures = new ArrayList<>();
        for (String sub : DATA) {
            Path dir = folder.resolve(sub);
            if (!Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(path); } catch (IOException failure) { failures.add(path + ": " + failure); }
                }
            } catch (IOException failure) {
                failures.add(dir + ": " + failure);
            }
        }
        return failures;
    }

}
