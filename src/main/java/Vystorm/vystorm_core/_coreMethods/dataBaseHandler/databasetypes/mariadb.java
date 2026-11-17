package Vystorm.vystorm_core._coreMethods.dataBaseHandler.databasetypes;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

        import com.zaxxer.hikari.HikariDataSource;

        import java.sql.*;
        import java.util.List;
        import java.util.ArrayList;

        public class mariadb implements DatabasetypeInterface {

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
                DebugMessages.mid("[mariadb] Database adapter data source assigned");
            }
            @Override
            public HikariDataSource getDataSource() {
                return dataSource;
            }

            @Override
            public boolean doesTableExist(String tableName) {
                DebugMessages.low("[mariadb] doesTableExist requested");
                String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?";
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, tableName);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                } catch (SQLException e) {
                    DebugMessages.high("[mariadb] Database doesTableExist failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
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
                return "SELECT " + columns + " FROM " + tableName + " WHERE " + whereClause + " LIMIT 1";
            }

            @Override
            public List<Object> selectAll(String tableName, String columns) {
                DebugMessages.low("[mariadb] selectAll requested");
                List<Object> results = new ArrayList<>();
                String sql = "SELECT " + columns + " FROM " + tableName;
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(rs.getObject(1));
                    }
                } catch (SQLException e) {
                    DebugMessages.high("[mariadb] Database selectAll failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
                }
                return results;
            }

            @Override
            public List<Object> selectWhere(String tableName, String columns, String whereClause) {
                DebugMessages.low("[mariadb] selectWhere requested");
                List<Object> results = new ArrayList<>();
                String sql = "SELECT " + columns + " FROM " + tableName + " WHERE " + whereClause;
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(rs.getObject(1));
                    }
                } catch (SQLException e) {
                    DebugMessages.high("[mariadb] Database selectWhere failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
                }
                return results;
            }

            @Override
            public String count(String tableName, String whereClause) {
                return "SELECT COUNT(*) FROM " + tableName + " WHERE " + whereClause;
            }

            @Override
            public boolean doesRecordExist(String tableName, String whereClause) {
                DebugMessages.low("[mariadb] doesRecordExist requested");
                String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE " + whereClause;
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                } catch (SQLException e) {
                    DebugMessages.high("[mariadb] Database doesRecordExist failed; state=" + e.getSQLState() + "; code=" + e.getErrorCode());
                }
                return false;
            }

            @Override
            public String connect() {
                return "MariaDB ready (DataSource provided by DataBaseHandler)";
            }

            @Override
            public String disconnect() {
                dataSource = null;
                return "Disconnected from MariaDB";
            }

            @Override
            public boolean isConnected() {
                return dataSource != null && !dataSource.isClosed();
            }
        }
