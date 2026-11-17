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
 * Manager view of {@link AtomicFiles}, reached through {@code api.data().files()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link AtomicFiles}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface AtomicFilesInterface {
    /** The delegating default. Plugins use {@code api.data().files()}. */
    AtomicFilesInterface DEFAULT = new AtomicFilesInterface() {};

    /** See {@link AtomicFiles#write}. */
    default void write(Path file, byte[] bytes) throws IOException { AtomicFiles.write(file, bytes); }

    /** See {@link AtomicFiles#write}. */
    default void write(Path file, FileConfiguration configuration) throws IOException { AtomicFiles.write(file, configuration); }

    /**
     * Moves an unreadable data file aside as {@code <name>.broken-<yyyyMMdd-HHmmss>} so that nothing saves over it.
     *
     * @return the file it was moved to, or {@code file} itself when it could not be moved
     * @since 0.44.0
     */
    default Path moveAside(Path file) { return AtomicFiles.moveAside(file); }

    /**
     * Reads a YAML file. A missing file gives an empty configuration. A file that exists but cannot be read (for
     * example a YAML error after a hand edit) is moved aside with {@link #moveAside}, a severe message is logged and
     * {@code null} is returned: the caller then uses its defaults in memory and must not save over the file until the
     * next restart.
     *
     * @return the loaded configuration, or {@code null} when the file was unreadable and moved aside
     * @since 0.44.0
     */
    default YamlConfiguration readYaml(Path file, java.util.logging.Logger log) { return AtomicFiles.readYaml(file, log); }
}
