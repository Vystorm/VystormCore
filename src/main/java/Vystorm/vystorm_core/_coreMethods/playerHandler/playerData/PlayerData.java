package Vystorm.vystorm_core._coreMethods.playerHandler.playerData;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;

public class PlayerData{
	  public   FileConfiguration settings = new org.bukkit.configuration.file.YamlConfiguration();
	  public   HashMap <String, String[]>  temporary = new HashMap<>();
	  public   HashMap <String, DataEntry>  dataEntries = new HashMap<>();

}
