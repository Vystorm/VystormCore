package Vystorm.vystorm_core._coreMethods.ressourceLoader;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.main;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;

/**
 * Lädt Standard-Ressourcen (YAML) aus dem Jar des aufrufenden Plugins, legt fehlende Dateien im Datenordner an und
 * ergänzt in bestehenden Dateien fehlende Default-Schlüssel (vorhandene Werte bleiben unverändert).
 */
public class RessourceHandler implements RessourceInterface {
/** Pro Plugin getrennt, damit gleichnamige Ressourcen verschiedener Plugins sich nicht überschreiben. */
Map<Plugin, Map<String, FileConfiguration>> resources = new ConcurrentHashMap<>();

@Override
public void load(ArrayList<String> strings, Plugin plugin) {
     DebugMessages.mid("[RessourceHandler] " + "Starting resource loading for Plugin " + plugin.getName());
     Map<String, FileConfiguration> loaded = loadUp(strings, plugin);
     resources.computeIfAbsent(plugin, ignored -> new ConcurrentHashMap<>()).putAll(loaded);
     DebugMessages.mid("[RessourceHandler] " + "Finished resource loading for Plugin " + plugin.getName());
     try {
	  checkResources(plugin, loaded);
     } catch (Exception e) {
	  DebugMessages.high("[RessourceHandler] " + "Error during resource checking for Plugin " + plugin.getName(), e);
     }
     DebugMessages.mid("[RessourceHandler] " + "Finished resource checking for Plugin " + plugin.getName());
}

/**
 * @param path
 */
@Override
public void resetConfig(String path) {

}

private static InputStream open(Plugin plugin, String path) {
     InputStream stream = plugin.getResource(path);
     if (stream != null) return stream;
     Plugin core = main.getPlugin();
     // Altverhalten: Ressourcen, die nur im Core-Jar liegen, weiterhin finden.
     return core == null || core == plugin ? null : core.getResource(path);
}

private Map<String, FileConfiguration> loadUp(ArrayList<String> paths, Plugin plugin) {
     Map<String, FileConfiguration> loaded = new HashMap<>();
     for (String path : paths) {
	  try (InputStream stream = open(plugin, path)) {
	       if (stream == null) {
		    DebugMessages.high("[RessourceHandler] " + "Resource not found: " + path);
		    continue;
	       }
	       FileConfiguration config = new YamlConfiguration();
	       config.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
	       loaded.put(path, config);
	       DebugMessages.low("[RessourceHandler] " + "Loaded resource: " + path);
	  } catch (Exception e) {
	       DebugMessages.high("[RessourceHandler] " + "Failed to load resource: " + path, e);
	  }
     }
     return loaded;
}

private void checkResources(Plugin plugin, Map<String, FileConfiguration> loaded) {
     for (Map.Entry<String, FileConfiguration> resource : loaded.entrySet()) {
	  String path = resource.getKey();
	  Path savepath = plugin.getDataFolder().toPath().resolve(path);
	  try {
	       if (!Files.exists(savepath)) {
		    AtomicFiles.write(savepath, resource.getValue());
		    DebugMessages.low("[RessourceHandler] " + "Saved new resource to file: " + path);
		    continue;
	       }
	       YamlConfiguration saved = new YamlConfiguration();
	       saved.load(savepath.toFile());
	       int added = mergeMissingKeys(resource.getValue(), saved);
	       if (added > 0) {
		    AtomicFiles.write(savepath, saved);
		    DebugMessages.mid("[RessourceHandler] Added " + added + " missing default keys to " + path);
	       }
	       resources.get(plugin).put(path, saved);
	  } catch (Exception e) {
	       DebugMessages.high("[RessourceHandler] " + "Error checking resource: " + path, e);
	  }
     }
}

/** Ergänzt in {@code saved} alle Blatt-Schlüssel aus {@code defaults}, die fehlen; vorhandene Werte bleiben. */
static int mergeMissingKeys(FileConfiguration defaults, FileConfiguration saved) {
     int added = 0;
     for (String key : defaults.getKeys(true)) {
	  if (defaults.isConfigurationSection(key) || saved.contains(key, true) || blockedByScalarParent(saved, key)) continue;
	  saved.set(key, defaults.get(key));
	  added++;
     }
     return added;
}

private static boolean blockedByScalarParent(FileConfiguration saved, String key) {
     int dot = key.lastIndexOf('.');
     while (dot > 0) {
	  String parent = key.substring(0, dot);
	  if (saved.contains(parent, true) && !saved.isConfigurationSection(parent)) return true;
	  dot = parent.lastIndexOf('.');
     }
     return false;
}
}
