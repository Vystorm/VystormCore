package Vystorm.vystorm_core._coreMethods.dataStorage.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;

public class Deleter {
HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>> dataStorage;
public Deleter(HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>>  map) {
	    this.dataStorage = map;

}
    public void deleteFile(String fileName, Path filePath) {
	  DebugMessages.low("[Deleter] Deleting file: " + fileName);
	  try {
		    Path path = filePath.resolve(fileName);
		    if (java.nio.file.Files.exists(path)) {
			    java.nio.file.Files.delete(path);
			    DebugMessages.mid("[Deleter] File deleted: " + path);
		    } else {
			    DebugMessages.low("[Deleter] Delete skipped; file absent: " + path);
		    }
    } catch (IOException e) {
			DebugMessages.high("[Deleter] Delete operation failed: " + e.getClass().getSimpleName());
            throw new RuntimeException(e);
	  }
    }
    public void deleteDatabaseEntry(String entryName, String tableName) {
		  DebugMessages.mid("[Deleter] Database entry deletion requested; not implemented");
          // Legacy data layer, file-based only: database entries are not supported here.
    }
    /**
     * Auto schould be used for every dynamic entry that uses flat or database depending on config settings gets updated later
     * @param entry
     */
    public void autoDelete(DataEntry entry,String setting){
		  // Legacy data layer, file-based only: the setting is ignored.
	    deleteStorageEntry(entry);
	    Path file = Vystorm.vystorm_core._coreMethods.dataStorage.DataEntryFiles.file(entry);
	    if (file != null && file.getParent() != null) deleteFile(file.getFileName().toString(), file.getParent());
    }
/**
 * Auto schould be used for every dynamic entry that uses flat or database depending on config settings gets updated later
 * @param path
 */
    public void autoDelete(Path path,String setting){
		  // Legacy data layer, file-based only: the setting is ignored.
	    deleteStorageEntry(path);
	    deleteFile(path.getFileName().toString(), path.getParent());
    }

    public void deleteStorageEntry(DataEntry entry){
	    for (Plugin plugin : dataStorage.keySet()) {
		    HashMap<String, HashMap<Path, DataEntry>> pluginData = dataStorage.get(plugin);
		    for (String stringKey : pluginData.keySet()) {
			    HashMap<Path, DataEntry> pathData = pluginData.get(stringKey);
			    pathData.values().removeIf(dataEntry -> dataEntry.equals(entry));
		    }
	    }
    }
    public void deleteStorageEntry(Path path){
	    try {
	    for (Plugin plugin : dataStorage.keySet()) {
		    HashMap<String, HashMap<Path, DataEntry>> pluginData = dataStorage.get(plugin);
		    for (String stringKey : pluginData.keySet()) {
			    HashMap<Path, DataEntry> pathData = pluginData.get(stringKey);
			    pathData.remove(path);
		    }
	    }
	    } catch (Exception e){
		    DebugMessages.high("[Deleter] Delete operation failed: " + e.getClass().getSimpleName());
            throw new RuntimeException(e);
	    }
    }
}
