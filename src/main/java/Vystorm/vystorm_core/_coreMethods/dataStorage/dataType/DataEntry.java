package Vystorm.vystorm_core._coreMethods.dataStorage.dataType;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;


import java.nio.file.Path;

public interface DataEntry {
//file methods have to be implemented in the child classes
// Legacy data layer, file-based only. Deprecated members stay for binary compatibility.
@Deprecated
public FileConfiguration getEntry();
public Path getPath();
public String getName();
public int getDataType();

public void setName(String name);
public void setPath(Path path);
@Deprecated
public void setEntry(FileConfiguration entry);

/** new methods for key-value data entries has to be used with the DataEncoder
 * Name path have to be set before loading and entry getting
 */
public String getEntry(String key);
public void   setEntry(String key, String value);
public void removeEntry(String key);
public boolean containsEntry(String key);
public ConfigurationSection getSection(String key);

public void saveEntry();
public void loadEntry();

}
