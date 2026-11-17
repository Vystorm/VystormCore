package Vystorm.vystorm_core._coreMethods.dataStorage.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import org.bukkit.configuration.file.FileConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.HashMap;

public class Saver {

	HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>> dataStorage;
	public Saver(HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>>  map) {
	    this.dataStorage = map;
	}



	/**
	 * Speichert einen Eintrag. YAML-Inhalte werden atomar geschrieben (Temp-Datei + Move) und nur, wenn sich der Inhalt
	 * gegenüber der Datei geändert hat; andere Typen (XML, CSV …) nutzen ihr eigenes {@code saveEntry()}.
	 */
	public  void save(@NotNull DataEntry entry) throws IOException {
        FileConfiguration content = entry.getEntry();
        if (content == null) {
            entry.saveEntry();
            return;
        }
        Path path = entry.getPath();
        String filename = path.getFileName().toString();
        Path file = filename.endsWith(".yml") || filename.endsWith(".yaml")
                ? path : path.resolve(entry.getName());
        try {
            String text = content.saveToString();
            if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
                DebugMessages.low("[Saver] Data entry unchanged: " + file);
                return;
            }
            AtomicFiles.write(file, text.getBytes(StandardCharsets.UTF_8));
            DebugMessages.low("[Saver] Data entry saved: " + file);
        } catch (IOException failure) {
            DebugMessages.high("[Saver] Data entry write failed: " + file);
            throw failure;
        }
    }


	/**
	 * Speichert alle registrierten Einträge. Ein fehlerhafter Eintrag stoppt die übrigen nicht; am Ende wird der erste
	 * Fehler als {@link UncheckedIOException} bzw. RuntimeException geworfen.
	 */
	public void saveAll(){
            DebugMessages.mid("[Saver] Saving registered data; plugins=" + dataStorage.size());
            RuntimeException first = null;
            int failed = 0;
            for (Plugin plugin : new ArrayList<>(dataStorage.keySet())) {
                HashMap<String, HashMap<Path, DataEntry>> pluginData = dataStorage.get(plugin);
                if (pluginData == null) continue;
                for (HashMap<Path, DataEntry> tableData : new ArrayList<>(pluginData.values())) {
                    if (tableData == null) continue;
                    for (DataEntry entry : new ArrayList<>(tableData.values())) {
                        if (entry == null) continue;
                        try {
                            save(entry);
                        } catch (IOException e) {
                            failed++;
                            DebugMessages.high("[Saver] Save failed for " + entry.getPath() + " (" + plugin.getName() + ")", e);
                            if (first == null) first = new UncheckedIOException(e);
                        } catch (RuntimeException e) {
                            failed++;
                            DebugMessages.high("[Saver] Save failed for " + entry.getPath() + " (" + plugin.getName() + ")", e);
                            if (first == null) first = e;
                        }
                    }
                }
            }
            if (first != null) {
                DebugMessages.high("[Saver] " + failed + " data entries could not be saved; all others were written");
                throw first;
            }
	}

public void saveAlll(){
            DebugMessages.mid("[Saver] Legacy save-all requested");
		    try {


				  for (Plugin plugin : dataStorage.keySet()) {
						for (String path : dataStorage.get(plugin).keySet()) {
							    for (DataEntry entry : dataStorage.get(plugin).get(path).values()) {
									  try {
											save(entry);
									  } catch (IOException e) {
											throw new RuntimeException(e);
									  }
							    }
						}
				  }
		    } catch (NullPointerException ignored){ {
            DebugMessages.high("[Saver] Legacy save-all interrupted by missing data");
		    }
}
	//No neeed to save mysql
}
public void autoSave(DataEntry entry,String setting) throws IOException {
		    // Legacy data layer, file-based only: the setting is ignored.
      entry.getEntry().save(entry.getPath().toString());
	}

/**
 * Auto schould be used for every dynamic entry that uses flat or database depending on config settings gets updated later
 * It saves all entries depending on the setting and picks out what to save where
 */
public void autoSave(){
	    // Legacy data layer, file-based only: not implemented, does nothing.
}

/**
 * Speichert den Eintrag an seinem eigenen Pfad.
 * @deprecated {@code of} und {@code plugin} werden ignoriert; direkt {@link #save(DataEntry)} nutzen.
 */
@Deprecated
public void saveDataEntryExtern(DataEntry states, Path of, Plugin plugin) {
     	    try {
		  save(states);
	    } catch (IOException e) {
		  throw new RuntimeException(e);
	    }
}
}
