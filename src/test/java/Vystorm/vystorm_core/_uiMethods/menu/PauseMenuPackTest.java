package Vystorm.vystorm_core._uiMethods.menu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Localization;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PauseMenuPackTest {
    @Test void givenDifferentCheckoutLineEndings_whenRendered_thenPackBytesAreIdentical() {
        assertArrayEquals(PauseMenuPack.render(OLD_MCMETA + "\n", "de"),
                PauseMenuPack.render(OLD_MCMETA + "\r\n", "de"));
    }
    @Test void olderServerGetsLegacyPackMetadata() throws Exception {
        String result = PauseMenuPack.packMetadata(OLD_MCMETA, "{\"pack_version\":{\"data\":81,\"resource\":64}}");
        assertTrue(result.contains("\"pack_format\":81"));
        assertFalse(result.contains("min_format"));
        assertFalse(result.contains("107"));
    }

    @Test void modernServerGetsItsExactMajorAndMinorFormat() throws Exception {
        for (int[] format : new int[][] {{94, 1}, {101, 1}, {107, 1}, {121, 0}}) {
            String result = PauseMenuPack.packMetadata(OLD_MCMETA,
                    "{\"pack_version\":{\"data_major\":" + format[0] + ",\"data_minor\":" + format[1] + "}}");
            assertTrue(result.contains("\"min_format\":[" + format[0] + "," + format[1] + "]"));
            assertTrue(result.contains("\"max_format\":[" + format[0] + "," + format[1] + "]"));
            assertFalse(result.contains("pack_format"));
        }
    }

    @Test void missingOrInvalidServerFormatCannotInstallAnAssumedFormat() {
        assertThrows(java.io.IOException.class, () -> PauseMenuPack.packMetadata(OLD_MCMETA, "{}"));
        assertThrows(java.io.IOException.class, () -> PauseMenuPack.packMetadata(OLD_MCMETA, "{\"pack_version\":{\"data\":-1}}"));
    }
    /** The German pack as shipped up to Core 0.21.0; the live world has exactly these bytes installed. */
    private static final String OLD_MCMETA = "{\"pack\":{\"description\":\"Vystorm Core: Vystorm-Menü im Pause-Menü (ESC) und als Schnellaktion\",\"min_format\":[107,1],\"max_format\":107}}";

    @TempDir Path dir;

    @AfterEach void reset() { Localization.get().setDefaultLanguage(Localization.AUTO); }

    private static Plugin core() {
        Plugin core = mock(Plugin.class);
        when(core.getResource(anyString())).thenAnswer(i -> PauseMenuPackTest.class.getClassLoader().getResourceAsStream(i.getArgument(0)));
        return core;
    }

    @Test void givenGerman_whenRendered_thenSameBytesAsTheOldPack() throws Exception {
        Plugin core = core();
        Path target = dir.resolve("de");
        assertTrue(PauseMenuPack.write(core, target, "de"));
        assertEquals(OLD_MCMETA + "\n", Files.readString(target.resolve("pack.mcmeta"), StandardCharsets.UTF_8));
        String menu = Files.readString(target.resolve("data/vystorm_core/dialog/menu.json"), StandardCharsets.UTF_8);
        assertTrue(menu.contains("\"external_title\": {\"text\": \"Vystorm-Menü\"}"));
        assertTrue(menu.contains("\"label\": {\"text\": \"⌂ Hauptmenü\"}"));
        assertFalse(menu.contains("{{"));
        assertFalse(PauseMenuPack.write(core, target, "de"), "unchanged pack is not rewritten");
    }

    @Test void givenAuto_whenPackInstalled_thenKeepsItsLanguageElseEnglish() throws Exception {
        Plugin core = core();
        Path installed = dir.resolve("installed");
        PauseMenuPack.write(core, installed, "de");
        assertEquals("de", PauseMenuPack.packLanguage(core, installed, "auto"));
        assertEquals("en", PauseMenuPack.packLanguage(core, dir.resolve("fresh"), "auto"));
        assertEquals("de", PauseMenuPack.packLanguage(core, dir.resolve("fresh"), "de_at"));
        Localization.get().setDefaultLanguage("de");
        assertEquals("de", PauseMenuPack.packLanguage(core, dir.resolve("fresh"), "auto"));
        Path english = dir.resolve("en");
        PauseMenuPack.write(core, english, "en");
        assertTrue(Files.readString(english.resolve("data/vystorm_core/dialog/menu.json")).contains("\"Vystorm Menu\""));
    }
}
