package Vystorm.vystorm_core._coreMethods.dataStorage;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._coreMethods.dataStorage.methods.*;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

public interface DataStorageInterface {
	/** @deprecated Use {@code api.data().files()}. */
	@Deprecated(since = "0.64.0")
	public Loader loader();
	/** @deprecated Use {@code api.data().files()}. */
	@Deprecated(since = "0.64.0")
	public Creator creator();
	/** @deprecated Use {@code api.data().files()}. */
	@Deprecated(since = "0.64.0")
	public Saver saver();
	/** @deprecated Use {@code api.data().files()}. */
	@Deprecated(since = "0.64.0")
	public Deleter deleter();

/** @deprecated Use {@code api.data().versioned()}. */
@Deprecated(since = "0.64.0")
DataEntry dataEntry(Plugin plugin, String folder, Path path, String filename);

/** @deprecated Use {@code api.data().versioned()}. */
@Deprecated(since = "0.64.0")
void dataEntryPut(Plugin plugin, String folder, DataEntry entry);

/** @deprecated Use {@code api.data().versioned()}. */
@Deprecated(since = "0.64.0")
void dataEntryRemove(Plugin plugin, String folder, Path path);

    /**
     * gives back an integer representing the save state of the data entry
     * 0 = FlatFile
     * 1 = Database
     * 2 = Cloud
     * 3 = SQLite
     * 4 = csv
     *
     * @param plugin
     * @param folder
     * @param path
     * @deprecated Use {@code api.data().versioned()}.
     */
@Deprecated(since = "0.64.0")
int getDataSaveState(Plugin plugin, String folder, Path path);

    /**
     * gives back an integer representing the save state of the data entry
     * 0 = FlatFile
     * 1 = Database
     * 2 = Cloud
     * 3 = SQLite
     * 4 = csv
     * 5 = xml
     *
     * @param plugin
     * @param folder
     * @param path
     */
// NO CHANGE METHOD INCLUDED ON PURPOSE only convert over the converter of DataEntry (legacy data layer, file-based only)
/** @deprecated Nicht implementiert: Es gibt keinen Konverter, der Aufruf ändert nichts. */
@Deprecated
void convertDataEntry(Plugin plugin, String folder, Path path, int newState);

/** @deprecated Nicht implementiert: Der Aufruf schreibt nur eine Log-Zeile. */
@Deprecated
void exportDataEntry(Plugin plugin, String folder, Path path, Path exportPath);

/**
     * gives back the DataEncoder for key-value data entry handling
     */
DataEncoder dataEncoder();


    /**
     * Is meant for stop or reload of the server to save all data entries at once is not intended for regular use
     */
void saveAll();

    /** Atomic file writes and safe YAML reads ({@link AtomicFiles}). @since 0.64.0 */
    default Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files() { return Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT; }

    /** Versioned JSON stores with migrations ({@code storage.VersionedStore}). @since 0.64.0 */
    default Vystorm.vystorm_core.storage.VersionedStoreInterface versioned() { return Vystorm.vystorm_core.storage.VersionedStoreInterface.DEFAULT; }

    /** Previews of data migrations ({@code storage.MigrationPreviews}). @since 0.64.0 */
    default Vystorm.vystorm_core.storage.MigrationPreviewsInterface migrationPreviews() { return Vystorm.vystorm_core.storage.MigrationPreviewsInterface.DEFAULT; }

    /** Players who joined this server: name, last seen, skin address ({@code knownplayers.KnownPlayers}). @since 0.71.0 */
    default Vystorm.vystorm_core.knownplayers.KnownPlayersInterface knownPlayers() { return Vystorm.vystorm_core.knownplayers.KnownPlayersInterface.DEFAULT; }
}
