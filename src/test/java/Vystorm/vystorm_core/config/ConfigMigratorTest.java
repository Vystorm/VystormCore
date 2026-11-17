package Vystorm.vystorm_core.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigMigratorTest {
    @Test void stepsRunInOrderFromTheStoredVersion() {
        var config = new YamlConfiguration();
        config.set("config-version", 2);config.set("spawn.chance", 5);
        var migrator = new ConfigMigrator("config-version", 4)
                .step(2, c -> fail("already at 2"))
                .step(3, c -> c.set("spawn.chance", c.getInt("spawn.chance") * 2))
                .step(4, c -> { c.set("spawn.rate", c.getInt("spawn.chance")); c.set("spawn.chance", null); });
        assertEquals(2, migrator.migrate(config));
        assertEquals(10, config.getInt("spawn.rate"));
        assertFalse(config.contains("spawn.chance"));
        assertEquals(4, config.getInt("config-version"));
    }

    @Test void newerConfigIsRejected() {
        var config = new YamlConfiguration();config.set("config-version", 7);
        assertThrows(IllegalStateException.class, () -> new ConfigMigrator("config-version", 3).migrate(config));
    }

    @Test void missingIntermediateStepIsSkippedButLaterStepsRun() {
        var config = new YamlConfiguration();config.set("config-version", 1);
        var migrator = new ConfigMigrator("config-version", 3).step(3, c -> c.set("ran", true));
        assertEquals(1, migrator.migrate(config));
        assertTrue(config.getBoolean("ran"));
        assertEquals(3, config.getInt("config-version"));
    }
}
