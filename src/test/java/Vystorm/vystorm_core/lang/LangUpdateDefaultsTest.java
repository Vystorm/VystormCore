package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Changed default texts reach existing servers (0.59.0) while admin edits stay. */
class LangUpdateDefaultsTest {
    @TempDir Path folder;

    @Test
    void givenUneditedAndEditedTexts_whenThePluginChangesItsDefaults_thenOnlyUneditedOnesUpdate() throws Exception {
        Path target = folder.resolve("en.yml");
        String v1 = "menu:\n  close: \"Close\"\n  open: \"Open\"\n";
        Files.writeString(target, "menu:\n  close: \"Close\"\n  open: \"Open it now\"\n", StandardCharsets.UTF_8);   // admin edited "open"
        Lang.writeDefaults(target, v1);
        String v2 = "menu:\n  close: \"Close menu\"\n  open: \"Open menu\"\n";
        assertEquals(1, Lang.updateUnchangedTexts(target, v2));
        String now = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(now.contains("Close menu"), now);
        assertTrue(now.contains("Open it now"), "the admin's text stays: " + now);
    }

    @Test
    void givenNoDefaultsFile_whenUpdating_thenNothingChanges() throws Exception {
        Path target = folder.resolve("de.yml");
        Files.writeString(target, "a: \"alt\"\n", StandardCharsets.UTF_8);
        assertEquals(0, Lang.updateUnchangedTexts(target, "a: \"neu\"\n"));
        assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("alt"));
    }

    @Test
    void givenDefaultsFile_whenWritten_thenItIsNotALanguageFile() {
        assertTrue(Lang.defaultsOf(folder.resolve("en.yml")).getFileName().toString().endsWith(".yml.defaults"));
    }
}
