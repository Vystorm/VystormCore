package Vystorm.vystorm_core._uiMethods.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Remembers per plugin whether its first-run setup is still open ({@code setup-state.yml}). A plugin first seen without
 * a config is a new install and stays {@link Status#PENDING} across restarts; one first seen with a config is an existing
 * install and is never nagged. A damaged file is left untouched and nothing counts as pending. @since 0.54.0
 */
final class SetupState {
    enum Status { PENDING, EXISTING, DONE, SKIPPED }
    private final Path file;
    private final YamlConfiguration data = new YamlConfiguration();
    private boolean damaged;

    SetupState(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try { data.loadFromString(Files.readString(file)); }
        catch (IOException | InvalidConfigurationException | RuntimeException e) { damaged = true; }
    }
    private static String key(String plugin) { return "plugins." + plugin.toLowerCase(Locale.ROOT).replace('.', '_'); }

    Status status(String plugin) {
        String raw = data.getString(key(plugin));
        if (raw == null) return null;
        try { return Status.valueOf(raw.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return Status.EXISTING; }
    }
    /** First sighting of a plugin with a {@code setup.yml}; later sightings change nothing. */
    void observe(String plugin, boolean hadConfig) throws IOException {
        if (damaged || status(plugin) != null) return;
        mark(plugin, hadConfig ? Status.EXISTING : Status.PENDING);
    }
    boolean pending(String plugin) { return !damaged && status(plugin) == Status.PENDING; }
    void mark(String plugin, Status status) throws IOException {
        if (damaged) return;
        data.set(key(plugin), status.name().toLowerCase(Locale.ROOT));
        Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(file, data);
    }
}
