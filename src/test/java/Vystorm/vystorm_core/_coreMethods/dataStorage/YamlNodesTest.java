package Vystorm.vystorm_core._coreMethods.dataStorage;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.YmlEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A loaded YAML document must not keep its SnakeYAML nodes (live heap 30.10.2026: 3.6 million ScalarNodes). */
class YamlNodesTest {
    @TempDir Path folder;

    private static String document(int entries) {
        var text = new StringBuilder("# header\nsection:\n");
        for (int i = 0; i < entries; i++) text.append("  key").append(i).append(": value").append(i).append('\n');
        return text.toString();
    }

    @Test
    void bukkitKeepsTheNodesOfALoadedDocument() throws Exception {
        assertTrue(YamlNodes.supported(), "Paper's YamlConfiguration still has the cache this works around");
        var yaml = new YamlConfiguration();
        yaml.loadFromString(document(500));
        assertTrue(YamlNodes.cached(yaml) >= 1000, "two nodes per entry before the release");
    }

    @Test
    void releaseDropsTheNodesAndKeepsTheValues() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(document(500));
        assertSame(yaml, YamlNodes.release(yaml));
        assertEquals(0, YamlNodes.cached(yaml));
        assertEquals("value42", yaml.getString("section.key42"));
        assertTrue(yaml.saveToString().contains("# header"), "comments survive");
        // Reloading the same object starts from an empty cache again.
        yaml.loadFromString(document(10));
        YamlNodes.release(yaml);
        assertEquals(0, YamlNodes.cached(yaml));
        assertNull(YamlNodes.release(null));
    }

    @Test
    void coreLoadersReleaseTheNodes() throws Exception {
        Path file = folder.resolve("data.yml");
        Files.writeString(file, document(300));
        var read = AtomicFiles.readYaml(file, Logger.getLogger("test"));
        assertEquals(0, YamlNodes.cached(read));
        assertEquals("value7", read.getString("section.key7"));

        var entry = new YmlEntry("data.yml", folder);
        entry.loadEntry();
        assertEquals(0, YamlNodes.cached((YamlConfiguration) entry.getEntry()));
        assertEquals("value299", entry.getEntry("section.key299"));
    }
}
