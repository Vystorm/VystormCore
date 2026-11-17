package Vystorm.vystorm_core._coreMethods.dataBaseHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes.DatabasetypeInterface;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;

public class DataBaseHandler implements  DataBaseInterface {
    private ConcurrentHashMap <String, HikariDataSource> dataBases = new ConcurrentHashMap<>();
    private ConcurrentHashMap <String, DataBaseType> dataBaseTypes = new ConcurrentHashMap<>();

    @Override
    public void disconnectALL() {
    dataBases.forEach((key, dataSource) -> {;
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    });
    dataBases.clear();
    DebugMessages.mid("[DataBaseHandler] " + "All databases have been disconnected.");
    }

    @Override
    public void registerDataBase(String ID, String dataBaseName, String userName, String password, String host, int port, DataBaseType type) {
        HikariDataSource dataSource = new HikariDataSource();
        DataSource ds = dataSource;

    switch (type) {
        case MYSQL -> {
            dataSource.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dataBaseName);
            dataSource.setUsername(userName);
            dataSource.setPassword(password);
            dataBaseTypes.put(ID, type);
            dataBases.put(ID, dataSource);
            DebugMessages.mid("[DataBaseHandler] MySQL database '" + ID + "' registered at " + host + ":" + port);
        }
        case MARIADB -> {
            dataSource.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + dataBaseName);
            dataSource.setUsername(userName);
            dataSource.setPassword(password);
            dataBaseTypes.put(ID, type);
            dataBases.put(ID, dataSource);
            DebugMessages.mid("[DataBaseHandler] MariaDB database '" + ID + "' registered at " + host + ":" + port);
        }
        case POSTGRESQL -> {
            dataSource.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + dataBaseName);
            dataSource.setUsername(userName);
            dataSource.setPassword(password);
            dataBaseTypes.put(ID, type);
            dataBases.put(ID, dataSource);
            DebugMessages.mid("[DataBaseHandler] PostgreSQL database '" + ID + "' registered at " + host + ":" + port);
        }
        case SQLITE -> {
            dataSource.setJdbcUrl("jdbc:sqlite:" + dataBaseName);
            dataSource.setUsername(userName);
            dataSource.setPassword(password);
            dataBaseTypes.put(ID, type);
            dataBases.put(ID, dataSource);
            DebugMessages.mid("[DataBaseHandler] SQLite database '" + ID + "' registered: " + dataBaseName);
        }
        case SQLSERVER -> {
            dataSource.setJdbcUrl("jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + dataBaseName);
            dataSource.setUsername(userName);
            dataSource.setPassword(password);
            dataBaseTypes.put(ID, type);
            dataBases.put(ID, dataSource);
            DebugMessages.mid("[DataBaseHandler] SQL Server database '" + ID + "' registered at " + host + ":" + port);
        }
    }
    }


    /**
     * Registers an already configured pool under {@code ID} (used by Core for the shared database). Replaces and closes a
     * different pool with the same ID.
     *
     * @since 0.14.0
     */
    public void registerDataSource(String ID, HikariDataSource dataSource, DataBaseType type) {
        HikariDataSource previous = dataBases.put(ID, dataSource);
        dataBaseTypes.put(ID, type);
        if (previous != null && previous != dataSource && !previous.isClosed()) previous.close();
        DebugMessages.mid("[DataBaseHandler] DataSource '" + ID + "' registered (" + type + ")");
    }

    @Override
    public void unregisterDataBase(String ID) {
        HikariDataSource dataSource = dataBases.get(ID);
        if (dataSource != null) {
            if (!dataSource.isClosed()) {
                dataSource.close();
            }
            dataBases.remove(ID);
            DebugMessages.mid("[DataBaseHandler] " + "Database with ID " + ID + " has been unregistered.");
        } else {
            DebugMessages.mid("[DataBaseHandler] " + "No database found with ID " + ID + ".");
        }

    }

@Override
public HikariDataSource getDataSource(String ID) {
    HikariDataSource dataSource = dataBases.get(ID);
    if (dataSource != null) {
        DebugMessages.low("[DataBaseHandler] DataSource '" + ID + "' retrieved.");
        return dataSource;
    } else {
        DebugMessages.mid("[DataBaseHandler] No DataSource found with ID '" + ID + "'.");
        return null;
    }
}

@Override
public DatabasetypeInterface getDataBaseTypeHandler(String ID) {
    DataBaseType type = dataBaseTypes.get(ID);
    HikariDataSource dataSource = dataBases.get(ID);

    if (type == null || dataSource == null) {
        DebugMessages.mid("[DataBaseHandler] No database found with ID '" + ID + "'.");
        return null;
    }

    DatabasetypeInterface handler = switch (type) {
        case MYSQL -> new Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes.mysql();
        case MARIADB -> new Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes.mariadb();
        case POSTGRESQL -> new Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes.POSTGRESQL();
        case SQLITE -> new Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes.SQLITE();
        case SQLSERVER -> new Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes.SQLSERVER();
    };

    handler.setDataSource(dataSource);
    DebugMessages.low("[DataBaseHandler] Handler for '" + ID + "' (" + type + ") created.");
    return handler;
}

}
