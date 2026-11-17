package Vystorm.vystorm_core._coreMethods.dataStorage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesReadYamlTest {
    @TempDir Path folder;

    @Test
    void givenReadableOrMissingFile_whenRead_thenLoadedOrEmpty() throws Exception {
        Path file = folder.resolve("menu.yml");
        assertNotNull(AtomicFiles.readYaml(file, Logger.getLogger("test")), "missing file: empty configuration");
        Files.writeString(file, "pause-menu: false\n");
        YamlConfiguration yaml = AtomicFiles.readYaml(file, Logger.getLogger("test"));
        assertNotNull(yaml);
        assertFalse(yaml.getBoolean("pause-menu", true));
        assertTrue(Files.exists(file));
    }

    @Test
    void givenUnreadableFile_whenRead_thenMovedAsideUnchangedAndNull() throws Exception {
        Path file = folder.resolve("menu.yml");
        String content = "pause-menu: [false\n  broken: : yaml\n";
        Files.writeString(file, content);
        assertNull(AtomicFiles.readYaml(file, Logger.getLogger("test")));
        assertFalse(Files.exists(file));
        Path aside;
        try (Stream<Path> files = Files.list(folder)) { aside = files.filter(p -> p.getFileName().toString().startsWith("menu.yml.broken-")).findFirst().orElseThrow(); }
        assertEquals(content, Files.readString(aside));
    }
}
