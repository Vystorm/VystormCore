package Vystorm.vystorm_core._coreMethods.playerHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.main;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultPlayerHandlerTest {
    @Test
    void givenPlayerNotLoaded_whenSettingAccessed_thenLoadedLazilyAndSavedOnUnload(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("playerData"));
        Files.writeString(folder.resolve("playerData/defaultSettings.yml"), "language: de\n");
        Plugin core = mock(Plugin.class);
        when(core.getDataFolder()).thenReturn(folder.toFile());
        UUID id = UUID.randomUUID();
        try (var mainMock = mockStatic(main.class)) {
            mainMock.when(main::getPlugin).thenReturn(core);
            DefaultPlayerHandler handler = new DefaultPlayerHandler();
            assertFalse(handler.isLoaded(id));
            assertEquals("de", handler.getSetting(id, "language"));
            handler.setSetting(id, "language", "en");
            handler.unloadPlayer(id);
            assertFalse(handler.isLoaded(id));
            assertTrue(Files.readString(folder.resolve("playerData/" + id + "/settings.yml")).contains("language: en"));
            handler.unloadPlayer(id); // nicht geladen: kein Fehler
            try (var files = Files.list(folder.resolve("playerData/" + id))) {
                assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")));
            }
        }
    }
}
