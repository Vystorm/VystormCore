package Vystorm.vystorm_core._coreMethods.dataBaseHandler;

import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface.DataBaseType;
import com.zaxxer.hikari.HikariDataSource;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public class ConvertDatabase {
    private final DataBaseType exportType;
    private final DataBaseType importType;

    public ConvertDatabase(HikariDataSource exportDataSource, DataBaseType exportType,
                           HikariDataSource importDataSource, DataBaseType importType) {
        this.exportType = exportType;
        this.importType = importType;
    }

    public void convertTable(String tableName) {
    }

    public DataBaseType getExportType() {
        return exportType;
    }

    public DataBaseType getImportType() {
        return importType;
    }
}
