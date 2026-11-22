package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@code /vadmin placeholders rankings [namespace]}: the declared rankings with label, entries and the best three. */
class RankingsAdminTest {
    private PlaceholderTesting testing;
    private PlaceholderInterface placeholders;

    private static UUID id(String name) { return UUID.nameUUIDFromBytes(name.getBytes()); }

    @BeforeEach
    void setUp() {
        testing = PlaceholderTesting.detached();
        placeholders = testing.placeholders();
        placeholders.register(PlaceholderTesting.plugin("VystormVeins"), "veins")
                .ranking("mined", RankingInfo.of("Ore mined", "Ore blocks mined from veins").unit("blocks"), n -> List.of(
                        new Row(id("Bea"), "Bea", 980), new Row(id("Alex"), "Alex", 12_345), new Row(id("Cid"), "Cid", 40),
                        new Row(id("Dan"), "Dan", 3)))
                .ranking("found", RankingInfo.of("Veins found", null), n -> List.of());
        placeholders.register(PlaceholderTesting.plugin("WinterIsComing"), "winter")
                .ranking("sled-best", RankingInfo.of("Best sled run", "Fastest run").ascending().format(RankingInfo.Format.DURATION_MILLIS),
                        n -> List.of(new Row(id("Alex"), "Alex", 65_007)));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getPlayer()).thenReturn(player);
        when(player.locale()).thenReturn(Locale.ENGLISH);
        return player;
    }

    private static List<Component> sent(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    private static List<String> lines(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (Component component : sent(sender)) out.add(PlainTextComponentSerializer.plainText().serialize(component));
        return out;
    }

    @Test
    void givenRankings_whenListed_thenIdLabelEntriesOrderPluginAndTopThree() {
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders", "rankings"});
        List<String> lines = lines(admin);
        assertEquals("Rankings – 3 rankings in 2 namespaces", lines.get(0));
        assertEquals("· veins:mined Ore mined – 4 entries, highest first VystormVeins", lines.get(1));
        assertEquals("  Ore blocks mined from veins", lines.get(2));
        assertEquals("  1. Alex 12,345 blocks", lines.get(3));
        assertEquals("  2. Bea 980 blocks", lines.get(4));
        assertEquals("  3. Cid 40 blocks", lines.get(5));
        assertEquals("· veins:found Veins found – 0 entries, highest first VystormVeins", lines.get(6));
        assertEquals("  no entries yet", lines.get(7));
        assertEquals("· winter:sled-best Best sled run – 1 entries, lowest first WinterIsComing", lines.get(8));
        assertEquals("  Fastest run", lines.get(9));
        assertEquals("  1. Alex 1:05.007", lines.get(10));
        assertTrue(lines.get(11).contains("%vystorm_NAMESPACE_top_KEY_N_name%"), lines.get(11));
        assertEquals(12, lines.size());
        HoverEvent<?> hover = sent(admin).get(1).hoverEvent();
        assertNotNull(hover);
        String hoverText = PlainTextComponentSerializer.plainText().serialize((Component) hover.value());
        assertTrue(hoverText.contains("%vystorm_veins_top_mined_1_name%") && hoverText.contains("%vystorm_veins_rank_mined%")
                && hoverText.contains("%vystorm_veins_value_mined%"), hoverText);
    }

    @Test
    void givenNamespace_whenListed_thenOnlyItsRankings_andUnknownNamesTheKnownOnes() {
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders", "rankings", "WINTER"});
        List<String> lines = lines(admin);
        assertEquals("Rankings – 1 rankings in 1 namespaces", lines.get(0));
        assertEquals("· winter:sled-best Best sled run – 1 entries, lowest first WinterIsComing", lines.get(1));

        Player other = player("Other");
        PlaceholdersAdmin.handle(other, placeholders, new String[] {"placeholders", "rankings", "nothing"});
        assertEquals(List.of("There is no ranking in the namespace nothing. Namespaces with rankings: veins, winter"), lines(other));

        Player usage = player("Usage");
        PlaceholdersAdmin.handle(usage, placeholders, new String[] {"placeholders", "rankings", "a", "b"});
        assertTrue(lines(usage).get(0).startsWith("/vadmin placeholders rankings"));
    }

    @Test
    void givenNoRankings_whenListed_thenNone_andOverviewHasNoHint() {
        PlaceholderTesting empty = PlaceholderTesting.detached();
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, empty.placeholders(), new String[] {"placeholders", "rankings"});
        assertEquals(List.of("Rankings – 0 rankings in 0 namespaces", "No plugin has declared a ranking yet."), lines(admin));
        Player overview = player("Overview");
        PlaceholdersAdmin.handle(overview, empty.placeholders(), new String[] {"placeholders"});
        assertTrue(lines(overview).stream().noneMatch(line -> line.contains("rankings")), lines(overview).toString());
    }

    @Test
    void givenRankings_whenOverviewShown_thenAHintToTheRankingList() {
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders"});
        List<String> lines = lines(admin);
        assertTrue(lines.contains("3 rankings are offered as placeholders: /vadmin placeholders rankings"), lines.toString());
    }

    @Test
    void givenHundredRows_whenListed_thenEntriesShownAsHundredPlus() {
        placeholders.register(PlaceholderTesting.plugin("Big"), "big").ranking("rows", RankingInfo.of("Rows", null), n -> {
            List<Row> rows = new ArrayList<>();
            for (int i = 0; i < n; i++) rows.add(new Row(id("p" + i), "p" + i, i));
            return rows;
        });
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders", "rankings", "big"});
        assertEquals("· big:rows Rows – 100+ entries, highest first Big", lines(admin).get(1));
    }

    @Test
    void givenTyping_whenCompleting_thenRankingsSubcommandAndItsNamespaces() {
        assertEquals(List.of("test", "rankings", "veins", "winter"), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", ""}));
        assertEquals(List.of("veins", "winter"), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", "rankings", ""}));
        assertEquals(List.of("winter"), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", "rankings", "w"}));
        assertEquals(List.of("test"), PlaceholdersAdmin.complete(PlaceholderTesting.detached().placeholders(), new String[] {"placeholders", ""}));
    }

    @Test
    void givenEveryLanguage_whenRankingKeysRead_thenAllPresent() {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        List<String> keys = List.of("rankings-usage", "rankings-hint", "rankings-header", "rankings-none", "rankings-unknown",
                "rankings-line", "rankings-description", "rankings-descending", "rankings-ascending", "rankings-hover",
                "rankings-empty", "rankings-row", "rankings-syntax");
        for (String language : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            for (String key : keys)
                assertTrue(lang.translation(language, "placeholders." + key).isPresent(), language + " placeholders." + key);
    }
}
