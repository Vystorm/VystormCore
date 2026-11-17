package Vystorm.vystorm_core.database;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SharedDatabaseTest {
    private static final Logger LOG = Logger.getLogger("SharedDatabaseTest");

    @AfterEach
    void tearDown() { SharedDatabase.shutdown(); }

    static SharedDatabaseSettings settings(String host, int port, String db, String user, String password) {
        return new SharedDatabaseSettings(true, "mariadb", host, port, db, user, password, "PREFERRED", true,
                3, 1, 1500, 300_000, 900_000, 60_000, 0, null);
    }

    @Test
    void givenValidPartsWhenTableNameThenLowerCasePrefixed() {
        assertEquals("dinv_stash", SharedDatabase.tableName("dinv", "stash"));
        assertEquals("market_order_log", SharedDatabase.tableName(" Market ", "ORDER_LOG"));
    }

    @Test
    void givenUnsafePartsWhenTableNameThenRejected() {
        for (String[] bad : new String[][]{{"d", "t"}, {"1abc", "t"}, {"a_b", "t"}, {"abc", "t;drop"}, {"abc", "`t`"},
                {"abc", ""}, {null, "t"}, {"abc", null}, {"abcdefghijklmnopq", "t"}, {"abc", "x".repeat(61)}})
            assertThrows(IllegalArgumentException.class, () -> SharedDatabase.tableName(bad[0], bad[1]), String.join("|", String.valueOf(bad[0]), String.valueOf(bad[1])));
    }

    @Test
    void givenDisabledWhenStartedThenNotAvailable() throws Exception {
        SharedDatabase.start(SharedDatabaseSettings.disabled(), LOG, null);
        assertEquals(SharedDatabase.Status.DISABLED, SharedDatabase.status());
        assertTrue(SharedDatabase.dataSource().isEmpty());
        assertTrue(SharedDatabase.whenReady().get(1, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void givenInvalidConfigWhenStartedThenFailedWithoutThrowing() {
        SharedDatabase.start(settings("", 3306, "", "", ""), LOG, null);
        assertEquals(SharedDatabase.Status.FAILED, SharedDatabase.status());
        assertTrue(SharedDatabase.failure().orElseThrow().contains("host is empty"));
    }

    @Test
    void givenUnreachableServerWhenStartedThenFailedAndReadyCompletesEmpty() throws Exception {
        SharedDatabase.start(settings("127.0.0.1", 1, "nothing", "nobody", "x"), LOG, ds -> fail("must not become available"));
        Optional<DataSource> result = SharedDatabase.whenReady().get(30, TimeUnit.SECONDS);
        assertTrue(result.isEmpty());
        assertEquals(SharedDatabase.Status.FAILED, SharedDatabase.status());
        assertFalse(SharedDatabase.available());
        assertTrue(SharedDatabase.failure().isPresent());
        SharedDatabase.shutdown();
        assertEquals(SharedDatabase.Status.CLOSED, SharedDatabase.status());
    }

    @Test
    void givenPendingStartWhenShutdownThenReadyCompletesEmpty() throws Exception {
        SharedDatabase.start(settings("127.0.0.1", 1, "nothing", "nobody", "x"), LOG, null);
        var ready = SharedDatabase.whenReady();
        SharedDatabase.shutdown();
        assertTrue(ready.get(30, TimeUnit.SECONDS).isEmpty());
        assertFalse(SharedDatabase.available());
    }
}
