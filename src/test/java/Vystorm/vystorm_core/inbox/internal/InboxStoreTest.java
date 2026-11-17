package Vystorm.vystorm_core.inbox.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxStoreTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long NOW = 1_000_000_000_000L;

    @TempDir Path folder;

    @Test
    void givenKeyMessage_whenReloaded_thenNamespaceKeyAndArgsSurvive() {
        new InboxStore(folder).add(PLAYER, NOW, "VystormEstates", "vystormestates", "inbox.rent-expired", List.of("plot", "Harbour 3"), "", 50, 30);
        var messages = new InboxStore(folder).messages(PLAYER, NOW, 30);
        assertEquals(1, messages.size());
        var m = messages.getFirst();
        assertEquals("vystormestates", m.namespace());
        assertEquals("inbox.rent-expired", m.key());
        assertEquals(List.of("plot", "Harbour 3"), m.args());
        assertFalse(m.read());
        assertFalse(m.plain());
    }

    @Test
    void givenMoreThanLimit_whenAdding_thenOldestAreDropped() {
        InboxStore store = new InboxStore(folder);
        for (int i = 0; i < 5; i++) store.add(PLAYER, NOW + i, "P", "", "", List.of(), "message " + i, 3, 30);
        var messages = store.messages(PLAYER, NOW + 10, 30);
        assertEquals(List.of("message 4", "message 3", "message 2"), messages.stream().map(InboxStore.Message::text).toList());
    }

    @Test
    void givenOldMessage_whenListing_thenItIsHiddenAndPrunedOnNextWrite() {
        InboxStore store = new InboxStore(folder);
        store.add(PLAYER, NOW, "P", "", "", List.of(), "old", 50, 30);
        long later = NOW + 31 * InboxStore.DAY;
        assertTrue(store.messages(PLAYER, later, 30).isEmpty());
        store.add(PLAYER, later, "P", "", "", List.of(), "new", 50, 30);
        assertEquals(List.of("new"), new InboxStore(folder).messages(PLAYER, later, 0).stream().map(InboxStore.Message::text).toList());
    }

    @Test
    void givenUnread_whenMarkedReadAndDeleted_thenCountsFollow() {
        InboxStore store = new InboxStore(folder);
        store.add(PLAYER, NOW, "P", "", "", List.of(), "a", 50, 30);
        store.add(PLAYER, NOW, "P", "", "", List.of(), "b", 50, 30);
        assertEquals(2, store.unread(PLAYER, NOW, 30));
        store.markRead(PLAYER);
        assertEquals(0, store.unread(PLAYER, NOW, 30));
        store.add(PLAYER, NOW, "P", "", "", List.of(), "c", 50, 30);
        assertEquals(2, store.deleteRead(PLAYER));
        assertEquals(List.of("c"), new InboxStore(folder).messages(PLAYER, NOW, 30).stream().map(InboxStore.Message::text).toList());
    }

    @Test
    void givenColourCodesAndLineBreaks_whenStored_thenTextIsPlainSingleLine() {
        InboxStore store = new InboxStore(folder);
        store.add(PLAYER, NOW, "P", "", "", List.of(), "§cRed\nline\ttwo", 50, 30);
        assertEquals("&cRed line two", store.messages(PLAYER, NOW, 30).getFirst().text());
    }

    @Test
    void givenEverythingDeleted_whenSaved_thenFileIsRemoved() throws Exception {
        InboxStore store = new InboxStore(folder);
        store.add(PLAYER, NOW, "P", "", "", List.of(), "a", 50, 30);
        assertTrue(Files.exists(folder.resolve(PLAYER + ".yml")));
        store.markRead(PLAYER);
        store.deleteRead(PLAYER);
        assertFalse(Files.exists(folder.resolve(PLAYER + ".yml")));
    }

    @Test
    void givenBrokenFile_whenLoading_thenItIsMovedAsideAndNotOverwritten() throws Exception {
        Files.writeString(folder.resolve(PLAYER + ".yml"), "messages: [unclosed");
        InboxStore store = new InboxStore(folder);
        store.add(PLAYER, NOW, "P", "", "", List.of(), "a", 50, 30);
        assertFalse(Files.exists(folder.resolve(PLAYER + ".yml")));
        try (var files = Files.list(folder)) { assertEquals(1, files.count()); }
    }
}
