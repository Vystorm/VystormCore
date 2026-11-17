package Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

    import com.zaxxer.hikari.HikariDataSource;

    import java.sql.*;
    import java.util.List;
    import java.util.ArrayList;

    public class SQLSERVER implements DatabasetypeInterface {

        private String connectionString;
        private HikariDataSource dataSource;

        @Override
        public String getString() {
            return connectionString;
        }

        @Override
        public void setString(String data) {
            this.connectionString = data;
        }
        @Override

        public void setDataSource(HikariDataSource dataSource) {
            this.dataSource = dataSource;
                DebugMessages.mid("[SQLSERVER] Database adapter data source assigned");
        }
        @Override

        public HikariDataSource getDataSource() {
            return dataSource;
        }

        private Connection getConnection() throws SQLException {
            if (dataSource == null || dataSource.isClosed()) {
                throw new SQLException("DataSource not available or closed");
            }
            return dataSource.getConnection();
        }

        @Override
        public boolean doesTableExist(String tableName) {
                DebugMessages.low("[SQLSERVER] doesTableExist requested");
            String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, tableName);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            } catch (SQLException e) {
                DebugMessages.high("[SQLSERVER] Database doesTableExist failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
            }
            return false;
        }

        @Override
        public String createTable(String tableName, String columns) {
            return "CREATE TABLE " + tableName + " (" + columns + ")";
        }

        @Override
        public String dropTable(String tableName) {
            return "DROP TABLE " + tableName;
        }

        @Override
        public String insert(String tableName, String columns, String values) {
            return "INSERT INTO " + tableName + " (" + columns + ") VALUES (" + values + ")";
        }

        @Override
        public String update(String tableName, String setClause, String whereClause) {
            return "UPDATE " + tableName + " SET " + setClause + " WHERE " + whereClause;
        }

        @Override
        public String delete(String tableName, String whereClause) {
            return "DELETE FROM " + tableName + " WHERE " + whereClause;
        }

        @Override
        public String selectOne(String tableName, String columns, String whereClause) {
            return "SELECT TOP 1 " + columns + " FROM " + tableName + " WHERE " + whereClause;
        }

        @Override
        public List<Object> selectAll(String tableName, String columns) {
                DebugMessages.low("[SQLSERVER] selectAll requested");
            List<Object> results = new ArrayList<>();
            String sql = "SELECT " + columns + " FROM " + tableName;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getObject(1));
                }
            } catch (SQLException e) {
                DebugMessages.high("[SQLSERVER] Database selectAll failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
            }
            return results;
        }

        @Override
        public List<Object> selectWhere(String tableName, String columns, String whereClause) {
                DebugMessages.low("[SQLSERVER] selectWhere requested");
            List<Object> results = new ArrayList<>();
            String sql = "SELECT " + columns + " FROM " + tableName + " WHERE " + whereClause;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getObject(1));
                }
            } catch (SQLException e) {
                DebugMessages.high("[SQLSERVER] Database selectWhere failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
            }
            return results;
        }

        @Override
        public String count(String tableName, String whereClause) {
            return "SELECT COUNT(*) FROM " + tableName + " WHERE " + whereClause;
        }

        @Override
        public boolean doesRecordExist(String tableName, String whereClause) {
                DebugMessages.low("[SQLSERVER] doesRecordExist requested");
            String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE " + whereClause;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            } catch (SQLException e) {
                DebugMessages.high("[SQLSERVER] Database doesRecordExist failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
            }
            return false;
        }

        @Override
        public String connect() {
            return "SQL Server ready (DataSource provided by DataBaseHandler)";
        }

        @Override
        public String disconnect() {
            return "Disconnected from SQL Server";
        }

        @Override
        public boolean isConnected() {
            return dataSource != null && !dataSource.isClosed();
        }
    }
