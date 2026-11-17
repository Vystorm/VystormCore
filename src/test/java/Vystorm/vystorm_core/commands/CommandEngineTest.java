package Vystorm.vystorm_core.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CommandEngineTest {
    private final CommandTestKit kit = new CommandTestKit();
    private final List<String> chat = new ArrayList<>();

    private Player german() { return CommandTestKit.player(Locale.GERMANY, Set.of(), chat); }
    private Player english() { return CommandTestKit.player(Locale.US, Set.of(), chat); }
    private Player chinese() { return CommandTestKit.player(CommandTestKit.ZH_CN, Set.of(), chat); }

    // ------------------------------------------------------------------ tree building

    @Test
    void givenBadTrees_whenBuilt_thenRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.command("x").sub("a", s -> s.aliases("b")).sub("b", s -> {}).build());
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.command("x").arg(Arg.greedy("all")).arg(Arg.word("more")));
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.command("x").arg(Arg.word("a").optional()).arg(Arg.word("b")));
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.command("Bad Name"));
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.command("x").sub("a", s -> {}).sub("a", s -> {}));
    }

    @Test
    void givenTree_whenBuilt_thenLangPathsAndParentsFollowConvention() {
        CommandSpec dest = kit.spec.children().get("dest");
        assertEquals("commands.rails", kit.spec.langPath());
        assertEquals("commands.rails.sub.dest", dest.langPath());
        assertSame(kit.spec, dest.parent());
        assertEquals(List.of("dest"), dest.idPath());
        CommandSpec custom = CommandSpec.command("x").lang("cmd.x").sub("y", s -> s.sub("z", t -> {})).build();
        assertEquals("cmd.x.sub.y.sub.z", custom.children().get("y").children().get("z").langPath());
    }

    // ------------------------------------------------------------------ localized matching

    @Test
    void givenGermanPlayer_whenGermanEnglishOrChineseName_thenSameSubcommand() {
        Player p = german();
        kit.registration.execute(p, "rails", new String[]{"ziel", "Hafen", "Nord"});
        kit.registration.execute(p, "rails", new String[]{"dest", "Mitte"});
        kit.registration.execute(p, "rails", new String[]{"目的地", "X"});
        kit.registration.execute(p, "rails", new String[]{"REISEZIEL"});
        kit.registration.execute(p, "rails", new String[]{"destination"});
        assertEquals(5, kit.ran.size(), chat.toString());
        assertTrue(kit.ran.stream().allMatch(r -> r.node().equals("dest")));
        assertEquals("Hafen Nord", kit.ran.get(0).values().get("name"));
        assertNull(kit.ran.get(3).values().get("name"));
    }

    @Test
    void givenEnglishPlayer_whenGermanName_thenAcceptedToo() {
        kit.registration.execute(english(), "rails", new String[]{"karte"});
        kit.registration.execute(chinese(), "rails", new String[]{"karte"});
        assertEquals(2, kit.ran.size());
        assertEquals("map", kit.ran.get(1).node());
    }

    @Test
    void givenLocalizedKeywords_whenParsed_thenEnglishValue() {
        Player p = german();
        kit.registration.execute(p, "rails", new String[]{"schub", "an"});
        kit.registration.execute(p, "rails", new String[]{"schub", "AUS"});
        kit.registration.execute(english(), "rails", new String[]{"power", "true"});
        kit.registration.execute(english(), "rails", new String[]{"power", "ein"});
        assertEquals(List.of(true, false, true, true), kit.ran.stream().map(r -> r.values().get("state")).toList());
    }

    @Test
    void givenBadArguments_whenRun_thenLocalizedErrorWithUsage() {
        Player p = german();
        kit.registration.execute(p, "rails", new String[]{"tempo", "500"});
        kit.registration.execute(p, "rails", new String[]{"tempo", "schnell"});
        kit.registration.execute(p, "rails", new String[]{"tempo"});
        kit.registration.execute(p, "rails", new String[]{"schub", "vielleicht"});
        kit.registration.execute(p, "schienen", new String[]{"tempo", "5", "6"});
        assertTrue(kit.ran.isEmpty());
        String all = String.join("\n", chat);
        assertTrue(all.contains("500 muss zwischen 1 und 200 liegen."), all);
        assertTrue(all.contains("schnell ist keine Zahl."), all);
        assertTrue(all.contains("Es fehlt: KMH"), all);
        assertTrue(all.contains("Verwendung: /rails tempo KMH"), all);
        assertTrue(all.contains("Möglich: an, aus"), all);
        assertTrue(all.contains("Verwendung: /schienen tempo KMH"), all);
        assertTrue(all.contains("Zu viele Angaben."), all);
    }

    @Test
    void givenUnknownSubcommand_whenRun_thenDidYouMeanInOwnLanguage() {
        kit.registration.execute(german(), "rails", new String[]{"zeil"});
        assertTrue(chat.get(0).contains("Unbekannter Unterbefehl: zeil"), chat.toString());
        assertTrue(chat.get(1).contains("Meintest du ziel?"), chat.toString());
        chat.clear();
        kit.registration.execute(english(), "rails", new String[]{"mpa"});
        assertTrue(chat.get(1).contains("Did you mean map?"), chat.toString());
    }

    @Test
    void givenPermissionsAndConsole_whenRun_thenChecked() {
        List<String> console = new ArrayList<>();
        kit.registration.execute(german(), "rails", new String[]{"neuladen"});
        assertEquals("Dafür hast du keine Berechtigung.", chat.get(0));
        kit.registration.execute(CommandTestKit.console(console), "rails", new String[]{"map"});
        assertEquals("Only players can do that.", console.get(0));
        kit.registration.execute(CommandTestKit.console(console), "rails", new String[]{"reload"});
        assertEquals("reload", kit.ran.get(0).node());
    }

    // ------------------------------------------------------------------ suggestions

    @Test
    void givenLanguages_whenCompletingFirstWord_thenOwnLanguageNames() {
        assertEquals(List.of("ziel", "karte", "tempo", "schub", "hilfe"), kit.registration.suggest(german(), "rails", new String[]{""}));
        assertEquals(List.of("dest", "map", "speed", "power", "help"), kit.registration.suggest(english(), "rails", new String[]{""}));
        assertEquals(List.of("目的地", "地图", "速度", "加速", "帮助"), kit.registration.suggest(chinese(), "rails", new String[]{""}));
        Player admin = CommandTestKit.player(Locale.GERMANY, Set.of("rails.admin"), chat);
        assertTrue(kit.registration.suggest(admin, "rails", new String[]{""}).contains("neuladen"));
    }

    @Test
    void givenPrefix_whenOnlyEnglishMatches_thenEnglishOffered() {
        assertEquals(List.of("tempo"), kit.registration.suggest(german(), "rails", new String[]{"t"}));
        assertEquals(List.of("dest"), kit.registration.suggest(german(), "rails", new String[]{"de"}));
        assertEquals(List.of("karte"), kit.registration.suggest(english(), "rails", new String[]{"ka"}));
    }

    @Test
    void givenArguments_whenCompleting_thenLocalizedKeywordsAndCustomSuggestions() {
        assertEquals(List.of("an", "aus"), kit.registration.suggest(german(), "rails", new String[]{"schub", ""}));
        assertEquals(List.of("on", "off"), kit.registration.suggest(english(), "rails", new String[]{"power", ""}));
        assertEquals(List.of("clear", "Central"), kit.registration.suggest(german(), "rails", new String[]{"ziel", "c"}));
        assertEquals(List.of(), kit.registration.suggest(german(), "rails", new String[]{"tempo", ""}));
        assertEquals(List.of(), kit.registration.suggest(german(), "rails", new String[]{"karte", ""}));
    }

    @Test
    void givenGreedyArgument_whenCompletingLaterWords_thenItsSuggesterStillAnswers() {
        assertEquals(List.of("Harbour"), kit.registration.suggest(german(), "rails", new String[]{"ziel", "Hafen", "h"}));
        assertEquals(List.of("clear", "Central", "Harbour"), kit.registration.suggest(english(), "rails", new String[]{"dest", "Hafen", "Nord", ""}));
    }

    // ------------------------------------------------------------------ help

    @Test
    void givenHelp_whenGerman_thenLocalizedClickableLinesFilteredByPermission() {
        kit.registration.execute(german(), "schienen", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(chat.get(0).contains("/schienen") && chat.get(0).contains("Hilfe 1/1"), all);
        assertTrue(all.contains("Ziele und Streckenkarte"), all);
        assertTrue(all.contains("/schienen ziel [NAME...] – Ziel setzen oder anzeigen"), all);
        assertTrue(all.contains("/schienen schub an|aus – Schub an- oder ausschalten"), all);
        assertFalse(all.contains("neuladen"), all);
        chat.clear();
        kit.registration.execute(english(), "rails", new String[]{"?"});
        assertTrue(String.join("\n", chat).contains("/rails speed KMH – Set the cart speed"), chat.toString());
    }

    @Test
    void givenNoArguments_whenRootHasNoHandler_thenHelp() {
        kit.registration.execute(chinese(), "铁轨", new String[0]);
        assertTrue(String.join("\n", chat).contains("/铁轨 目的地 [NAME...] – 设置或显示目的地"), chat.toString());
    }

    @Test
    void givenManyEntries_whenHelpPaged_thenPagesAndNavigation() {
        CommandSpec.Builder b = CommandSpec.command("big");
        for (int i = 0; i < 20; i++) { int n = i; b.sub("s" + i, s -> s.description("entry " + n).executes(c -> {})); }
        CommandRegistration big = new CommandRegistration(kit.plugin, kit.lang, b.build(), null, Vystorm.vystorm_core.lang.Localization.get().core());
        big.execute(english(), "big", new String[]{"help", "3"});
        assertTrue(chat.get(0).contains("help 3/3"), chat.toString());
        assertTrue(String.join("\n", chat).contains("/big s16 – entry 16"), chat.toString());
        assertTrue(chat.get(chat.size() - 1).contains("previous page"), chat.toString());
    }

    @Test
    void givenSubNamesClash_whenConflictsChecked_thenReported() {
        String de = "commands:\n  x:\n    sub:\n      a:\n        name: b\n";
        var lang = Vystorm.vystorm_core.lang.Lang.create("Clash", java.util.Map.of("en", "x: y\n", "de", de));
        CommandSpec spec = CommandSpec.command("x").sub("a", s -> s.executes(c -> {})).sub("b", s -> s.executes(c -> {})).build();
        CommandRegistration r = new CommandRegistration(kit.plugin, lang, spec, null, Vystorm.vystorm_core.lang.Localization.get().core());
        assertEquals(List.of("de: /x b → a, b"), r.conflicts());
        // the own language wins: a German player typing "b" gets a
        List<String> got = new ArrayList<>();
        CommandSpec spec2 = CommandSpec.command("x").sub("a", s -> s.executes(c -> got.add("a"))).sub("b", s -> s.executes(c -> got.add("b"))).build();
        CommandRegistration r2 = new CommandRegistration(kit.plugin, lang, spec2, null, Vystorm.vystorm_core.lang.Localization.get().core());
        r2.execute(german(), "x", new String[]{"b"});
        r2.execute(english(), "x", new String[]{"b"});
        assertEquals(List.of("a", "b"), got);
    }

    @Test
    void givenUsage_whenRendered_thenLocalizedPath() {
        assertEquals("/schienen ziel [NAME...]", kit.registration.usage(german(), "schienen", kit.spec.children().get("dest")));
        assertEquals("/rails speed KMH", kit.registration.usage(english(), "rails", kit.spec.children().get("speed")));
    }

    @Test
    void givenLevenshtein_whenComputed_thenEditDistance() {
        assertEquals(1, Engine.distance("zeil", "ziel"));
        assertEquals(2, Engine.distance("kitten", "sitting") - 1);
        assertEquals(0, Engine.distance("map", "map"));
        assertEquals(3, Engine.distance("", "map"));
    }
}
