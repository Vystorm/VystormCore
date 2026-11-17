package Vystorm.vystorm_core.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.main;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class ResourcePacksLifecycleTest {
    private static final ResourcePacks.Settings SETTINGS = new ResourcePacks.Settings(true, true, false, "p", 69, "", true, "0.0.0.0", 8165, "pack.example.net");

    @AfterEach
    void reset() { ResourcePacks.shutdown(); }

    private static PackBuilder.Built build(String content) throws Exception {
        AssetRegistry registry = new AssetRegistry();
        registry.register(mock(Plugin.class), "m", List.of("ns"), Map.of("assets/ns/a.json", content.getBytes(StandardCharsets.UTF_8)), 1);
        return PackBuilder.build(registry.modules(), 69, "x");
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static Plugin core() {
        Plugin core = mock(Plugin.class);
        when(core.getLogger()).thenReturn(Logger.getLogger("test"));
        when(core.isEnabled()).thenReturn(true);
        return core;
    }

    @Test
    void givenPlayersOnlineBeforeFirstBuild_whenPublished_thenPackIsSent() throws Exception {
        Player player = player();
        Plugin core = core();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<main> mainMock = mockStatic(main.class)) {
            mainMock.when(main::getPlugin).thenReturn(core);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i -> List.of(player));
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            ResourcePacks.configureForTest(SETTINGS);
            PackBuilder.Built built = build("A");
            assertTrue(ResourcePacks.publish(built, 1));
            verify(player).setResourcePack(any(UUID.class), eq("http://pack.example.net:8165/vystorm-pack-" + built.sha1() + ".zip"), any(byte[].class), any(net.kyori.adventure.text.Component.class), eq(false));
            assertEquals(PackState.DOWNLOADING, ResourcePacks.state(player));
            assertEquals(built.sha1(), ResourcePacks.hash().orElseThrow());
        }
    }

    @Test
    void givenNewerPackPublished_whenOlderBuildFinishes_thenOlderIsDiscarded() throws Exception {
        Plugin core = core();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<main> mainMock = mockStatic(main.class)) {
            mainMock.when(main::getPlugin).thenReturn(core);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i -> List.of());
            ResourcePacks.configureForTest(SETTINGS);
            PackBuilder.Built newer = build("new");
            PackBuilder.Built older = build("old");
            assertTrue(ResourcePacks.publish(newer, 5));
            assertFalse(ResourcePacks.publish(older, 4));
            assertEquals(newer.sha1(), ResourcePacks.hash().orElseThrow());
            assertTrue(ResourcePacks.publish(older, 5), "gleiche Revision darf ersetzen");
        }
    }

    @Test
    void givenUnchangedHash_whenPublishedAgain_thenPlayersAreNotResent() throws Exception {
        Player player = player();
        Plugin core = core();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<main> mainMock = mockStatic(main.class)) {
            mainMock.when(main::getPlugin).thenReturn(core);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i -> List.of(player));
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            ResourcePacks.configureForTest(SETTINGS);
            PackBuilder.Built built = build("A");
            ResourcePacks.publish(built, 1);
            ResourcePacks.publish(built, 2);
            verify(player, times(1)).setResourcePack(any(UUID.class), anyString(), any(byte[].class), any(net.kyori.adventure.text.Component.class), anyBoolean());
        }
    }

    @Test
    void givenDownloadRunning_whenEnsurePack_thenNotResent() throws Exception {
        Player player = player();
        Plugin core = core();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<main> mainMock = mockStatic(main.class)) {
            mainMock.when(main::getPlugin).thenReturn(core);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i -> List.of(player));
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            ResourcePacks.configureForTest(SETTINGS);
            ResourcePacks.publish(build("A"), 1);
            ResourcePacks.ensurePack(player);
            ResourcePacks.ensurePack(player);
            verify(player, times(1)).setResourcePack(any(UUID.class), anyString(), any(byte[].class), any(net.kyori.adventure.text.Component.class), anyBoolean());
        }
    }

    @Test
    void givenPublishedPack_whenServed_thenOnlyExactPathReturnsBytes() throws Exception {
        PackBuilder.Built built = build("A");
        ResourcePacks.Published published = new ResourcePacks.Published(built.zip(), built.sha1(), 1);
        assertArrayEquals(built.zip(), ResourcePacks.serve(published, "/vystorm-pack-" + built.sha1() + ".zip"));
        assertNull(ResourcePacks.serve(published, "/vystorm-pack-deadbeef.zip"));
        assertNull(ResourcePacks.serve(published, "/"));
        assertNull(ResourcePacks.serve(null, "/vystorm-pack-" + built.sha1() + ".zip"));
    }

    @Test
    void givenSettings_whenUrlResolved_thenFallbacksApply() {
        ResourcePacks.Settings noHost = new ResourcePacks.Settings(true, true, false, "p", 69, "", true, "0.0.0.0", 8165, "");
        assertEquals("http://1.2.3.4:8165/vystorm-pack-ab.zip", ResourcePacks.url(noHost, "ab", "1.2.3.4").orElseThrow());
        assertEquals("http://localhost:8165/vystorm-pack-ab.zip", ResourcePacks.url(noHost, "ab", "0.0.0.0").orElseThrow());
        assertEquals("http://localhost:8165/vystorm-pack-ab.zip", ResourcePacks.url(noHost, "ab", "").orElseThrow());
        assertTrue(ResourcePacks.unreachableForRemoteClients(noHost, "0.0.0.0"));
        assertFalse(ResourcePacks.unreachableForRemoteClients(noHost, "1.2.3.4"));
        assertEquals("http://pack.example.net:8165/vystorm-pack-ab.zip", ResourcePacks.url(SETTINGS, "ab", "1.2.3.4").orElseThrow());
        ResourcePacks.Settings external = new ResourcePacks.Settings(true, true, false, "p", 69, "https://cdn.example/p.zip", true, "0.0.0.0", 8165, "");
        assertEquals("https://cdn.example/p.zip", ResourcePacks.url(external, "ab", "").orElseThrow());
        assertFalse(ResourcePacks.unreachableForRemoteClients(external, ""));
        ResourcePacks.Settings off = new ResourcePacks.Settings(true, true, false, "p", 69, "", false, "0.0.0.0", 8165, "");
        assertTrue(ResourcePacks.url(off, "ab", "1.2.3.4").isEmpty());
        assertTrue(ResourcePacks.url(noHost, null, "1.2.3.4").isEmpty());
    }

    @Test
    void givenInvalidSettings_whenValidated_thenDefaultsAndProblemsReported() {
        List<String> problems = new ArrayList<>();
        ResourcePacks.Settings raw = new ResourcePacks.Settings(true, true, false, null, 0, "ftp://x", true, "", 70000, " host ");
        ResourcePacks.Settings valid = ResourcePacks.validate(raw, problems);
        assertEquals(3, problems.size());
        assertEquals(ResourcePacks.DEFAULT_PORT, valid.port());
        assertEquals(ResourcePacks.DEFAULT_PACK_FORMAT, valid.packFormat());
        assertEquals("", valid.publicUrl());
        assertEquals("0.0.0.0", valid.bind());
        assertEquals("host", valid.publicHost());
        assertEquals("", valid.prompt());
        problems.clear();
        ResourcePacks.validate(SETTINGS, problems);
        assertTrue(problems.isEmpty());
    }

    @Test
    void givenModuleOfDisabledPlugin_whenPluginDisabled_thenRebuildIsScheduled(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("resource-pack.yml"), "http:\n  enabled: false\n");
        Plugin core = core();
        when(core.getDataFolder()).thenReturn(folder.toFile());
        Plugin consumer = mock(Plugin.class);
        PluginManager manager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<Listener> listeners = new ArrayList<>();
        doAnswer(i -> listeners.add(i.getArgument(0))).when(manager).registerEvents(any(Listener.class), eq(core));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<main> mainMock = mockStatic(main.class)) {
            mainMock.when(main::getPlugin).thenReturn(core);
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getIp).thenReturn("");
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ResourcePacks.initialize();
            assertEquals(1, listeners.size());
            ResourcePacks.register(consumer, "consumer-test-module", List.of("consumer_test"), Map.of(), 1);
            verify(scheduler, times(1)).runTaskLater(eq(core), any(Runnable.class), eq(20L));
            // Geplanter Build noch offen: Flag zurücksetzen wie der Scheduler es täte.
            ResourcePacks.shutdown();
            ResourcePacks.initialize();
            Listener listener = listeners.get(1);
            Method onDisable = listener.getClass().getMethod("onDisable", PluginDisableEvent.class);
            onDisable.setAccessible(true);
            onDisable.invoke(listener, new PluginDisableEvent(consumer));
            assertFalse(ResourcePacks.assets().hasModule("consumer-test-module"));
            // initialize (Module vorhanden) + Disable → mindestens ein weiterer geplanter Neuaufbau
            verify(scheduler, atLeast(2)).runTaskLater(eq(core), any(Runnable.class), eq(20L));
        }
    }
}
