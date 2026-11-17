package Vystorm.vystorm_core._coreMethods.dataStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Atomares Schreiben (Temp-Datei, fsync, Move): Ein abgebrochener Schreibvorgang lässt die vorherige vollständige Datei
 * stehen. Beliebiger Thread, solange derselbe Pfad nicht parallel geschrieben wird.
 *
 * <p>Preferred access: {@code api.data().files()}.
 *
 * @since 0.10.3
 */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void write(Path file, byte[] bytes) throws IOException {
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void write(Path file, FileConfiguration configuration) throws IOException {
        write(file, configuration.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Moves an unreadable data file aside as {@code <name>.broken-<yyyyMMdd-HHmmss>} so that nothing saves over it.
     *
     * @return the file it was moved to, or {@code file} itself when it could not be moved
     * @since 0.44.0
     */
    public static Path moveAside(Path file) {
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path aside = file.resolveSibling(file.getFileName() + ".broken-" + stamp);
        for (int i = 2; Files.exists(aside); i++) aside = file.resolveSibling(file.getFileName() + ".broken-" + stamp + "-" + i);
        try {
            Files.move(file, aside);
            return aside;
        } catch (IOException failure) {
            return file;
        }
    }

    /**
     * Reads a YAML file. A missing file gives an empty configuration. A file that exists but cannot be read (for
     * example a YAML error after a hand edit) is moved aside with {@link #moveAside}, a severe message is logged and
     * {@code null} is returned: the caller then uses its defaults in memory and must not save over the file until the
     * next restart.
     *
     * @return the loaded configuration, or {@code null} when the file was unreadable and moved aside
     * @since 0.44.0
     */
    public static YamlConfiguration readYaml(Path file, java.util.logging.Logger log) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) return yaml;
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return YamlNodes.release(yaml);
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            Path aside = moveAside(file);
            log.severe(file.getFileName() + " could not be read (" + failure.getMessage() + "). It was kept as " + aside.getFileName()
                    + "; the defaults are used and nothing is saved to it until the next restart. Fix that file and rename it back to "
                    + file.getFileName() + " before restarting; otherwise the defaults are written next time.");
            return null;
        }
    }
}
