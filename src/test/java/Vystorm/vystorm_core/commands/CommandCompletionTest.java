package Vystorm.vystorm_core.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core.web.WebServices;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Every subcommand handled by Core's commands is suggested with the permission and hidden without it. */
class CommandCompletionTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<main> mainMock;
    private MockedStatic<WebServices> web;
    private GuiInteface gui;
    private SettingsRegistry settings;
    private Player admin, member, other;

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        mainMock = mockStatic(main.class);
        web = mockStatic(WebServices.class);
        web.when(WebServices::modules).thenReturn(java.util.Set.of());
        API api = mock(API.class);
        gui = mock(GuiInteface.class);
        mainMock.when(main::getApi).thenReturn(api);
        Plugin core = mock(Plugin.class);
        when(core.getDataFolder()).thenReturn(new java.io.File("build/tmp/completion-test"));
        mainMock.when(main::getPlugin).thenReturn(core);
        when(api.gui()).thenReturn(gui);
        settings = new SettingsRegistry(core, () -> null, p -> {});
        when(api.settings()).thenReturn(settings);
        Plugin owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("Example");
        when(owner.isEnabled()).thenReturn(true);
        settings.register(owner, SettingsSection.server("server", "Server", "", "vystorm_core.admin",
                List.of(Setting.toggle("on", "On", "", p -> true, (p, v) -> null)), p -> {}));
        settings.register(owner, SettingsSection.player("mine", "Mine", "",
                List.of(Setting.toggle("on", "On", "", p -> true, (p, v) -> null))));
        admin = player("Admin", true);
        member = player("Member", false);
        other = player("Other", false);
        Server server = mock(Server.class);
        doReturn(List.of(admin, member, other)).when(server).getOnlinePlayers();
        for (Player p : List.of(admin, member, other)) when(p.getServer()).thenReturn(server);
        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i -> List.of(admin, member, other));
        when(gui.menuKeys(any())).thenReturn(List.of("example:shop"));
    }

    @AfterEach void tearDown() { web.close(); mainMock.close(); bukkit.close(); }

    private static Player player(String name, boolean admin) {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission("vystorm_core.admin")).thenReturn(admin);
        return p;
    }

    private static List<String> complete(org.bukkit.command.TabCompleter completer, org.bukkit.command.CommandSender sender, String... args) {
        return completer.onTabComplete(sender, mock(Command.class), "x", args);
    }

    @Test void givenMenuCommand_whenCompleting_thenEverySubcommandMenuKeyAndAdminArgumentAppears() {
        Menu menu = new Menu();
        List<String> all = complete(menu, admin, "");
        assertTrue(all.containsAll(Menu.SUBCOMMANDS), all.toString());
        assertTrue(all.contains("access"));
        assertTrue(all.contains("example:shop"));
        assertFalse(complete(menu, member, "").contains("access"));
        assertTrue(complete(menu, member, "").containsAll(Menu.SUBCOMMANDS));
        assertEquals(List.of("search", "settings"), complete(menu, admin, "S"), "prefix filter is case-insensitive");
        assertEquals(List.of("Member"), complete(menu, admin, "access", "me"));
        assertEquals(List.of(), complete(menu, member, "access", ""));
    }

    @Test void givenSettingsCommand_whenCompleting_thenServerDraftProfileOnlyForAdmins() {
        SettingsCommand command = new SettingsCommand();
        List<String> all = complete(command, admin, "");
        assertTrue(all.containsAll(List.of("server", "draft", "profile", "example:server", "example:mine")), all.toString());
        List<String> mine = complete(command, member, "");
        assertEquals(List.of("example:mine"), mine);
        assertEquals(List.of("export", "import"), complete(command, admin, "profile", ""));
        assertEquals(List.of(), complete(command, member, "profile", ""));
    }

    @Test void givenNbtCommand_whenCompleting_thenTagsAndMenusOnlyForAdmins() {
        NBT nbt = new NBT();
        assertEquals(List.of("openMenu", "openBook"), complete(nbt, admin, ""));
        assertEquals(List.of("example:shop"), complete(nbt, admin, "openMenu", ""));
        assertEquals(List.of(), complete(nbt, member, ""));
    }

    @Test void givenWebCommand_whenCompleting_thenStatusForAdminsAndLinkOnlyForTheConsole() {
        WebCommand command = new WebCommand();
        assertEquals(List.of("logout", "status"), complete(command, admin, ""));
        assertEquals(List.of("logout"), complete(command, member, ""));
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("vystorm_core.admin")).thenReturn(true);
        assertEquals(List.of("status", "link"), complete(command, console, ""));
        assertEquals(List.of("Admin"), complete(command, console, "link", "a"));
        assertEquals(List.of(), complete(command, member, "link", ""));
    }

    @Test void givenRegionCommand_whenCompleting_thenOnlyAdminsGetSubcommandsAndPlayers() {
        var command = new Vystorm.vystorm_core.region.RegionCommand();
        assertEquals(List.of("at", "list"), complete(command, admin, ""));
        assertEquals(List.of("Other"), complete(command, admin, "at", "o"));
        assertEquals(List.of(), complete(command, member, ""));
    }
}
