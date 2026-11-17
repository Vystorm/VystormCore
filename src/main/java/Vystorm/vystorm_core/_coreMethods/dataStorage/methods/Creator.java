package Vystorm.vystorm_core._coreMethods.dataStorage.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

public class Creator {
	HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>> dataStorage;

	public Creator(HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>>  map) {
	    this.dataStorage = map;

  }

// Creator class for creating files and directories in the data storage system
  public void createFile(String fileName, Path path) {
    // Logic to create a file with the given name
    // This is a placeholder for the actual implementation
      try {
			    Files.createDirectories(path);
		} catch (IOException e) {
			    DebugMessages.high("[Creator] File or directory creation failed: " + path);
            throw new RuntimeException(e);
		}
      try {
        Path filePath = path.resolve(fileName);
         if(!Files.exists(filePath)) {
                Files.createFile(filePath);
                DebugMessages.low("[Creator] File created: " + filePath);
            }
         } catch (IOException e) {
		    DebugMessages.high("[Creator] File or directory creation failed: " + path);
            throw new RuntimeException(e);
         }
      }


  public void createDatabaseEntry(String entryName, String tableName) {
		DebugMessages.mid("[Creator] Database entry creation requested; not implemented");
        // Legacy data layer, file-based only: database entries are not supported here.
  }
  /**
   * Auto schould be used for every dynamic entry that uses flat or database depending on config settings gets updated later
   * File has to be loaded manually after creation it will not be autoloaded
   * @param fileName
   * @param path
   */
  public void AutoCreate(String fileName, Path path,String setting){
		    // Legacy data layer, file-based only: the setting is ignored.
		    createFile(fileName,path);
  }
}
