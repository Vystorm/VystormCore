package Vystorm.vystorm_core.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommandRegistryTest {
    private final Map<String, Command> known = new HashMap<>();
    private final List<String> chat = new ArrayList<>();
    private final CommandTestKit kit = new CommandTestKit();

    @BeforeEach
    void setUp() {
        Registry.INSTANCE.reset();
        CommandMap map = mock(CommandMap.class);
        when(map.getKnownCommands()).thenReturn(known);
        when(map.getCommand(anyString())).thenAnswer(i -> known.get((String) i.getArgument(0)));
        when(map.register(anyString(), any(Command.class))).thenAnswer(i -> {
            String prefix = i.getArgument(0);
            Command c = i.getArgument(1);
            known.putIfAbsent(prefix + ":" + c.getName(), c);
            boolean free = known.putIfAbsent(c.getName(), c) == null;
            for (String alias : c.getAliases()) known.putIfAbsent(alias, c);
            return free;
        });
        Registry.INSTANCE.commandMap = () -> map;
        Registry.INSTANCE.serverLanguage = () -> "de";
        Registry.INSTANCE.serverLoadedForTests(true);
        Settings.set(Settings.DEFAULTS);
    }

    @AfterEach
    void tearDown() {
        Registry.INSTANCE.reset();
        Settings.set(Settings.DEFAULTS);
    }

    private static Settings settings(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return Settings.of(c.getConfigurationSection("commands"));
    }

    private Map<String, CommandRegistration.AliasStatus> states(CommandRegistration r) {
        Map<String, CommandRegistration.AliasStatus> out = new HashMap<>();
        for (var a : r.localizedAliases()) out.put(a.alias(), a.status());
        return out;
    }

    @Test
    void givenSeveralChangesInABatch_whenDone_thenPlayersGetTheirCommandListOnce() {
        Player online = mock(Player.class);
        Registry.INSTANCE.online = () -> List.of(online);
        CommandSpec one = CommandSpec.command("menuone").executes(c -> { }).build(), two = CommandSpec.command("menutwo").executes(c -> { }).build();
        CommandRegistration[] made = new CommandRegistration[2];
        CommandBatch.run(() -> {
            made[0] = CoreCommands.register(kit.plugin, kit.lang, one);
            made[1] = CoreCommands.register(kit.plugin, kit.lang, two);
            verify(online, never()).updateCommands();   // nothing is sent while the changes are still coming
        });
        verify(online, times(1)).updateCommands();
        assertNotNull(known.get("menuone"));
        assertNotNull(known.get("menutwo"));
        // nested batches end with the outermost one; a batch without a change sends nothing
        CommandBatch.run(() -> CommandBatch.run(() -> CoreCommands.unregister(made[0])));
        verify(online, times(2)).updateCommands();
        CommandBatch.run(() -> { });
        verify(online, times(2)).updateCommands();
        // outside a batch every change is sent at once, as before
        CoreCommands.unregister(made[1]);
        verify(online, times(3)).updateCommands();
        assertNull(known.get("menutwo"));
    }

    @Test
    void givenFreeLabels_whenRegistered_thenRootFixedAndLocalizedAliasesInCommandMap() {
        CommandRegistration r = CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        assertEquals(CommandRegistration.Mount.BUKKIT, r.mount());
        assertNotNull(known.get("rails"));
        assertNotNull(known.get("cart"));
        assertEquals(Map.of("schienen", CommandRegistration.AliasStatus.ACTIVE, "bahn", CommandRegistration.AliasStatus.ACTIVE,
                "铁轨", CommandRegistration.AliasStatus.ACTIVE), states(r));
        assertSame(known.get("schienen"), known.get("testrails:schienen"));
        assertEquals("de", r.localizedAliases().get(0).language());
        assertEquals(r, CoreCommands.find("铁轨").orElseThrow());
        // the alias runs the tree with its own label
        Player p = CommandTestKit.player(Locale.GERMANY, Set.of(), chat);
        known.get("schienen").execute(p, "schienen", new String[]{"karte"});
        assertEquals("map", kit.ran.get(0).node());
        assertEquals("schienen", kit.ran.get(0).label());
        assertEquals(List.of("ziel", "karte", "tempo", "schub", "hilfe"), known.get("bahn").tabComplete(p, "bahn", new String[]{""}));
    }

    @Test
    void givenLabelOfAnotherPlugin_whenAliasApplied_thenCollisionAndUntouched() {
        Command other = new Command("bahn") {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String l, String[] a) { return true; }
        };
        known.put("bahn", other);
        CommandRegistration r = CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        assertEquals(CommandRegistration.AliasStatus.COLLISION, states(r).get("bahn"));
        assertSame(other, known.get("bahn"));
        var alias = r.localizedAliases().stream().filter(a -> a.alias().equals("bahn")).findFirst().orElseThrow();
        assertEquals("server /bahn", alias.detail());
        assertEquals(CommandRegistration.AliasStatus.ACTIVE, states(r).get("schienen"));
    }

    @Test
    void givenTwoVystormPlugins_whenSameAlias_thenFirstKeepsIt() {
        CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        Plugin second = mock(Plugin.class);
        when(second.isEnabled()).thenReturn(true);
        when(second.getName()).thenReturn("Trains");
        Lang lang = Lang.create("Trains", Map.of("en", "commands:\n  trains:\n    description: x\n", "de", "commands:\n  trains:\n    aliases: [bahn, zug]\n"));
        CommandRegistration r2 = CoreCommands.register(second, lang, CommandSpec.command("trains").executes(c -> {}).build());
        assertEquals(CommandRegistration.AliasStatus.COLLISION, states(r2).get("bahn"));
        assertTrue(r2.localizedAliases().stream().anyMatch(a -> a.alias().equals("bahn") && a.detail().equals("TestRails /rails")));
        assertEquals(CommandRegistration.AliasStatus.ACTIVE, states(r2).get("zug"));
    }

    @Test
    void givenPolicies_whenApplied_thenAliasesFollowLanguageSelection() throws Exception {
        CommandRegistration r = CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        Settings.set(settings("commands:\n  localized-aliases: default\n"));
        Registry.INSTANCE.applyAll();
        assertEquals(CommandRegistration.AliasStatus.ACTIVE, states(r).get("schienen"));
        assertEquals(CommandRegistration.AliasStatus.OFF_BY_POLICY, states(r).get("铁轨"));
        assertNull(known.get("铁轨"));
        Settings.set(settings("commands:\n  localized-aliases: none\n"));
        Registry.INSTANCE.applyAll();
        assertNull(known.get("schienen"));
        assertNotNull(known.get("rails"));
        Settings.set(settings("commands:\n  localized-aliases: all\n  disabled-aliases: [bahn, \"testrails:铁轨\"]\n"));
        Registry.INSTANCE.applyAll();
        assertEquals(CommandRegistration.AliasStatus.DISABLED, states(r).get("bahn"));
        assertEquals(CommandRegistration.AliasStatus.DISABLED, states(r).get("铁轨"));
        assertEquals(CommandRegistration.AliasStatus.ACTIVE, states(r).get("schienen"));
        assertNotNull(known.get("schienen"));
    }

    @Test
    void givenStartup_whenNotLoaded_thenAliasesPendingUntilFirstTick() {
        Registry.INSTANCE.serverLoadedForTests(false);
        Settings.set(new Settings(AliasPolicy.ALL, Set.of(), 8, false));
        CommandRegistration r = CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        assertEquals(CommandRegistration.AliasStatus.PENDING, states(r).get("schienen"));
        assertNull(known.get("schienen"));
        CoreCommands.serverLoaded();
        assertEquals(CommandRegistration.AliasStatus.ACTIVE, states(r).get("schienen"));
    }

    @Test
    void givenUnregister_whenPluginDisabled_thenEntriesRemoved() {
        CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        CoreCommands.unregister(kit.plugin);
        assertNull(known.get("rails"));
        assertNull(known.get("schienen"));
        assertTrue(CoreCommands.registrations().isEmpty());
    }

    // ------------------------------------------------------------------ adapter

    @Test
    void givenPluginYmlCommand_whenAdopted_thenOldExecutorGetsEnglishIdsAndCoreAddsHelpAndAliases() {
        List<String[]> seen = new ArrayList<>();
        CommandExecutor old = (s, c, l, a) -> { seen.add(a); return true; };
        TabCompleter oldTab = (s, c, l, a) -> List.of("dest", "map", "Central");
        PluginCommand command = mock(PluginCommand.class);
        when(command.getName()).thenReturn("rails");
        when(command.getAliases()).thenReturn(List.of("vrails"));
        when(command.getExecutor()).thenReturn(old);
        when(command.getTabCompleter()).thenReturn(oldTab);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("TestRails");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getCommand("rails")).thenReturn(command);
        CommandSpec declaration = CommandSpec.command("rails")
                .sub("dest", s -> s.arg(Arg.greedy("name").optional()))
                .sub("map", s -> {})
                .sub("power", s -> s.arg(Arg.bool("state")))
                .build();
        CommandRegistration r = CoreCommands.adopt(plugin, kit.lang, declaration);
        assertEquals(CommandRegistration.Mount.ADAPTER, r.mount());
        ArgumentCaptor<CommandExecutor> exec = ArgumentCaptor.forClass(CommandExecutor.class);
        verify(command).setExecutor(exec.capture());
        TabExecutor wrapper = (TabExecutor) exec.getValue();

        Player p = CommandTestKit.player(Locale.GERMANY, Set.of(), chat);
        wrapper.onCommand(p, command, "rails", new String[]{"ziel", "Hafen", "Nord"});
        wrapper.onCommand(p, command, "rails", new String[]{"schub", "aus"});
        wrapper.onCommand(p, command, "rails", new String[]{"unbekannt", "x"});
        assertArrayEquals(new String[]{"dest", "Hafen", "Nord"}, seen.get(0));
        assertArrayEquals(new String[]{"power", "off"}, seen.get(1));
        assertArrayEquals(new String[]{"unbekannt", "x"}, seen.get(2));

        wrapper.onCommand(p, command, "rails", new String[]{"hilfe"});
        assertEquals(3, seen.size(), "help is answered by Core");
        assertTrue(String.join("\n", chat).contains("/rails ziel [NAME...] – Ziel setzen oder anzeigen"), chat.toString());

        // completion: localized names replace the English ids, the old completer's extra values stay
        assertEquals(List.of("ziel", "karte", "schub", "hilfe", "Central"), wrapper.onTabComplete(p, command, "rails", new String[]{""}));
        // localized root alias delegates to the same tree
        assertNotNull(known.get("schienen"));
        known.get("schienen").execute(p, "schienen", new String[]{"karte"});
        assertArrayEquals(new String[]{"map"}, seen.get(3));

        when(command.getExecutor()).thenReturn(wrapper);
        CoreCommands.unregister(plugin);
        verify(command).setExecutor(old);
    }

    @Test
    void givenAdoptedCommandWithPluginYmlAlias_whenLocalizedAliasEqualsIt_thenNotAddedAgain() {
        PluginCommand command = mock(PluginCommand.class);
        when(command.getName()).thenReturn("rails");
        when(command.getAliases()).thenReturn(List.of("vrails", "Bahn"));
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("TestRails");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getCommand("rails")).thenReturn(command);
        CommandRegistration r = CoreCommands.adopt(plugin, kit.lang, CommandSpec.command("rails").sub("map", s -> {}).build());
        assertFalse(states(r).containsKey("bahn"), "plugin.yml alias is not a localized alias (no self-collision)");
        assertEquals(CommandRegistration.AliasStatus.ACTIVE, states(r).get("schienen"));
    }

    @Test
    void givenAdoptOfMissingCommand_whenCalled_thenRejected() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("X");
        assertThrows(IllegalArgumentException.class, () -> CoreCommands.adopt(plugin, kit.lang, CommandSpec.command("nope").build()));
    }

    @Test
    void givenOwner_whenDescribed_thenPluginName() {
        Command c = mock(Command.class, withSettings().extraInterfaces(PluginIdentifiableCommand.class));
        when(c.getName()).thenReturn("spawn");
        when(((PluginIdentifiableCommand) c).getPlugin()).thenReturn(kit.plugin);
        assertEquals("TestRails /spawn", Registry.owner(c));
    }

    @Test
    void givenServerLanguage_whenAuto_thenEnglishOrConfigured() {
        Localization.get().setDefaultLanguage("de");
        try { assertEquals("de", Registry.configuredServerLanguage()); }
        finally { Localization.get().setDefaultLanguage(Localization.AUTO); }
        assertTrue(Registry.enabledBy(AliasPolicy.DEFAULT, "de", "de_at"));
        assertFalse(Registry.enabledBy(AliasPolicy.DEFAULT, "zh_cn", "de"));
        assertTrue(Registry.enabledBy(AliasPolicy.ALL, "zh_cn", "de"));
        assertFalse(Registry.enabledBy(AliasPolicy.NONE, "de", "de"));
    }

    /** A fixed alias another plugin's command already owns (Core's /m menu vs. Chat's /msg alias /m on a real server). */
    @Test
    void givenFixedAliasOwnedByAnotherPlugin_whenApplied_thenReportedAsConflictAndNotFound() {
        Plugin core = mock(Plugin.class);
        when(core.getName()).thenReturn("vystorm_core");
        PluginCommand menu = mock(PluginCommand.class);
        when(menu.getName()).thenReturn("menu");
        when(menu.getPlugin()).thenReturn(core);
        known.put("cart", menu);
        CommandRegistration r = CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        assertEquals(Map.of("cart", "vystorm_core /menu"), r.fixedAliasConflicts());
        assertSame(menu, known.get("cart"));
        assertTrue(CoreCommands.find("cart").isEmpty());
        assertSame(r, CoreCommands.find("vrails").orElseThrow());
    }

    @Test
    void givenFixedAliasesOwnedByThePluginItself_whenApplied_thenNoConflict() {
        CommandRegistration r = CoreCommands.register(kit.plugin, kit.lang, kit.spec);
        assertTrue(r.fixedAliasConflicts().isEmpty());
        assertSame(r, CoreCommands.find("cart").orElseThrow());
    }
}
