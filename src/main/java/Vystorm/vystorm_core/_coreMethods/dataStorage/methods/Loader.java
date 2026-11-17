package Vystorm.vystorm_core._coreMethods.dataStorage.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.YmlEntry;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

public class Loader {
    private final HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>> dataStorage;

    public Loader(HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>> map) {
        this.dataStorage = map;
    }

    private Path resolve(Plugin plugin, Path path) {
        Path base = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(base)) return absolute;
        // Legacy callers use /debug/file.yml to mean a path inside the plugin folder.
        return base.resolve(path.toString().replaceFirst("^[/\\\\]+", "")).normalize();
    }

    @Deprecated
    public boolean loadFolder(Plugin plugin, Path path) throws IOException {
        Path folder = resolve(plugin, path);
        if (!Files.isDirectory(folder)) {
            DebugMessages.low("[Loader] Folder unavailable: " + folder);
            return false;
        }
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).toList()) {
                try {
                    loadFile(plugin, file);
                } catch (InvalidConfigurationException e) {
                    DebugMessages.high("[Loader] Invalid YAML while loading folder entry: " + file);
                    throw new IOException("Invalid YAML: " + file, e);
                }
            }
        }
        return true;
    }

    @Deprecated
    public boolean loadFile(Plugin plugin, Path path) throws IOException, InvalidConfigurationException {
        Path file = resolve(plugin, path);
        if (!Files.isRegularFile(file)) {
            DebugMessages.low("[Loader] File unavailable: " + file);
            return false;
        }
        DataEntry entry = loadEntry(file);
        dataStorage.computeIfAbsent(plugin, ignored -> new HashMap<>())
                .computeIfAbsent(file.getParent().getFileName().toString(), ignored -> new HashMap<>())
                .put(file, entry);
        return true;
    }

    public boolean autoLoad(Plugin plugin, Path path) throws IOException, InvalidConfigurationException {
        Path resolved = resolve(plugin, path);
        return Files.isDirectory(resolved) ? loadFolder(plugin, resolved) : loadFile(plugin, resolved);
    }

    public boolean fileExists(Plugin plugin, Path path) {
        return Files.exists(resolve(plugin, path));
    }

    private DataEntry loadEntry(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.load(file.toFile());
        Vystorm.vystorm_core._coreMethods.dataStorage.YamlNodes.release(configuration);
        DataEntry entry = new YmlEntry();
        entry.setName(file.getFileName().toString());
        entry.setPath(file.getParent());
        entry.setEntry(configuration);
        DebugMessages.low("[Loader] YAML file loaded: " + file);
        return entry;
    }

    public DataEntry loadSpecificEntryExtern(Path path, Plugin plugin) {
        Path file = resolve(plugin, path);
        if (!Files.isRegularFile(file)) {
            DebugMessages.low("[Loader] Optional YAML file absent: " + file);
            return null;
        }
        try {
            return loadEntry(file);
        } catch (IOException e) {
            DebugMessages.high("[Loader] YAML read failed: " + file);
            throw new UncheckedIOException("Cannot load " + file, e);
        } catch (InvalidConfigurationException e) {
            DebugMessages.high("[Loader] Invalid YAML file: " + file);
            throw new IllegalArgumentException("Invalid YAML: " + file, e);
        }
    }

    public void replaceEntryData(Plugin plugin, Path from, Path to) {
        Path target = resolve(plugin, to);
        try {
            DataEntry source = loadEntry(resolve(plugin, from));
            Files.createDirectories(target.getParent());
            source.getEntry().save(target.toFile());
            DebugMessages.mid("[Loader] YAML replacement saved: " + target);
            loadFile(plugin, target);
        } catch (IOException e) {
            DebugMessages.high("[Loader] YAML replacement failed: " + target);
            throw new UncheckedIOException("Cannot replace " + target, e);
        } catch (InvalidConfigurationException e) {
            DebugMessages.high("[Loader] YAML replacement source is invalid: " + from);
            throw new IllegalArgumentException("Invalid YAML: " + from, e);
        }
    }
}
