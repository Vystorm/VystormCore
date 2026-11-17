package Vystorm.vystorm_core._coreMethods.dataStorage.dataType;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public class DataBaseEntry implements DataEntry {
    @Override
    public FileConfiguration getEntry() {
        return null;
    }

    @Override
    public Path getPath() {
        return null;
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public int getDataType() {
        return 1;
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
        return "";
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

    public String getId() {
        return null;
    }

    public void setId(String id) {
    }
}
