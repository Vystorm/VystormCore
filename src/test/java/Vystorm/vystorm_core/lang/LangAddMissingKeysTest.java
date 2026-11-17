package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangAddMissingKeysTest {
    @TempDir Path folder;

    @Test
    void givenOldFileOnDisk_whenUpdateBringsNewKeys_thenOnlyTheNewKeysAreAddedAndCustomTextsStay() throws Exception {
        Path file = folder.resolve("en.yml");
        Files.writeString(file, "# my server texts\ngreeting: \"Hi there (custom)\"\nupdates:\n  checking: \"custom checking\"\n", StandardCharsets.UTF_8);
        String bundled = "greeting: \"Hello\"\nupdates:\n  checking: \"Checking…\"\n  join: \"Updates available\"\nlist:\n  - one\n  - two\n";

        int added = Lang.addMissingKeys(file, bundled);

        assertEquals(2, added);
        YamlConfiguration result = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("Hi there (custom)", result.getString("greeting"));
        assertEquals("custom checking", result.getString("updates.checking"));
        assertEquals("Updates available", result.getString("updates.join"));
        assertEquals(2, result.getStringList("list").size());
        assertTrue(Files.exists(folder.resolve("en.yml.before-update")));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("# my server texts"));
    }

    @Test
    void givenCompleteFile_whenChecked_thenNothingIsWritten() throws Exception {
        Path file = folder.resolve("de.yml");
        Files.writeString(file, "greeting: \"Hallo\"\n", StandardCharsets.UTF_8);
        assertEquals(0, Lang.addMissingKeys(file, "greeting: \"Hello\"\n"));
        assertTrue(Files.notExists(folder.resolve("de.yml.before-update")));
    }
}
