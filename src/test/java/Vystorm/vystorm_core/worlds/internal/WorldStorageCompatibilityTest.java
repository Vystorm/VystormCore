package Vystorm.vystorm_core.worlds.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorldStorageCompatibilityTest {
    @Test void legacyWorldsKeepTheirNamedContainerFolderAndCase() {
        Path container = Path.of("test-world-container").toAbsolutePath();
        assertEquals(container.resolve("Plugin_World"), BukkitWorldHost.resolveFolder(null, container, "Plugin_World"));
    }

    @Test void keyedWorldsUseOnlyTheLevelDirectoryAndLowercaseKey() {
        Path level = Path.of("test-level").toAbsolutePath();
        assertEquals(level.resolve("dimensions/minecraft/plugin_world"),
                BukkitWorldHost.resolveFolder(level, Path.of("wrong-container"), "Plugin_World"));
    }
}
