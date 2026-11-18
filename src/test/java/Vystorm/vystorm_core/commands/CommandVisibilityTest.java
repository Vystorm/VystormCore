package Vystorm.vystorm_core.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** visibleWhen (0.65.0): completion and help follow a switch at once; refresh(); same label again after startup. */
class CommandVisibilityTest {
    private final CommandTestKit kit = new CommandTestKit();
    private final List<String> chat = new ArrayList<>();
    private final AtomicBoolean powerOn = new AtomicBoolean(true);
    private final Map<String, Command> known = new HashMap<>();

    private CommandSpec spec() {
        return CommandSpec.command("rails")
                .sub("map", s -> s.executes(kit.record("map")))
                .sub("power", s -> s.visibleWhen(powerOn::get).executes(kit.record("power")))
                .sub("reload", s -> s.visibleWhen(sender -> sender instanceof Player p && p.getName().equals("Bot")).executes(kit.record("reload")))
                .build();
    }

    private CommandRegistration registration(CommandSpec spec) {
        return new CommandRegistration(kit.plugin, kit.lang, spec, null, Localization.get().core());
    }

    private Player english() { return CommandTestKit.player(Locale.US, Set.of(), chat); }

    @BeforeEach
    void setUp() {
        Registry.INSTANCE.reset();
        CommandMap map = mock(CommandMap.class);
        lenient().when(map.getKnownCommands()).thenReturn(known);
        lenient().when(map.getCommand(anyString())).thenAnswer(i -> known.get((String) i.getArgument(0)));
        lenient().when(map.register(anyString(), any(Command.class))).thenAnswer(i -> {
            Command c = i.getArgument(1);
            known.putIfAbsent(i.getArgument(0) + ":" + c.getName(), c);
            return known.putIfAbsent(c.getName(), c) == null;
        });
        Registry.INSTANCE.commandMap = () -> map;
        Registry.INSTANCE.serverLanguage = () -> "en";
        Registry.INSTANCE.serverLoadedForTests(true);
        Settings.set(Settings.DEFAULTS);
    }

    @AfterEach
    void tearDown() {
        Registry.INSTANCE.reset();
        Settings.set(Settings.DEFAULTS);
    }

    @Test
    void givenVisibleWhen_whenSwitchFlips_thenSuggestionsFollowWithoutRegisteringAgain() {
        CommandRegistration r = registration(spec());
        Player p = english();
        assertTrue(r.suggest(p, "rails", new String[]{""}).contains("power"));
        powerOn.set(false);
        List<String> off = r.suggest(p, "rails", new String[]{""});
        assertFalse(off.contains("power"), off.toString());
        assertTrue(off.contains("map"));
        assertTrue(r.suggest(p, "rails", new String[]{"po"}).isEmpty());
        powerOn.set(true);
        assertTrue(r.suggest(p, "rails", new String[]{"po"}).contains("power"));
    }

    @Test
    void givenVisibleWhen_whenOff_thenHelpLeavesItOutButItStillRuns() {
        CommandRegistration r = registration(spec());
        Player p = english();
        powerOn.set(false);
        assertFalse(r.entries(p).stream().anyMatch(e -> e.name().equals("power")));
        r.help(p, "rails", 1);
        assertFalse(chat.stream().anyMatch(line -> line.contains("/rails power")), chat.toString());
        assertTrue(chat.stream().anyMatch(line -> line.contains("/rails map")), chat.toString());
        // not blocked: the handler answers (in a plugin: "This feature is switched off on this server.")
        r.execute(p, "rails", new String[]{"power"});
        assertEquals("power", kit.ran.get(kit.ran.size() - 1).node());
        powerOn.set(true);
        assertTrue(r.entries(p).stream().anyMatch(e -> e.name().equals("power")));
    }

    @Test
    void givenSenderPredicate_whenAsked_thenPerSender() {
        CommandSpec s = spec();
        CommandSpec reload = s.children().get("reload");
        Player bot = english();
        Player other = CommandTestKit.player(Locale.US, Set.of(), chat);
        when(other.getName()).thenReturn("Other");
        assertTrue(reload.visibleTo(bot));
        assertFalse(reload.visibleTo(other));
        assertFalse(reload.visibleTo(null));
        assertTrue(s.children().get("map").visibleTo(null));
        // hidden() still wins, and several conditions must all hold; a throwing condition counts as visible
        CommandSpec built = CommandSpec.command("x")
                .sub("a", b -> b.hidden().visibleWhen(() -> true))
                .sub("b", b -> b.visibleWhen(() -> true).visibleWhen(() -> false))
                .sub("c", b -> b.visibleWhen(() -> { throw new IllegalStateException("broken"); }))
                .build();
        assertFalse(built.children().get("a").visibleTo(bot));
        assertFalse(built.children().get("b").visibleTo(bot));
        assertTrue(built.children().get("c").visibleTo(bot));
    }

    @Test
    void givenRootCondition_whenOff_thenNoSuggestions() {
        CommandSpec root = CommandSpec.command("rails").visibleWhen(powerOn::get).sub("map", s -> s.executes(kit.record("map"))).build();
        CommandRegistration r = registration(root);
        Player p = english();
        assertEquals(List.of("map", "help"), r.suggest(p, "rails", new String[]{""}));
        powerOn.set(false);
        assertTrue(r.suggest(p, "rails", new String[]{""}).isEmpty());
        assertFalse(root.visibleTo(p));
    }

    @Test
    void givenRefresh_whenCalledInABatchOrAlone_thenCommandListSentOncePerBatch() {
        Player online = mock(Player.class);
        Registry.INSTANCE.online = () -> List.of(online);
        CommandBatch.run(() -> {
            CoreCommands.refresh();
            CoreCommandsInterface.DEFAULT.refresh();
            CoreCommands.refresh();
            verify(online, never()).updateCommands();
        });
        verify(online, times(1)).updateCommands();
        CoreCommandsInterface.DEFAULT.refresh();
        verify(online, times(2)).updateCommands();
        // before the server has loaded nothing is sent (the first tick sends everything)
        Registry.INSTANCE.serverLoadedForTests(false);
        CoreCommands.refresh();
        verify(online, times(2)).updateCommands();
    }

    @Test
    void givenPaperMountedRoot_whenSamePluginRegistersSameLabelAfterLoad_thenTreeSwappedInsideTheRegistration() {
        CommandRegistration first = CoreCommands.register(kit.plugin, kit.lang, spec());
        first.mount = CommandRegistration.Mount.PAPER;          // as Paper's command event leaves it
        int entries = known.size();
        CommandSpec next = CommandSpec.command("rails").sub("speed", s -> s.executes(kit.record("speed"))).build();
        CommandRegistration second = CoreCommands.register(kit.plugin, kit.lang, next);
        assertSame(first, second);
        assertSame(next, second.spec());
        assertEquals(CommandRegistration.Mount.PAPER, second.mount());
        assertEquals(entries, known.size(), "no Bukkit root added next to the Paper node");
        assertEquals(List.of(first), CoreCommands.registrations(kit.plugin));
        second.execute(english(), "rails", new String[]{"speed"});
        assertEquals("speed", kit.ran.get(kit.ran.size() - 1).node());
        assertFalse(second.suggest(english(), "rails", new String[]{""}).contains("map"));
    }

    @Test
    void givenBukkitMountedRoot_whenRegisteredAgain_thenReplacedAsBefore() {
        CommandRegistration first = CoreCommands.register(kit.plugin, kit.lang, spec());
        assertEquals(CommandRegistration.Mount.BUKKIT, first.mount());
        CommandRegistration second = CoreCommands.register(kit.plugin, kit.lang, CommandSpec.command("rails").executes(kit.record("root")).build());
        assertNotSame(first, second);
        assertEquals(CommandRegistration.Mount.NONE, first.mount());
        assertEquals(List.of(second), CoreCommands.registrations(kit.plugin));
    }
}
