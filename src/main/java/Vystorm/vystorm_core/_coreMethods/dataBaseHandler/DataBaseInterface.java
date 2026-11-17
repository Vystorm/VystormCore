package Vystorm.vystorm_core._coreMethods.dataBaseHandler;

import Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes.DatabasetypeInterface;
import Vystorm.vystorm_core.database.SharedDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

public interface DataBaseInterface {
    enum  DataBaseType {
        MYSQL,
        MARIADB,
        POSTGRESQL,
        SQLITE,
        SQLSERVER
    }
    void disconnectALL();
    void registerDataBase(String ID, String dataBaseName, String userName, String password, String host, int port,DataBaseType type);
    void unregisterDataBase(String ID);
    HikariDataSource getDataSource(String ID);
    DatabasetypeInterface getDataBaseTypeHandler(String ID);


    /**
     * Shared Vystorm database (Core {@code config.yml}, {@code database.shared}); empty while disabled, connecting,
     * failed or closed. Use connections off the server thread and close them; never close the DataSource.
     *
     * @since 0.14.0
     */
    default Optional<DataSource> shared() { return SharedDatabase.dataSource(); }

    /** Whether {@link #shared()} is present. @since 0.14.0 */
    default boolean isSharedAvailable() { return SharedDatabase.available(); }

    /** Completes once Core knows whether the shared database is usable (see {@link SharedDatabase#whenReady()}). @since 0.14.0 */
    default CompletableFuture<Optional<DataSource>> sharedReady() { return SharedDatabase.whenReady(); }

    /** Safe table name {@code <prefix>_<table>} for the shared database (see {@link SharedDatabase#tableName}). @since 0.14.0 */
    default String sharedTableName(String pluginPrefix, String table) { return SharedDatabase.tableName(pluginPrefix, table); }

    /** State of the shared pool ({@link SharedDatabase#status()}). @since 0.64.0 */
    default SharedDatabase.Status sharedStatus() { return SharedDatabase.status(); }

    /** Reason of the last failure of the shared pool, without credentials ({@link SharedDatabase#failure()}). @since 0.64.0 */
    default Optional<String> sharedFailure() { return SharedDatabase.failure(); }
}
