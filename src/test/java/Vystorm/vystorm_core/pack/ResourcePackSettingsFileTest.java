package Vystorm.vystorm_core.pack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourcePackSettingsFileTest {
    @TempDir Path folder;

    @Test
    void givenReadableThenUnreadableFile_whenLoaded_thenDefaultsWrittenOnlyWhileReadable() throws Exception {
        Path good = folder.resolve("good").resolve("resource-pack.yml");
        Files.createDirectories(good.getParent());
        Files.writeString(good, "http:\n  port: 9999\n");
        YamlConfiguration yaml = ResourcePacks.loadSettingsYaml(good, Logger.getLogger("test"));
        assertEquals(9999, yaml.getInt("http.port"));
        assertTrue(Files.readString(good).contains("send-on-join"), "missing defaults are written back");

        Path file = folder.resolve("resource-pack.yml");
        String content = "http:\n  port: [9999\n";
        Files.writeString(file, content);
        yaml = ResourcePacks.loadSettingsYaml(file, Logger.getLogger("test"));
        assertTrue(yaml.getBoolean("enabled"), "defaults in memory");
        assertFalse(Files.exists(file), "the broken file is moved aside and not replaced by defaults");
        try (Stream<Path> files = Files.list(folder)) {
            Path aside = files.filter(p -> p.getFileName().toString().startsWith("resource-pack.yml.broken-")).findFirst().orElseThrow();
            assertEquals(content, Files.readString(aside));
        }
        ResourcePacks.loadSettingsYaml(file, Logger.getLogger("test"));
        assertFalse(Files.exists(file), "not written again until a restart");
    }
}
