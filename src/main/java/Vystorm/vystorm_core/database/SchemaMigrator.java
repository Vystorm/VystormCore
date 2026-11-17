package Vystorm.vystorm_core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * Minimal versioned schema migration for the shared database. Each plugin (owner = its table prefix) has one row in
 * {@value #VERSION_TABLE}; steps with a higher version than stored are applied in order, the version is saved after
 * every step, so a failed step is retried from there on the next start.
 *
 * <pre>{@code
 * int version = new SchemaMigrator("dinv")
 *     .step(1, "CREATE TABLE IF NOT EXISTS dinv_stash (owner BINARY(16) PRIMARY KEY, data MEDIUMBLOB NOT NULL, updated BIGINT NOT NULL)")
 *     .step(2, "ALTER TABLE dinv_stash ADD COLUMN slots INT NOT NULL DEFAULT 0")
 *     .migrate(dataSource);   // off the server thread, once at startup
 * }</pre>
 *
 * <p>MySQL commits DDL implicitly, so a step with several DDL statements is not atomic: write steps idempotent
 * ({@code IF NOT EXISTS}) or with one DDL statement each. Written for MySQL/MariaDB.
 *
 * @since 0.14.0
 */
public final class SchemaMigrator {
    /** Table holding one version row per owner. */
    public static final String VERSION_TABLE = "vystorm_schema_versions";

    /** One migration step, run with auto-commit off; Core commits afterwards. */
    @FunctionalInterface
    public interface Step {
        void apply(Connection connection) throws SQLException;
    }

    private final String owner;
    private final TreeMap<Integer, Step> steps = new TreeMap<>();

    /** @param owner the plugin's table prefix, same rules as {@link SharedDatabase#tableName} */
    public SchemaMigrator(String owner) {
        this.owner = SharedDatabase.prefix(owner);
    }

    /** Step that lifts the schema to {@code version} (>= 1) by running the SQL statements in order. */
    public SchemaMigrator step(int version, String... sql) {
        Objects.requireNonNull(sql, "sql");
        String[] statements = sql.clone();
        return step(version, connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String each : statements) statement.execute(each);
            }
        });
    }

    /** Step that lifts the schema to {@code version} (>= 1). */
    public SchemaMigrator step(int version, Step step) {
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (steps.putIfAbsent(version, Objects.requireNonNull(step, "step")) != null)
            throw new IllegalArgumentException("duplicate step " + version);
        return this;
    }

    /** Highest registered version (0 without steps). */
    public int latest() { return steps.isEmpty() ? 0 : steps.lastKey(); }

    public String owner() { return owner; }

    /** Stored version of this owner, 0 if none. Creates the version table if needed. */
    public int currentVersion(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            return read(connection);
        }
    }

    /**
     * Applies all pending steps and returns the resulting version. Blocking; call off the server thread.
     *
     * @throws IllegalStateException if the database is newer than the highest registered step
     * @throws SQLException if a step fails (the version of the last successful step is kept)
     */
    public int migrate(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            int current = read(connection);
            if (current > latest())
                throw new IllegalStateException("Schema of '" + owner + "' is version " + current + ", newer than supported " + latest());
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (Map.Entry<Integer, Step> entry : steps.tailMap(current, false).entrySet()) {
                    try {
                        entry.getValue().apply(connection);
                        write(connection, entry.getKey());
                        connection.commit();
                    } catch (SQLException | RuntimeException failure) {
                        try { connection.rollback(); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
                        if (failure instanceof SQLException sql)
                            throw new SQLException("Schema step " + entry.getKey() + " of '" + owner + "' failed: " + sql.getMessage(),
                                    sql.getSQLState(), sql.getErrorCode(), sql);
                        throw failure;
                    }
                    current = entry.getKey();
                }
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return current;
        }
    }

    private static void ensureTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE
                    + " (owner VARCHAR(64) NOT NULL PRIMARY KEY, version INT NOT NULL, updated_at BIGINT NOT NULL)");
        }
    }

    private int read(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT version FROM " + VERSION_TABLE + " WHERE owner = ?")) {
            query.setString(1, owner);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    private void write(Connection connection, int version) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("INSERT INTO " + VERSION_TABLE
                + " (owner, version, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE version = VALUES(version), updated_at = VALUES(updated_at)")) {
            update.setString(1, owner);
            update.setInt(2, version);
            update.setLong(3, System.currentTimeMillis());
            update.executeUpdate();
        }
    }
}
