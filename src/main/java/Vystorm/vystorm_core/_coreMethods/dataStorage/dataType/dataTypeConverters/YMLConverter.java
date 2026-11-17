package Vystorm.vystorm_core._coreMethods.dataStorage.dataType.dataTypeConverters;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.YmlEntry;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public class YMLConverter {
    public DataEntry toDataBase(YmlEntry entry) {
        return null;
    }

    public DataEntry toCloud(YmlEntry entry) {
        return null;
    }

    public DataEntry toCSV(YmlEntry entry) {
        return null;
    }

    public DataEntry toSqlite(YmlEntry entry) {
        return null;
    }

    public DataEntry toXML(YmlEntry entry) {
        return null;
    }
}
