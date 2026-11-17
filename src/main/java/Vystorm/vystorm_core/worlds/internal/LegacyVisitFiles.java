package Vystorm.vystorm_core.worlds.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Administrative recovery of Vystorm Skills 1.16 Talentwelt snapshots. */
final class LegacyVisitFiles {
    private static final String WORLD_ID = "vystormskills:talentwelt";
    private static final String OWNER = "VystormSkills";
    private static final String TOOL_KEY = "vystormskills:tw_item";

    private final Path sessions;
    private final Path archive;
    private final VisitSnapshotStore store;
    private final String worldName;
    private final Consumer<String> log;

    LegacyVisitFiles(Path talentDir, VisitSnapshotStore store, String worldName, Consumer<String> log) {
        sessions = talentDir.resolve("sessions");
        archive = talentDir.resolve("sessions-1.16");
        this.store = store;
        this.worldName = worldName;
        this.log = log;
    }

    String operate(UUID id, String action) {
        try {
            return switch (action) {
                case "archive" -> archive(id) ? "archived" : "nothing";
                case "retry" -> retry(id);
                default -> "failed";
            };
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept("Legacy snapshot " + id + " not processed; source data is kept: " + failure.getMessage());
            return "failed";
        }
    }

    private String retry(UUID id) throws IOException, InvalidConfigurationException {
        Path source = snapshot(id);
        if (!Files.exists(source)) return Files.exists(applied(id)) ? "failed" : "nothing";
        if (!Files.isRegularFile(source)) throw new IOException("Quelle ist keine Datei: " + source);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(source, StandardCharsets.UTF_8));
        if (yaml.getInt("version", 0) != 1 || !yaml.isString("items") || !yaml.isString("return.world"))
            throw new InvalidConfigurationException("not a valid talent world snapshot in format 1");
        if (store.exists(id)) {
            archive(id);
            return "retried";
        }

        yaml.set("world-id", WORLD_ID);
        yaml.set("world-name", worldName);
        yaml.set("owner", OWNER);
        yaml.set("legacy-tool-key", TOOL_KEY);
        writeDurably(id, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        archive(id);
        return "retried";
    }

    /** Writes and forces the Core file before moving or removing any legacy evidence. */
    private void writeDurably(UUID id, byte[] bytes) throws IOException {
        Path dir = store.dir();
        Files.createDirectories(dir);
        Path target = dir.resolve(id + ".yml");
        Path tmp = dir.resolve(id + ".yml.legacy-retry.tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            VisitSnapshotStore.forceDirectory(dir);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Moves every available source without deletion or replacement. */
    private boolean archive(UUID id) throws IOException {
        Path source = snapshot(id), marker = applied(id);
        if (!Files.exists(source) && !Files.exists(marker)) return false;
        Files.createDirectories(archive);
        if (Files.exists(source)) moveWithoutOverwrite(source, archive.resolve(source.getFileName()));
        if (Files.exists(marker)) moveWithoutOverwrite(marker, archive.resolve(marker.getFileName()));
        VisitSnapshotStore.forceDirectory(archive);
        return true;
    }

    private static void moveWithoutOverwrite(Path source, Path preferred) throws IOException {
        Path target = preferred;
        while (true) {
            try {
                Files.move(source, target);
                return;
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                target = preferred.resolveSibling(preferred.getFileName() + "." + UUID.randomUUID());
            }
        }
    }

    private Path snapshot(UUID id) { return sessions.resolve(id + ".yml"); }
    private Path applied(UUID id) { return sessions.resolve(id + ".applied"); }
}
