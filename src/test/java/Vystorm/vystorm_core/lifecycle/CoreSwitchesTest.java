package Vystorm.vystorm_core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal.CorePlaceholders;
import Vystorm.vystorm_core._uiMethods.chat.ChatActions;
import Vystorm.vystorm_core.chat.TradeChatCommand;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Core's own feature switches (0.65.0): every key is in config.yml with a comment and on by default, every switch has a
 * one-line description in seven languages, and off really stops the feature while on behaves as before.
 */
class CoreSwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");

    @AfterEach void allOn() { CoreSwitches.configure(null); }

    private static void off(CoreSwitches.Switch... switches) {
        YamlConfiguration config = new YamlConfiguration();
        for (CoreSwitches.Switch s : switches) config.set(s.path, false);
        CoreSwitches.configure(config);
    }

    private static YamlConfiguration language(String code) throws Exception {
        try (InputStream in = CoreSwitchesTest.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
            assertNotNull(in, "lang/" + code + ".yml");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test void givenPackagedConfig_whenRead_thenEverySwitchIsOnAndExplained() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (CoreSwitches.Switch s : CoreSwitches.Switch.values()) {
            assertTrue(config.isBoolean(s.path), s.path);
            assertTrue(config.getBoolean(s.path), s.path);
            String parent = s.path.substring(0, s.path.lastIndexOf('.'));
            assertFalse(config.getComments(s.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + s.path);
        }
        for (String[] declared : CoreSwitches.DECLARED) assertTrue(config.isBoolean(declared[0]), declared[0]);
        assertEquals(Set.of(), readOff(config), "the packaged config switches nothing off");
    }

    private static Set<CoreSwitches.Switch> readOff(YamlConfiguration config) {
        CoreSwitches.configure(config);
        return CoreSwitches.off();
    }

    @Test void givenAConfigWithoutTheNewKeys_whenRead_thenEverythingStaysOn() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("player-menu.enabled", true);
        assertEquals(Set.of(), readOff(old));
        assertTrue(CoreSwitches.on(CoreSwitches.Switch.TRADE_CHAT));
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchHasADescription() throws Exception {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = language(code);
            assertFalse(lang.getString("feature-off", "").isBlank(), code);
            for (CoreSwitches.Switch s : CoreSwitches.Switch.values()) assertFalse(lang.getString(s.description(), "").isBlank(), code + " " + s.description());
            for (String[] declared : CoreSwitches.DECLARED) assertFalse(lang.getString(declared[1], "").isBlank(), code + " " + declared[1]);
        }
        assertEquals("<red>This feature is switched off on this server.", language("en").getString("feature-off"));
    }

    @Test void givenTradeChatOff_whenAPlayerTrades_thenNothingIsSentAndOnAsBefore() {
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        when(sender.getName()).thenReturn("Trader");
        when(sender.hasPermission(any(String.class))).thenReturn(true);
        PluginManager manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(sender));
            off(CoreSwitches.Switch.TRADE_CHAT);
            new TradeChatCommand().onCommand(sender, mock(Command.class), "vtrade", new String[] {"hello"});
            verifyNoInteractions(manager);
            verify(sender).sendMessage(any(Component.class));

            CoreSwitches.configure(null);
            new TradeChatCommand().onCommand(sender, mock(Command.class), "vtrade", new String[] {"hello"});
            verify(manager).callEvent(any());
        }
    }

    @Test void givenCoordinatesOff_whenALocationIsShown_thenPlainTextWithoutClick() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Component on = ChatActions.location(new Location(world, 1, 64, 2));
        assertNotNull(on.clickEvent(), "on: clickable as before");
        off(CoreSwitches.Switch.COORDINATES);
        Component plain = ChatActions.location(new Location(world, 1, 64, 2));
        assertNull(plain.clickEvent());
        assertNull(plain.hoverEvent());
    }

    @Test void givenRegionsOff_whenRegionPlaceholdersResolve_thenEmpty() {
        PlaceholderTesting testing = PlaceholderTesting.detached();
        API api = mock(API.class);
        var area = mock(Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface.class, CALLS_REAL_METHODS);
        when(api.area()).thenReturn(area);
        CorePlaceholders.register(PlaceholderTesting.plugin("vystorm_core"), testing.placeholders(), () -> api, key -> key);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Player steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
        when(steve.getPlayer()).thenReturn(steve);
        when(steve.getWorld()).thenReturn(world);
        when(steve.getLocation()).thenReturn(new Location(world, 1, 64, 2));
        assertEquals("0 false", testing.resolve(steve, "{region:count} {region:flag:pvp}"), "on: as before");
        off(CoreSwitches.Switch.REGIONS);
        assertEquals(" ", testing.resolve(steve, "{region:count} {region:flag:pvp}"));
    }

    @Test void givenSwitchedCommands_whenOff_thenCompletionAndHelpLeaveThemAndOnAsBefore() {
        Player admin = mock(Player.class);
        when(admin.hasPermission("vystorm_core.admin")).thenReturn(true);
        var region = new Vystorm.vystorm_core.region.RegionCommand();
        assertEquals(List.of("at", "list"), region.onTabComplete(admin, mock(Command.class), "vregion", new String[] {""}));
        assertFalse(CoreSwitches.commandOff("vregion"));
        off(CoreSwitches.Switch.REGIONS, CoreSwitches.Switch.TRADE_CHAT);
        assertEquals(List.of(), region.onTabComplete(admin, mock(Command.class), "vregion", new String[] {""}));
        assertTrue(CoreSwitches.commandOff("vregion"));
        assertTrue(CoreSwitches.commandOff("VTRADE"));
        assertFalse(CoreSwitches.commandOff("vworlds"));
        assertFalse(CoreSwitches.commandOff("vmenu"));
    }

}
