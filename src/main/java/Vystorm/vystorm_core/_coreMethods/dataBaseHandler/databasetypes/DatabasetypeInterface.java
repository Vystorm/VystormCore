package Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes;
import com.zaxxer.hikari.HikariDataSource;

import java.util.List;

public interface DatabasetypeInterface {
    String getString();
    void setString(String data);

    HikariDataSource getDataSource();

    boolean doesTableExist(String tableName);

    // Tabellen-Operationen
    String createTable(String tableName, String columns);
    String dropTable(String tableName);

    // CRUD-Operationen
    String insert(String tableName, String columns, String values);
    String update(String tableName, String setClause, String whereClause);
    String delete(String tableName, String whereClause);

    // Abfragen
    String selectOne(String tableName, String columns, String whereClause);
    List<Object> selectAll(String tableName, String columns);
    List<Object> selectWhere(String tableName, String columns, String whereClause);

    // Zählen & Prüfen
    String count(String tableName, String whereClause);
    boolean doesRecordExist(String tableName, String whereClause);

    // Verbindung
    String connect();
    String disconnect();
    boolean isConnected();

    void setDataSource(HikariDataSource dataSource);
}


