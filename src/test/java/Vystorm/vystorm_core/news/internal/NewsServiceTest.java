package Vystorm.vystorm_core.news.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.news.News;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Read state, notices and announcements of the news board, with player preferences in a fake persistent data container. */
class NewsServiceTest {
    private MockedStatic<Bukkit> bukkit;
    private org.bukkit.plugin.Plugin core;
    private YamlConfiguration config;
    private NewsService service;
    private NewsBoard board;
    private Player reader, optedOut, guest;

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        core = mock(org.bukkit.plugin.Plugin.class);
        when(core.getName()).thenReturn("vystorm_core");
        when(core.namespace()).thenReturn("vystorm_core");
        when(core.isEnabled()).thenReturn(true);
        config = new YamlConfiguration();
        when(core.getConfig()).thenReturn(config);
        Server server = mock(Server.class);
        when(core.getServer()).thenReturn(server);
        reader = player("Reader", true);
        optedOut = player("Quiet", true);
        guest = player("Guest", false);
        doReturn(List.of(reader, optedOut, guest)).when(server).getOnlinePlayers();
        board = new NewsBoard(null);
        service = new NewsService(core, mock(API.class), board);
        Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs.setBool(core, optedOut, NewsService.PREF_JOIN, false);
    }

    @AfterEach void tearDown() { bukkit.close(); }

    private static Player player(String name, boolean mayRead) {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(News.PERMISSION)).thenReturn(mayRead);
        Map<NamespacedKey, Object> data = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(NamespacedKey.class), any(PersistentDataType.class))).thenAnswer(i -> data.get(i.<NamespacedKey>getArgument(0)));
        doAnswer(i -> { data.put(i.getArgument(0), i.getArgument(2)); return null; })
                .when(pdc).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        when(pdc.has(any(NamespacedKey.class))).thenAnswer(i -> data.containsKey(i.<NamespacedKey>getArgument(0)));
        when(p.getPersistentDataContainer()).thenReturn(pdc);
        return p;
    }

    private static List<String> messages(Player p) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(p, atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    @Test void givenNewPost_whenPublishing_thenOnlyReadersWhoWantNoticesAreToldAndTheAuthorHasReadIt() {
        Player author = player("Admin", true);
        News.Post post = service.publish(author, "Admin", "Summer event", "Friday 8 pm").orElseThrow();
        assertTrue(messages(reader).stream().anyMatch(m -> m.contains("Summer event") && m.contains("Admin")), messages(reader).toString());
        assertTrue(messages(optedOut).isEmpty(), "switched off in /vsettings");
        assertTrue(messages(guest).isEmpty(), "no vystorm_core.news");
        assertTrue(messages(author).stream().anyMatch(m -> m.contains("#" + post.id())));
        assertEquals(List.of(), service.unread(author));
        assertEquals(List.of(post), service.unread(reader));
    }

    @Test void givenReadPosts_whenMarkingAndAfterTheFileWasReset_thenUnreadFollowsTheLatestId() {
        service.publish(null, "Shop", "One", "");
        service.publish(null, "Shop", "Two", "");
        assertEquals(2, service.unread(reader).size());
        service.markRead(reader);
        assertEquals(0, service.unread(reader).size());
        News.Post three = service.publish(null, "Shop", "Three", "").orElseThrow();
        assertEquals(List.of(three), service.unread(reader));

        // news.yml deleted: ids start again at 1, a stored "read up to 3" must not hide the new posts.
        NewsService fresh = new NewsService(core, mock(API.class), new NewsBoard(null));
        News.Post again = fresh.publish(null, "Shop", "After reset", "").orElseThrow();
        assertEquals(1, again.id());
        assertEquals(List.of(again), fresh.unread(reader));
        assertEquals(0, fresh.lastRead(reader));
    }

    @Test void givenBoardSwitchedOff_whenPublishing_thenNothingIsStoredOrAnnounced() {
        config.set("news.enabled", false);
        Player author = player("Admin", true);
        assertTrue(service.publish(author, "Admin", "Hidden", "").isEmpty());
        assertEquals(0, board.latestId());
        assertTrue(messages(reader).isEmpty());
        assertFalse(service.notifies(reader));
        config.set("news.enabled", true);
        config.set("news.announce-new-posts", false);
        assertTrue(service.publish(author, "Admin", "Quiet post", "").isPresent());
        assertTrue(messages(reader).isEmpty(), "announcements switched off");
    }

    @Test void givenReadPost_whenWriterCorrectsIt_thenNoAnnouncementOrUnreadChange() {
        News.Post post = board.post("Original", "Title", "Text", System.currentTimeMillis(), 20, 30);
        service.markRead(reader);
        when(guest.hasPermission(News.WRITE_PERMISSION)).thenReturn(true);
        service.edit(guest, post.id(), "Correction", "Updated");
        assertEquals("Correction", board.get(post.id()).orElseThrow().title());
        assertEquals(0, service.unread(reader).size());
        assertTrue(messages(reader).isEmpty());
        assertTrue(messages(optedOut).isEmpty());
        assertTrue(messages(guest).stream().anyMatch(m -> m.contains("#" + post.id())));
    }

    @Test void givenNoPermissionOrDisabledBoard_whenEditing_thenPostIsUnchanged() {
        News.Post post = board.post("A", "Title", "", System.currentTimeMillis(), 20, 30);
        service.edit(guest, post.id(), "Denied", "");
        assertEquals(post, board.get(post.id()).orElseThrow());
        when(guest.hasPermission(News.WRITE_PERMISSION)).thenReturn(true);
        config.set("news.enabled", false);
        service.edit(guest, post.id(), "Disabled", "");
        assertEquals(post, board.get(post.id()).orElseThrow());
    }

    @Test void givenConsoleEditCommand_whenParsingReplacement_thenUpdatesOnlyValidExistingPost() throws Exception {
        var active = NewsService.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(null, service);
        try {
            var console = mock(org.bukkit.command.ConsoleCommandSender.class);
            when(console.hasPermission(News.WRITE_PERMISSION)).thenReturn(true);
            News.Post post = board.post("A", "Title", "", System.currentTimeMillis(), 20, 30);
            NewsService.adminCommand(console, new String[]{"news", "edit", "#1", "New", "title", "|", "Line\\nTwo"});
            assertEquals("New title", board.get(post.id()).orElseThrow().title());
            assertEquals("Line\nTwo", board.get(post.id()).orElseThrow().text());
            for (String id : List.of("bad", "-1", "9999999999", "2"))
                NewsService.adminCommand(console, new String[]{"news", "edit", id, "Invalid"});
            NewsService.adminCommand(console, new String[]{"news", "edit", "1"});
            NewsService.adminCommand(console, new String[]{"news", "edit", "1", "|", "Missing title"});
            assertEquals("New title", board.get(post.id()).orElseThrow().title());
            assertEquals(1, board.latestId());
        } finally { active.set(null, null); }
    }
}
