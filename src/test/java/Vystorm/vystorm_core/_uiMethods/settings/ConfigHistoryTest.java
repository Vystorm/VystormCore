package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigHistoryTest {
    @TempDir Path folder;

    private static ConfigHistory.Change change(String path, Object before, Object after) { return new ConfigHistory.Change(path, before, after); }

    @Test void givenChanges_whenRecording_thenUnchangedAndSecretValuesAreLeftOut() {
        ConfigHistory history = new ConfigHistory(folder.resolve("h.yml"), 10);
        ConfigHistory.Entry entry = history.record("Admin", "Example", "config.yml", List.of(
                change("fields.cooldown", 30, 45), change("fields.speed", 1.5, 1.5), change("fields.max", 16, 16.0),
                change("database.password", "a", "b"), change("web.api-key", null, "x")), 0, 1000L);
        assertNotNull(entry);
        assertEquals(List.of("fields.cooldown"), entry.changes().stream().map(ConfigHistory.Change::path).toList());
        assertNull(history.record("Admin", "Example", "config.yml", List.of(change("a", 1, 1)), 0, 2000L), "nothing changed, nothing recorded");
        assertEquals(1, history.size());
    }

    @Test void givenSavedHistory_whenLoadingAgain_thenEntriesIdsAndUndoLinksSurvive() throws Exception {
        Path file = folder.resolve("sub/config-history.yml");
        ConfigHistory history = new ConfigHistory(file, 10);
        ConfigHistory.Entry first = history.record("Admin", "Example", "config.yml",
                List.of(change("worlds", List.of("world"), List.of("world", "nether")), change("prefix", null, "[Ex]")), 0, 1000L);
        ConfigHistory.Entry undo = history.record("Mod", "Example", "config.yml",
                List.of(change("prefix", "[Ex]", null)), first.id(), 2000L);
        assertTrue(Files.exists(file));

        ConfigHistory loaded = new ConfigHistory(file, 10);
        assertEquals(2, loaded.size());
        ConfigHistory.Entry again = loaded.get(first.id()).orElseThrow();
        assertEquals(undo.id(), again.undoneBy());
        assertTrue(again.undone());
        assertEquals(1000L, again.time());
        assertEquals(List.of("world", "nether"), again.changes().getFirst().after());
        assertNull(again.changes().get(1).before());
        assertEquals(first.id(), loaded.get(undo.id()).orElseThrow().undoOf());
        ConfigHistory.Entry next = loaded.record("Admin", "Example", "config.yml", List.of(change("a", 1, 2)), 0, 3000L);
        assertEquals(undo.id() + 1, next.id(), "ids continue after a restart");
    }

    @Test void givenUndoOfAnUndo_whenRecording_thenTheOriginalEntryIsActiveAndUndoableAgain() {
        Path file = folder.resolve("h.yml");
        ConfigHistory history = new ConfigHistory(file, 10);
        ConfigHistory.Entry first = history.record("Admin", "Example", "config.yml", List.of(change("cooldown", 30, 45)), 0, 1000L);
        ConfigHistory.Entry undo = history.record("Admin", "Example", "config.yml", List.of(change("cooldown", 45, 30)), first.id(), 2000L);
        assertTrue(history.get(first.id()).orElseThrow().undone());
        ConfigHistory.Entry redo = history.record("Admin", "Example", "config.yml", List.of(change("cooldown", 30, 45)), undo.id(), 3000L);

        ConfigHistory.Entry original = history.get(first.id()).orElseThrow();
        assertFalse(original.undone(), "its values are active again");
        assertEquals(0, original.undoneBy());
        assertEquals(redo.id(), history.get(undo.id()).orElseThrow().undoneBy());
        assertFalse(new ConfigHistory(file, 10).get(first.id()).orElseThrow().undone(), "saved that way");
    }

    @Test void givenMoreThanMax_whenRecording_thenOldestAreDroppedAndNewestComeFirst() {
        ConfigHistory history = new ConfigHistory(null, 3);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(history.record("A", i % 2 == 0 ? "One" : "Two", "config.yml", List.of(change("v", i, i + 1)), 0, i).id());
        assertEquals(3, history.size());
        assertTrue(history.get(ids.getFirst()).isEmpty());
        assertEquals(List.of(ids.get(4), ids.get(3), ids.get(2)), history.newestFirst(e -> true).stream().map(ConfigHistory.Entry::id).toList());
        assertEquals(List.of(ids.get(4), ids.get(2)), history.newestFirst(e -> e.plugin().equals("One")).stream().map(ConfigHistory.Entry::id).toList());
    }

    @Test void givenValues_whenComparingAndShowing_thenNumbersListsAndNullsBehave() {
        assertTrue(ConfigHistory.same(30, 30.0));
        assertTrue(ConfigHistory.same(List.of(1, "a"), Arrays.asList(1L, "a")));
        assertFalse(ConfigHistory.same(List.of("a"), "a"));
        assertFalse(ConfigHistory.same(null, ""));
        assertTrue(ConfigHistory.same(null, null));
        assertEquals("—", ConfigHistory.show(null, 20));
        assertEquals("\"[Ex]\"", ConfigHistory.show("[Ex]", 20));
        assertEquals("[world, nether]", ConfigHistory.show(List.of("world", "nether"), 40));
        assertEquals("\"aaaa…", ConfigHistory.show("a".repeat(50), 6));
        assertEquals("\"&cred\"", ConfigHistory.show("§cred", 20), "no colour codes leak into chat");
    }

    @Test void givenUnreadableFile_whenLoading_thenItIsMovedAsideAndNeverOverwritten() throws Exception {
        Path file = folder.resolve("config-history.yml");
        String content = "entries:\n  - id: [\n";
        Files.writeString(file, content);
        ConfigHistory history = new ConfigHistory(file, 10);
        assertNotNull(history.broken());
        assertEquals(content, Files.readString(history.broken()), "the original is kept unchanged");
        history.record("Admin", "Example", "config.yml", List.of(change("a", 1, 2)), 0, 1000L);
        assertFalse(Files.exists(file), "no new history is written over it");
    }
}
