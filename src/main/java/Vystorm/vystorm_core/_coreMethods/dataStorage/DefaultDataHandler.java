package Vystorm.vystorm_core._coreMethods.dataStorage;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._coreMethods.dataStorage.methods.*;
import Vystorm.vystorm_core.main;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static Vystorm.vystorm_core.main.getApi;

public class DefaultDataHandler implements DataStorageInterface {

    private final HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>> dataStorage = new HashMap<>();
    // DefaultDataHandler implements DataStorageInterface
    private final Creator creator = new Creator(dataStorage);
    private final Deleter deleter = new Deleter(dataStorage);
    private final Loader loader = new Loader(dataStorage);
    private final Saver saver = new Saver(dataStorage);
    private final DataEncoder encoder = new DataEncoder();

    @Override
    @SuppressWarnings("deprecation")
    public Loader loader() {
        return loader;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Creator creator() {
        return creator;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Saver saver() {
        return saver;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Deleter deleter() {
        return deleter;
    }

    @Override
    @SuppressWarnings("deprecation")
    public DataEntry dataEntry(Plugin plugin, String folder, Path path, String filename) {
        if (dataStorage.containsKey(plugin)) {
            if (dataStorage.get(plugin).containsKey(folder)) {
                if (dataStorage.get(plugin).get(folder).containsKey(path)) {
                    return dataStorage.get(plugin).get(folder).get(path);
                } else {
                    for (DataEntry entry : dataStorage.get(plugin).get(folder).values()) {
                        if (entry.getName().equals(filename)) {
                            return entry;
                        }
                    }
                }

            }
        }
        // Viele Aufrufer fragen optionale Dateien ab (z. B. menubar.yaml); wer den Eintrag braucht, meldet den Fehler selbst.
        DebugMessages.mid("[DefaultDataHandler] DataEntry not found: " + plugin.getName() + " | " + folder + " | " + path + " | ");
        return null;
    }

    /**
     *
     * @param plugin the plugin the entry belongs to
     * @param folder folder schould be a given and may be added to entry but nor really needed as it should be clear what folder it is in if its in use
     * @param entry  the entry to be added
     */
    @Override
    @SuppressWarnings("deprecation")
    public void dataEntryPut(Plugin plugin, String folder, DataEntry entry) {
        if (!dataStorage.containsKey(plugin)) {
            dataStorage.put(plugin, new HashMap<>());
        }
        if (!dataStorage.get(plugin).containsKey(folder)) {
            dataStorage.get(plugin).put(folder, new HashMap<>());
        }
        // Schlüssel ist die Datei (wie im Loader), nicht der Ordner: sonst überschreiben sich Dateien desselben Ordners.
        dataStorage.get(plugin).get(folder).put(DataEntryFiles.file(entry), entry);
        DebugMessages.low("[DDH] Registered data entry; plugin=" + plugin.getName() + "; folder=" + folder);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void dataEntryRemove(Plugin plugin, String folder, Path path) {
        if (dataStorage.containsKey(plugin)) {
            if (dataStorage.get(plugin).containsKey(folder)) {
                HashMap<Path, DataEntry> entries = dataStorage.get(plugin).get(folder);
                Path key = resolveKey(entries, path);
                if (key != null) entries.remove(key);
                DebugMessages.low("[DDH] Data entry removal requested: " + path);
            }
        }

    }

    @Override
    @SuppressWarnings("deprecation")
    public int getDataSaveState(Plugin plugin, String folder, Path path) {
        if (dataStorage.containsKey(plugin)) {
            if (dataStorage.get(plugin).containsKey(folder)) {
                Path key = resolveKey(dataStorage.get(plugin).get(folder), path);
                if (key != null) {
                    DataEntry entry = dataStorage.get(plugin).get(folder).get(key);
                    // Here you would implement logic to determine the save state
                    // For now, we return a default value
                    return entry.getDataType();// Default to FlatFile
                }
            }
        }
        DebugMessages.high("[DDH] " + "DataEntry not found for getting save state: " + plugin.getName() + " | " + folder + " | " + path);
        DebugMessages.high("[DDH] " + "Returning default save state 999 for unknown entry.");
        return 999;
    }


    /** @deprecated Nicht implementiert (Konverter fehlt); der Aufruf ändert nichts. */
    @Deprecated
    @Override
    public void convertDataEntry(Plugin plugin, String folder, Path path, int newState) {
        if (dataStorage.containsKey(plugin)) {
            if (dataStorage.get(plugin).containsKey(folder)) {
                Path key = resolveKey(dataStorage.get(plugin).get(folder), path);
                if (key != null) {
                    DataEntry entry = dataStorage.get(plugin).get(folder).get(key);
                    // Here you would implement logic to convert the data entry to the new state
                    // This is a placeholder implementation
                    DebugMessages.mid("[DefaultDataHandler] Conversion requested (not implemented) for DataEntry: " + entry.getName() + " to state " + newState);
                    switch (entry.getDataType()) {
                        case 0:
                            switch (newState) {
                                case 1:
                                    // Convert from FlatFile to Database
                                    break;
                                case 2:
                                    // Convert from FlatFile to Cloud
                                    break;
                                case 3:
                                    // Convert from FlatFile to SQLite
                                    break;
                                case 4:
                                    // Convert from FlatFile to CSV
                                    break;
                                case 5:
                                    // Convert from FlatFile to XML
                                    break;
                                default:
                                    DebugMessages.high("[DefaultDataHandler] Unknown target data type for conversion: " + newState);
                            }
                            break;
                        case 1:
                            switch (newState) {
                                case 0:
                                    // Convert from Database to FlatFile
                                    break;
                                case 2:
                                    // Convert from Database to Cloud
                                    break;
                                case 3:
                                    // Convert from Database to SQLite
                                    break;
                                case 4:
                                    // Convert from Database to CSV
                                    break;
                                case 5:
                                    // Convert from Database to XML
                                    break;
                                default:
                                    DebugMessages.high("[DefaultDataHandler] Unknown target data type for conversion: " + newState);
                            }
                            break;
                        case 2:
                            switch (newState) {
                                case 0:
                                    // Convert from Cloud to FlatFile
                                    break;
                                case 1:
                                    // Convert from Cloud to Database
                                    break;
                                case 3:
                                    // Convert from Cloud to SQLite
                                    break;
                                case 4:
                                    // Convert from Cloud to CSV
                                    break;
                                case 5:
                                    // Convert from Cloud to XML
                                    break;
                                default:
                                    DebugMessages.high("[DefaultDataHandler] Unknown target data type for conversion: " + newState);
                            }
                            // Convert from Cloud
                            break;
                        case 3:
                            switch (newState) {
                                case 0:
                                    // Convert from SQLite to FlatFile
                                    break;
                                case 1:
                                    // Convert from SQLite to Database
                                    break;
                                case 2:
                                    // Convert from SQLite to Cloud
                                    break;
                                case 4:
                                    // Convert from SQLite to CSV
                                    break;
                                case 5:
                                    // Convert from SQLite to XML
                                    break;
                                default:
                                    DebugMessages.high("[DefaultDataHandler] Unknown target data type for conversion: " + newState);
                            }

                            // Convert from SQLite
                            break;
                        case 4:
                            switch (newState) {
                                case 0:
                                    // Convert from CSV to FlatFile
                                    break;
                                case 1:
                                    // Convert from CSV to Database
                                    break;
                                case 2:
                                    // Convert from CSV to Cloud
                                    break;
                                case 3:
                                    // Convert from CSV to SQLite
                                    break;
                                case 5:
                                    // Convert from CSV to XML
                                    break;
                                default:
                                    DebugMessages.high("[DefaultDataHandler] Unknown target data type for conversion: " + newState);
                            }
                            // Convert from CSV
                            break;
                        case 5:
                            switch (newState) {
                                case 0:
                                    // Convert from XML to FlatFile
                                    break;
                                case 1:
                                    // Convert from XML to Database
                                    break;
                                case 2:
                                    // Convert from XML to Cloud
                                    break;
                                case 3:
                                    // Convert from XML to SQLite
                                    break;
                                case 4:
                                    // Convert from XML to CSV
                                    break;
                                default:
                                    DebugMessages.high("[DefaultDataHandler] Unknown target data type for conversion: " + newState);
                            }
                            // Convert from XML
                            break;
                        default:
                            DebugMessages.high("[DefaultDataHandler] Unknown data type for conversion: " + entry.getDataType());
                    }
                    // Conversion logic goes here
                }
            }
        }
    }

    /**
     * Schlüssel zu einem Pfad: exakt (Datei) oder – für Altaufrufer, die den Ordner übergeben – der einzige Eintrag
     * mit diesem Ordner. Mehrdeutige Ordnerpfade liefern null.
     */
    private static Path resolveKey(HashMap<Path, DataEntry> entries, Path path) {
        if (path == null) return null;
        if (entries.containsKey(path)) return path;
        Path match = null;
        for (Map.Entry<Path, DataEntry> entry : entries.entrySet()) {
            if (!path.equals(entry.getValue().getPath())) continue;
            if (match != null) {
                DebugMessages.mid("[DDH] Ambiguous folder path " + path + "; pass the file path instead");
                return null;
            }
            match = entry.getKey();
        }
        return match;
    }

    /**
     * @deprecated Nicht implementiert (nur Log-Ausgabe). Bleibt aus Kompatibilitätsgründen bestehen.
     */
    @Deprecated
    @Override
    public void exportDataEntry(Plugin plugin, String folder, Path path, Path exportPath) {
        DebugMessages.mid("[DDH] Data export requested; not implemented");

    }

    @Override
    public DataEncoder dataEncoder() {
        return encoder;
    }

    @Override
    public void saveAll() {
        saver.saveAll();
    }
}
