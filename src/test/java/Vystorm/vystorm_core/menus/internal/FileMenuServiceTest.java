package Vystorm.vystorm_core.menus.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class FileMenuServiceTest {
    @TempDir Path folder;
    Plugin core;
    API api;
    GuiInteface gui;
    YamlConfiguration config;
    long[] now = { 1_000_000L };
    FileMenuService service;

    @BeforeEach
    void setUp() {
        core = mock(Plugin.class);
        config = new YamlConfiguration();
        Logger quiet = Logger.getLogger("file-menus-test");
        quiet.setUseParentHandlers(false);
        when(core.getLogger()).thenReturn(quiet);
        when(core.getConfig()).thenReturn(config);
        when(core.getName()).thenReturn("vystorm_core");
        api = mock(API.class);
        gui = mock(GuiInteface.class);
        when(api.gui()).thenReturn(gui);
        // the lookup by name only: asking the server whether a material is an item needs a running server
        service = new FileMenuService(core, api, folder, () -> now[0], Material::getMaterial);
    }

    private void file(String name, String json) throws Exception { Files.write(folder.resolve(name), json.getBytes(StandardCharsets.UTF_8)); }

    private static String menu(String id, String extra) {
        return "{ \"format\": \"vystorm-menu\", \"version\": 1, \"id\": \"" + id + "\"" + (extra.isEmpty() ? "" : ", " + extra) + " }";
    }

    private static Player player(String name, String... permissions) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
        for (String permission : permissions) when(player.hasPermission(permission)).thenReturn(true);
        return player;
    }

    @Test
    void givenGoodAndBrokenFiles_whenLoaded_thenTheBrokenOneIsSkippedAndTheOthersRegistered() throws Exception {
        file("main.json", FileMenuLayoutTest.resource("main.json"));
        file("locks-general.json", FileMenuLayoutTest.resource("locks-general.json"));
        file("hidden.json", menu("hidden", "\"mainMenu\": false"));
        file("broken.json", menu("broken", "\"pages\": 0"));
        file("garbage.json", "this is not json");
        file("notes.txt", "not a menu file");

        assertEquals(3, service.reload());
        assertEquals(List.of("hidden", "locks-general", "main"), service.ids());
        assertEquals(List.of("broken.json", "garbage.json"), List.copyOf(service.errors().keySet()));
        assertTrue(service.errors().get("broken.json").get(0).contains("pages") && service.errors().get("broken.json").get(0).contains("out of range"));
        assertTrue(service.errors().get("garbage.json").get(0).startsWith("not JSON"));
        // the demo opens a menu nobody provides: the owner is told when the file is read
        assertTrue(service.warnings().get("main.json").stream().anyMatch(w -> w.contains("\"fatigue\"") && w.contains("\"fatigue-help\", which does not exist")),
                String.valueOf(service.warnings()));

        verify(gui).registerMenu(eq(core), eq("menus/main"), any(Function.class), any(Material.class), any(Predicate.class), any(Consumer.class), eq(""), any(Function.class));
        verify(gui).registerMenu(eq(core), eq("menus/locks-general"), any(Function.class), any(Material.class), any(Predicate.class), any(Consumer.class), eq(""), any(Function.class));
        verify(gui, never()).registerMenu(eq(core), eq("menus/hidden"), any(Function.class), any(Material.class), any(Predicate.class), any(Consumer.class), anyString(), any(Function.class));

        assertEquals("main", service.idOf("main"));
        assertEquals("main", service.idOf("menus:main"));
        assertEquals("main", service.idOf("MENUS:Main"));
        assertNull(service.idOf("broken"));
        assertNull(service.idOf("vystorm_core:menus/main"));
        assertNull(service.idOf(null));
    }

    @Test
    void givenAReload_whenFilesChanged_thenTilesAreReplacedAndRemovedMenusForgotten() throws Exception {
        file("shop.json", menu("shop", "\"title\": \"<gold>Shop\""));
        assertEquals(1, service.reload());
        Files.delete(folder.resolve("shop.json"));
        file("bank.json", menu("bank", ""));
        assertEquals(1, service.reload());
        assertEquals(List.of("bank"), service.ids());
        verify(gui).unregisterMenu(core, "menus/shop");
        verify(gui).registerMenu(eq(core), eq("menus/bank"), any(Function.class), any(Material.class), any(Predicate.class), any(Consumer.class), eq(""), any(Function.class));
    }

    @Test
    void givenTheSwitchIsOff_whenLoaded_thenNothingIsReadOrRegistered() throws Exception {
        file("shop.json", menu("shop", ""));
        config.set("menus.enabled", false);
        assertEquals(0, service.reload());
        assertEquals(List.of(), service.ids());
        assertTrue(service.errors().isEmpty());
        verifyNoInteractions(gui);
        assertEquals(FileMenuService.Result.UNKNOWN, service.open(player("Steve"), "shop"));
    }

    @Test
    void givenAMenuWithoutATitle_whenRegistered_thenItsTileShowsTheId() throws Exception {
        file("shop.json", menu("shop", ""));
        assertEquals(1, service.reload());
        // the title a tile shows: plain text, the id when the menu has no title
        ArgumentCaptor<Function<Player, String>> title = ArgumentCaptor.forClass(Function.class);
        verify(gui).registerMenu(eq(core), eq("menus/shop"), title.capture(), any(Material.class), any(Predicate.class), any(Consumer.class), eq(""), any(Function.class));
        assertEquals("shop", title.getValue().apply(null));
    }

    @Test
    void givenAMenuWithAPermission_whenOpened_thenOnlyAllowedPlayersGetIt() throws Exception {
        file("vip.json", menu("vip", "\"permission\": \"shop.vip\", \"mainMenu\": false"));
        file("guests.json", menu("guests", "\"permission\": \"!shop.member\", \"mainMenu\": false"));
        service.reload();
        assertEquals(FileMenuService.Result.DENIED, service.open(player("Steve"), "vip"));
        assertEquals(FileMenuService.Result.DENIED, service.open(player("Alex", "shop.member"), "guests"));
        assertEquals(FileMenuService.Result.UNKNOWN, service.open(player("Steve"), "nope"));
        assertEquals(List.of("guests"), service.idsFor(player("Steve")));
        assertEquals(List.of("vip"), service.idsFor(player("Alex", "shop.vip", "shop.member")));
        assertTrue(FileMenuService.allowed(player("Steve"), ""));
        assertFalse(FileMenuService.allowed(null, "shop.vip"));
    }

    @Test
    void givenClickActions_whenClicked_thenPlayerAndConsoleCommandsRunWithNameAndUuidOnly() throws Exception {
        file("main.json", FileMenuLayoutTest.resource("main.json"));
        service.reload();
        MenuFile menu = service.menu("main");
        Player vip = player("Steve", "vystorm.vip"), guest = player("Alex");
        AtomicInteger closed = new AtomicInteger();
        try (var bukkit = mockStatic(Bukkit.class)) {
            ConsoleCommandSender console = mock(ConsoleCommandSender.class);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);

            service.click(vip, menu, menu.widget("herbarium"), false, 1, closed::incrementAndGet);
            verify(vip).performCommand("herbarium");
            assertEquals(1, closed.get(), "the close action ran");
            service.click(vip, menu, menu.widget("herbarium"), true, 1, closed::incrementAndGet);
            verify(vip, times(1)).performCommand(anyString());   // no right-click actions: nothing happens

            service.click(vip, menu, menu.widget("fatigue"), false, 1, closed::incrementAndGet);
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "fatigue reset Steve"));
            // the cooldown of an hour: a second click does nothing, also half an hour later
            now[0] += 1_800_000L;
            service.click(vip, menu, menu.widget("fatigue"), false, 1, closed::incrementAndGet);
            bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), times(1));
            now[0] += 1_800_001L;
            service.click(vip, menu, menu.widget("fatigue"), false, 1, closed::incrementAndGet);
            bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), times(2));

            // without the click permission nothing happens, and no cooldown starts
            service.click(guest, menu, menu.widget("fatigue"), false, 1, closed::incrementAndGet);
            bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), times(2));
            verify(guest, never()).performCommand(anyString());
        }
    }

    @Test
    void givenAPlayerNameThatWouldChangeTheCommand_whenAConsoleActionRuns_thenItIsLeftOut() throws Exception {
        file("gift.json", menu("gift", "\"mainMenu\": false, \"widgets\": [ { \"id\": \"gift\", \"type\": \"button\", \"click\": { \"cooldown\": 1, \"actions\": ["
                + " { \"command\": \"give {player} diamond\", \"as\": \"console\" }, { \"command\": \"log {uuid}\", \"as\": \"console\" }, { \"command\": \"thanks {player}\" } ] } } ]"));
        service.reload();
        MenuFile menu = service.menu("gift");
        Player everyone = player("@a");
        try (var bukkit = mockStatic(Bukkit.class)) {
            ConsoleCommandSender console = mock(ConsoleCommandSender.class);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            service.click(everyone, menu, menu.widget("gift"), false, 1, () -> { });
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "log " + everyone.getUniqueId()));
            bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), times(1));
            verify(everyone).performCommand("thanks @a");   // as the player: nothing they could not type themselves
        }
        assertEquals("tp Steve " + player("Steve").getUniqueId() + " {page} %x%", FileMenuService.fill("tp {player} {uuid} {page} %x%", player("Steve")));
    }
}
