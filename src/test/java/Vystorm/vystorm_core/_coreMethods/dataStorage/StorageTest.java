package Vystorm.vystorm_core._coreMethods.dataStorage;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.YmlEntry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageTest {
    @TempDir Path directory;

    @Test
    void givenYamlEntryWhenSavedTwiceThenReloadLatestData() {
        YmlEntry entry = new YmlEntry();
        entry.setPath(directory.resolve("nested"));
        entry.setName("items.yml");
        entry.setEntry("item", "base64::first");
        entry.saveEntry();
        entry.setEntry("item", "base64::second");
        entry.saveEntry();
        YmlEntry loaded = new YmlEntry();
        loaded.setPath(directory.resolve("nested/items.yml"));
        loaded.loadEntry();
        assertEquals("base64::second", loaded.getEntry("item"));
    }

    @Test
    void givenFailedAtomicReplacementThenPreserveExistingDestination() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("protected.yml"));
        Path marker = destination.resolve("existing");
        Files.writeString(marker, "preserved");
        YmlEntry entry = new YmlEntry("protected.yml", destination);
        entry.setEntry("value", "replacement");
        assertThrows(java.io.UncheckedIOException.class, entry::saveEntry);
        assertEquals("preserved", Files.readString(marker));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "failed writes must clean their temporary files");
        }
    }

    @Test
    void givenLegacyRelativePathWhenLoadedThenSaveInsidePluginFolder() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        DefaultDataHandler data = new DefaultDataHandler();
        Files.createDirectories(directory.resolve("debug"));
        Files.writeString(directory.resolve("debug/states.yml"), "state: first\n");
        var entry = data.loader().loadSpecificEntryExtern(Path.of("/debug/states.yml"), plugin);
        assertEquals("first", entry.getEntry("state"));
        entry.setEntry("state", "second");
        data.saver().save(entry);
        assertEquals("second", data.loader().loadSpecificEntryExtern(Path.of("debug/states.yml"), plugin).getEntry("state"));
    }

    @Test
    void givenSeveralFilesWhenLoadingFolderThenKeepEachEntry() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Path folder = Files.createDirectory(directory.resolve("items"));
        Files.writeString(folder.resolve("first.yml"), "amount: 1\n");
        Files.writeString(folder.resolve("second.yml"), "amount: 2\n");
        DefaultDataHandler data = new DefaultDataHandler();
        assertTrue(data.loader().autoLoad(plugin, Path.of("items")));
        var first = data.dataEntry(plugin, "items", folder.resolve("first.yml"), "first.yml");
        var second = data.dataEntry(plugin, "items", folder.resolve("second.yml"), "second.yml");
        assertNotSame(first, second);
        first.setEntry("amount", "3");
        second.setEntry("amount", "4");
        data.saveAll();
        assertEquals("3", data.loader().loadSpecificEntryExtern(folder.resolve("first.yml"), plugin).getEntry("amount"));
        assertEquals("4", data.loader().loadSpecificEntryExtern(folder.resolve("second.yml"), plugin).getEntry("amount"));
    }

    @Test
    void givenMalformedYamlWhenLoadedThenFailWithoutOverwriting() throws Exception {
        Path file = directory.resolve("broken.yml");
        String invalid = "key: [unfinished";
        Files.writeString(file, invalid);
        YmlEntry entry = new YmlEntry();
        entry.setPath(file);
        assertThrows(IllegalArgumentException.class, entry::loadEntry);
        assertEquals(invalid, Files.readString(file));
    }
}
