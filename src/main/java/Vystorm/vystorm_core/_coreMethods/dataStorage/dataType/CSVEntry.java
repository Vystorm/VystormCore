package Vystorm.vystorm_core._coreMethods.dataStorage.dataType;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public class CSVEntry implements DataEntry {
    private final Path path;
    private final String name;

    public CSVEntry(Path path, String name) {
        this.path = path;
        this.name = name;
    }

    @Override
    public FileConfiguration getEntry() {
        return null;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getDataType() {
        return 6;
    }

    @Override
    public void setName(String name) {
    }

    @Override
    public void setPath(Path path) {
    }

    @Override
    public void setEntry(FileConfiguration entry) {
    }

    @Override
    public String getEntry(String key) {
        return null;
    }

    @Override
    public void setEntry(String key, String value) {
    }

    @Override
    public void removeEntry(String key) {
    }

    @Override
    public boolean containsEntry(String key) {
        return false;
    }

    @Override
    public ConfigurationSection getSection(String key) {
        return null;
    }

    @Override
    public void saveEntry() {
    }

    @Override
    public void loadEntry() {
    }
}
