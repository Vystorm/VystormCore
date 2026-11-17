package Vystorm.vystorm_core._coreMethods.dataStorage.dataType;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class YmlEntry implements DataEntry {
    private FileConfiguration entry = new YamlConfiguration();
    private Path path;
    private String name;

    public YmlEntry() {}

    public YmlEntry(String name, Path path) {
        this.name = name;
        this.path = path;
    }

    @Override
    public void removeEntry(String key) { entry.set(key, null); }
    @Override
    public boolean containsEntry(String key) { return entry.contains(key); }
    @Override
    public org.bukkit.configuration.ConfigurationSection getSection(String key) {
        return entry.getConfigurationSection(key);
    }

    @Override
    public FileConfiguration getEntry() { return entry; }
    @Override
    public Path getPath() { return path; }
    @Override
    public String getName() { return name; }
    @Override
    public int getDataType() { return 0; }
    @Override
    public void setName(String name) { this.name = Objects.requireNonNull(name, "name"); }
    @Override
    public void setPath(Path path) { this.path = Objects.requireNonNull(path, "path"); }
    @Override
    public void setEntry(FileConfiguration entry) { this.entry = Objects.requireNonNull(entry, "entry"); }
    @Override
    public String getEntry(String key) { return entry.getString(key); }
    @Override
    public void setEntry(String key, String value) { entry.set(key, value); }

    private Path filePath() {
        Objects.requireNonNull(path, "path");
        // Accept existing callers supplying either a directory or the complete YAML path.
        String filename = path.getFileName().toString();
        if (filename.endsWith(".yml") || filename.endsWith(".yaml")) return path;
        Objects.requireNonNull(name, "name");
        return path.resolve(name.endsWith(".yml") || name.endsWith(".yaml") ? name : name + ".yml");
    }

    @Override
    public synchronized void saveEntry() {
        Path file = filePath().toAbsolutePath();
        Path temporary = null;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
            byte[] bytes = entry.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var output = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // The previous complete document survives an interrupted write.
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            DebugMessages.low("[YmlEntry] YAML entry saved: " + file);
        } catch (java.io.IOException e) {
            DebugMessages.high("[YmlEntry] YAML write failed: " + file);
            throw new java.io.UncheckedIOException("Cannot save " + file, e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { }
            }
        }
    }

    @Override
    public void loadEntry() {
        Path file = filePath();
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            if (Files.exists(file)) loaded.load(file.toFile());
            Vystorm.vystorm_core._coreMethods.dataStorage.YamlNodes.release(loaded);
            entry = loaded;
            DebugMessages.low("[YmlEntry] YAML entry loaded; file exists=" + Files.exists(file));
        } catch (java.io.IOException e) {
            DebugMessages.high("[YmlEntry] YAML read failed: " + file);
            throw new java.io.UncheckedIOException("Cannot load " + file, e);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            DebugMessages.high("[YmlEntry] Invalid YAML file: " + file);
            throw new IllegalArgumentException("Invalid YAML: " + file, e);
        }
    }
}
