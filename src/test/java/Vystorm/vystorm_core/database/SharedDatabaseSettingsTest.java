package Vystorm.vystorm_core.database;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SharedDatabaseSettingsTest {

    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                SharedDatabaseSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void givenDefaultConfigWhenParsedThenDisabledWithSaneDefaults() {
        SharedDatabaseSettings settings = SharedDatabaseSettings.from(defaults().getConfigurationSection("database.shared"));
        assertFalse(settings.enabled());
        assertEquals("mysql", settings.type());
        assertEquals(3306, settings.port());
        assertEquals("PREFERRED", settings.sslMode());
        assertEquals(8, settings.maximumPoolSize());
        assertEquals(2, settings.minimumIdle());
        assertTrue(settings.maxLifetimeMs() > settings.keepaliveTimeMs());
        assertEquals(SharedDatabaseSettings.disabled().maxLifetimeMs(), settings.maxLifetimeMs());
    }

    @Test
    void givenMissingSectionWhenParsedThenDisabled() {
        assertFalse(SharedDatabaseSettings.from(null).enabled());
    }

    @Test
    void givenGPortalStyleConfigWhenParsedThenUrlAndPropertiesAreBuilt() {
        YamlConfiguration yaml = defaults();
        yaml.set("database.shared.enabled", true);
        yaml.set("database.shared.host", "db.example.invalid");
        yaml.set("database.shared.database", "db_1");
        yaml.set("database.shared.username", "u");
        yaml.set("database.shared.password", "secret-pw");
        yaml.set("database.shared.type", "MariaDB");
        yaml.set("database.shared.properties.socketTimeout", 30000);
        yaml.set("database.shared.properties.sslMode", "REQUIRED");
        SharedDatabaseSettings settings = SharedDatabaseSettings.from(yaml.getConfigurationSection("database.shared"));
        assertTrue(settings.problems().isEmpty(), settings.problems().toString());
        assertEquals("mariadb", settings.type());
        assertEquals("jdbc:mysql://db.example.invalid:3306/db_1", settings.jdbcUrl());
        assertEquals("30000", settings.driverProperties().get("socketTimeout"));
        assertEquals("REQUIRED", settings.driverProperties().get("sslMode"), "user properties override defaults");
        assertFalse(settings.toString().contains("secret-pw"));
    }

    @Test
    void givenBrokenValuesWhenValidatedThenEveryProblemIsListed() {
        SharedDatabaseSettings settings = new SharedDatabaseSettings(true, "postgres", "", 0, "a;b", "", "", "sometimes", false,
                0, 5, 10, 0, 1000, 10, 0, null);
        String problems = String.join("\n", settings.problems());
        for (String expected : new String[]{"type", "host", "port", "database name", "username", "ssl-mode",
                "maximum-size", "minimum-idle", "connection-timeout", "max-lifetime", "keepalive"})
            assertTrue(problems.contains(expected), expected + " missing in " + problems);
    }
}
