package Vystorm.vystorm_core._coreMethods.dataStorage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._coreMethods.dataStorage.methods.Saver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaverTest {
    private static DataEntry yaml(Path file, String key, String value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(key, value);
        DataEntry entry = mock(DataEntry.class);
        when(entry.getEntry()).thenReturn(config);
        when(entry.getPath()).thenReturn(file);
        when(entry.getName()).thenReturn(file.getFileName().toString());
        return entry;
    }

    @Test
    void givenOneBrokenEntry_whenSaveAll_thenOthersAreStillWrittenAndFailureReported(@TempDir Path folder) throws Exception {
        DataEntry broken = mock(DataEntry.class);
        when(broken.getEntry()).thenThrow(new IllegalStateException("broken"));
        DataEntry first = yaml(folder.resolve("a.yml"), "a", "1");
        DataEntry last = yaml(folder.resolve("b.yml"), "b", "2");
        HashMap<Path, DataEntry> table = new LinkedHashMap<>();
        table.put(folder.resolve("a.yml"), first);
        table.put(folder.resolve("broken.yml"), broken);
        table.put(folder.resolve("b.yml"), last);
        HashMap<String, HashMap<Path, DataEntry>> pluginData = new HashMap<>();
        pluginData.put("t", table);
        HashMap<Plugin, HashMap<String, HashMap<Path, DataEntry>>> storage = new HashMap<>();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("p");
        storage.put(plugin, pluginData);

        assertThrows(IllegalStateException.class, () -> new Saver(storage).saveAll());
        assertTrue(Files.readString(folder.resolve("a.yml")).contains("a: '1'"));
        assertTrue(Files.readString(folder.resolve("b.yml")).contains("b: '2'"));
        try (var files = Files.list(folder)) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp"))); }
    }

    @Test
    void givenNonYamlEntry_whenSaved_thenOwnSaveEntryIsUsedInsteadOfNpe() throws Exception {
        DataEntry csv = mock(DataEntry.class);
        when(csv.getEntry()).thenReturn(null);
        new Saver(new HashMap<>()).save(csv);
        verify(csv).saveEntry();
    }

    @Test
    void givenUnchangedContent_whenSaved_thenFileIsNotRewritten(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("c.yml");
        DataEntry entry = yaml(file, "c", "3");
        Saver saver = new Saver(new HashMap<>());
        saver.save(entry);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000));
        saver.save(entry);
        assertEquals(1_000, Files.getLastModifiedTime(file).toMillis());
    }
}
