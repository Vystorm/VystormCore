package Vystorm.vystorm_core.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runs only against a local test database given by VYSTORM_TEST_DB_HOST/PORT/NAME/USER/PASSWORD
 * (never the live database; the host must be local).
 */
class SharedDatabaseIntegrationTest {
    private static final String HOST = System.getenv("VYSTORM_TEST_DB_HOST");

    @AfterEach
    void tearDown() { SharedDatabase.shutdown(); }

    private static SharedDatabaseSettings local() {
        assumeTrue(HOST != null && !HOST.isBlank(), "VYSTORM_TEST_DB_HOST not set");
        assumeTrue(HOST.equals("127.0.0.1") || HOST.equals("localhost") || HOST.equals("::1"), "only local test databases");
        return SharedDatabaseTest.settings(HOST, Integer.parseInt(System.getenv().getOrDefault("VYSTORM_TEST_DB_PORT", "3306")),
                System.getenv("VYSTORM_TEST_DB_NAME"), System.getenv("VYSTORM_TEST_DB_USER"),
                System.getenv().getOrDefault("VYSTORM_TEST_DB_PASSWORD", ""));
    }

    @Test
    void givenLocalDatabaseWhenStartedThenSharedPoolWorksAndMigratesOnce() throws Exception {
        AtomicReference<DataSource> hooked = new AtomicReference<>();
        SharedDatabase.start(local(), Logger.getLogger("SharedDatabaseIntegrationTest"), hooked::set);
        DataSource ds = SharedDatabase.whenReady().get(30, TimeUnit.SECONDS)
                .orElseThrow(() -> new AssertionError("not available: " + SharedDatabase.failure().orElse("?")));
        assertEquals(SharedDatabase.Status.AVAILABLE, SharedDatabase.status());
        assertSame(ds, SharedDatabase.dataSource().orElseThrow());
        assertSame(ds, hooked.get());
        assertEquals("com.zaxxer.hikari.HikariDataSource", ds.getClass().getName());
        assertEquals(SharedPool.POOL_NAME, ((com.zaxxer.hikari.HikariDataSource) ds).getPoolName());

        String table = SharedDatabase.tableName("itest", "items");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + table);
            s.execute("DELETE FROM " + SchemaMigrator.VERSION_TABLE + " WHERE owner = 'itest'");
        } catch (java.sql.SQLException tableMissing) {
            // first run: version table does not exist yet
        }
        SchemaMigrator migrator = new SchemaMigrator("itest")
                .step(1, "CREATE TABLE IF NOT EXISTS " + table + " (id INT PRIMARY KEY, name VARCHAR(32) NOT NULL)")
                .step(2, "ALTER TABLE " + table + " ADD COLUMN amount INT NOT NULL DEFAULT 0");
        assertEquals(2, migrator.migrate(ds));
        assertEquals(2, migrator.migrate(ds), "second run applies nothing");
        assertEquals(2, migrator.currentVersion(ds));

        try (Connection c = ds.getConnection();
             PreparedStatement insert = c.prepareStatement("INSERT INTO " + table + " (id, name, amount) VALUES (?, ?, ?)")) {
            insert.setInt(1, 1); insert.setString(2, "Ä-Stahl ⚔"); insert.setInt(3, 7);
            assertEquals(1, insert.executeUpdate());
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet row = s.executeQuery("SELECT name, amount FROM " + table + " WHERE id = 1")) {
            assertTrue(row.next());
            assertEquals("Ä-Stahl ⚔", row.getString(1));
            assertEquals(7, row.getInt(2));
        }

        SchemaMigrator tooOld = new SchemaMigrator("itest").step(1, "SELECT 1");
        assertThrows(IllegalStateException.class, () -> tooOld.migrate(ds));
        SchemaMigrator broken = new SchemaMigrator("itest").step(1, "SELECT 1").step(2, "SELECT 1").step(3, "THIS IS NOT SQL");
        assertThrows(java.sql.SQLException.class, () -> broken.migrate(ds));
        assertEquals(2, broken.currentVersion(ds), "failed step keeps the last good version");

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { s.execute("DROP TABLE " + table); }
        SharedDatabase.shutdown();
        assertEquals(SharedDatabase.Status.CLOSED, SharedDatabase.status());
        assertTrue(((com.zaxxer.hikari.HikariDataSource) ds).isClosed());
    }

    @Test
    void givenWrongPasswordWhenStartedThenFailedNotThrown() throws Exception {
        SharedDatabaseSettings good = local();
        SharedDatabaseSettings bad = new SharedDatabaseSettings(true, good.type(), good.host(), good.port(), good.database(),
                good.username(), good.password() + "-wrong", good.sslMode(), true, 2, 0, 3000, 300_000, 900_000, 60_000, 0, null);
        SharedDatabase.start(bad, Logger.getLogger("SharedDatabaseIntegrationTest"), null);
        assertTrue(SharedDatabase.whenReady().get(30, TimeUnit.SECONDS).isEmpty());
        assertEquals(SharedDatabase.Status.FAILED, SharedDatabase.status());
        assertTrue(SharedDatabase.failure().orElseThrow().toLowerCase().contains("denied"), SharedDatabase.failure().orElse(""));
    }
}
