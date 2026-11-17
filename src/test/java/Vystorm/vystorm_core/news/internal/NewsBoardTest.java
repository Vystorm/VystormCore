package Vystorm.vystorm_core.news.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.news.News;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewsBoardTest {
    private static final long DAY = NewsBoard.DAY;
    @TempDir Path folder;

    @Test void givenPosts_whenListing_thenNewestFirstAndUnreadByLastSeenId() {
        NewsBoard board = new NewsBoard(folder.resolve("news.yml"));
        assertEquals(0, board.latestId());
        News.Post a = board.post("Admin", "Welcome", "Hello!", 1000, 20, 0);
        News.Post b = board.post("Shop", "Sale", "", 2000, 20, 0);
        assertEquals(List.of(b, a), board.visible(3000, 0));
        assertEquals(List.of(b, a), board.unread(0, 3000, 0), "a new player has not read anything");
        assertEquals(List.of(b), board.unread(a.id(), 3000, 0));
        assertEquals(List.of(), board.unread(board.latestId(), 3000, 0));
    }

    @Test void givenSavedBoard_whenLoadingAgain_thenPostsSurviveAndIdsAreNeverReused() throws Exception {
        Path file = folder.resolve("data/news.yml");
        NewsBoard board = new NewsBoard(file);
        News.Post first = board.post("Admin", "One", "line 1\nline 2", 1000, 20, 0);
        News.Post second = board.post("Admin", "Two", "", 2000, 20, 0);
        assertTrue(board.remove(second.id()));
        assertFalse(board.remove(second.id()));
        assertTrue(Files.exists(file));

        NewsBoard loaded = new NewsBoard(file);
        assertEquals(List.of(first), loaded.visible(3000, 0));
        assertEquals("line 1\nline 2", loaded.get(first.id()).orElseThrow().text());
        assertEquals(second.id(), loaded.latestId(), "the deleted id stays used");
        assertEquals(second.id() + 1, loaded.post("Admin", "Three", "", 4000, 20, 0).id());
    }

    @Test void givenOldAndManyPosts_whenPosting_thenExpiredAndOldestAreDropped() {
        NewsBoard board = new NewsBoard(null);
        News.Post old = board.post("A", "Old", "", 0, 20, 0);
        News.Post mid = board.post("A", "Mid", "", 5 * DAY, 20, 0);
        assertEquals(List.of(mid), board.visible(10 * DAY, 7), "older than 7 days is hidden");
        assertEquals(2, board.visible(10 * DAY, 0).size(), "0 days = no limit");
        board.post("A", "New", "", 10 * DAY, 20, 7);
        assertTrue(board.get(old.id()).isEmpty(), "expired posts are removed on the next post");
        board.post("A", "Newer", "", 11 * DAY, 2, 0);
        assertEquals(List.of("Newer", "New"), board.visible(11 * DAY, 0).stream().map(News.Post::title).toList());
    }

    @Test void givenUnsafeText_whenPosting_thenPlainTrimmedTextOrRejection() {
        NewsBoard board = new NewsBoard(null);
        News.Post post = board.post("Ad\u0007min", "  §cRed\ttitle\nhere  ", "a\r\nb\n\n\n\nc\u0000", 0, 20, 0);
        assertEquals("&cRed title here", post.title());
        assertEquals("a\nb\n\nc", post.text());
        assertEquals("Admin", post.author());
        assertEquals(NewsBoard.MAX_TITLE, board.post("A", "x".repeat(200), "", 0, 20, 0).title().length());
        assertEquals(NewsBoard.MAX_TEXT, board.post("A", "T", "y".repeat(5000), 0, 20, 0).text().length());
        assertThrows(IllegalArgumentException.class, () -> board.post("A", " \n\u0001 ", "text", 0, 20, 0));
    }

    @Test void givenAdminInput_whenSplittingAndListingTitles_thenTitleTextAndEllipsis() {
        assertArrayEquals(new String[] {"Event", "Friday at 8\nBe there"}, NewsService.splitPost("Event | Friday at 8\\nBe there"));
        assertArrayEquals(new String[] {"Only a title", ""}, NewsService.splitPost("Only a title"));
        assertNull(NewsService.splitPost(" | text"));
        assertNull(NewsService.splitPost(""));
        List<News.Post> posts = List.of(new News.Post(3, 0, "A", "C", ""), new News.Post(2, 0, "A", "B", ""),
                new News.Post(1, 0, "A", "A", ""));
        assertEquals("\"C\", \"B\", …", NewsService.titles(posts, 2));
        assertEquals("\"C\", \"B\", \"A\"", NewsService.titles(posts, 3));
    }

    @Test void givenUnreadableFile_whenLoading_thenItIsMovedAsideAndNeverOverwritten() throws Exception {
        Path file = folder.resolve("news.yml");
        String content = "posts:\n  '1':\n    title: [\n";
        Files.writeString(file, content);
        NewsBoard board = new NewsBoard(file);
        assertNotNull(board.broken());
        assertTrue(board.broken().getFileName().toString().startsWith("news.yml.broken-"));
        assertEquals(content, Files.readString(board.broken()), "the original is kept unchanged");
        board.post("Admin", "New", "", 1000, 20, 0);
        assertFalse(Files.exists(file), "no empty board is written over it");
    }

    @Test void givenSavedPost_whenEdited_thenContentPersistsWithoutChangingIdentityOrExpiry() {
        Path file = folder.resolve("news.yml");
        NewsBoard board = new NewsBoard(file);
        News.Post original = board.post("Author", "Typo", "Old text", 1000, 20, 0);
        News.Post newer = board.post("Other", "Newer", "", 2000, 20, 0);
        News.Post edited = board.edit(original.id(), " Corrected\ntitle ", "a\r\nb\u0000").orElseThrow();
        assertEquals(new News.Post(original.id(), original.time(), original.author(), "Corrected title", "a\nb"), edited);
        NewsBoard loaded = new NewsBoard(file);
        assertEquals(List.of(newer, edited), loaded.visible(3000, 0));
        assertEquals(List.of(newer), loaded.unread(original.id(), 3000, 0));
        assertEquals(newer.id(), loaded.latestId());
        assertTrue(loaded.visible(31 * DAY, 30).isEmpty());
    }

    @Test void givenMissingPostOrInvalidTitle_whenEditing_thenNoPostIsCreatedOrChanged() {
        NewsBoard board = new NewsBoard(null);
        News.Post original = board.post("A", "Title", "Text", 0, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> board.edit(original.id(), "\n\u0001", "replacement"));
        assertEquals(original, board.get(original.id()).orElseThrow());
        assertEquals(64, board.edit(original.id(), "x".repeat(100), "y".repeat(2000)).orElseThrow().title().length());
        assertEquals(1000, board.get(original.id()).orElseThrow().text().length());
        board.remove(original.id());
        assertTrue(board.edit(original.id(), "Deleted", "").isEmpty());
        assertTrue(board.visible(0, 0).isEmpty());
        assertEquals(original.id(), board.latestId());
    }
}
