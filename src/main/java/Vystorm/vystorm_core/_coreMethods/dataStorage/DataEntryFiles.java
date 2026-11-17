package Vystorm.vystorm_core._coreMethods.dataStorage;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import java.nio.file.Path;

/**
 * Einheitliche Datei-Auflösung für Daten-Einträge: {@code getPath()} ist je nach Herkunft der Ordner (Loader) oder
 * schon die Datei. Diese Klasse liefert immer die Datei und dient als Map-Schlüssel im Datenhandler.
 *
 * @since 0.11.2
 */
public final class DataEntryFiles {
    private DataEntryFiles() {}

    /** Datei eines Eintrags; {@code null}, wenn der Eintrag keinen Pfad hat. */
    public static Path file(DataEntry entry) {
        if (entry == null || entry.getPath() == null) return null;
        Path path = entry.getPath();
        Path last = path.getFileName();
        String name = last == null ? "" : last.toString();
        if (name.endsWith(".yml") || name.endsWith(".yaml") || entry.getName() == null || entry.getName().isEmpty()) return path;
        return path.resolve(entry.getName());
    }
}
