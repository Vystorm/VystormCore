package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderBridge;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@code /vadmin placeholders}: overview, values of a namespace, the test subcommand and tab completion. */
class PlaceholdersAdminTest {
    private PlaceholderTesting testing;
    private PlaceholderInterface placeholders;

    @BeforeEach
    void setUp() {
        testing = PlaceholderTesting.detached();
        placeholders = testing.placeholders();
        var namespace = placeholders.register(PlaceholderTesting.plugin("XPVaultEconomy"), "xpvault");
        namespace.add(PlaceholderValue.viewer("balance", player -> player.getName() + "=120").description("XP in the account"));
        namespace.add(PlaceholderValue.global("accounts", () -> 7).description("{lang:vystorm_core:placeholders.values.core.online}").asyncSafe());
        namespace.add(PlaceholderValue.of("top", context -> "#" + context.argument(0)).arguments("<rank> <stat>"));
        placeholders.register(PlaceholderTesting.plugin("VystormBoards"), "boards", context -> "dynamic");
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

    private static final class Bridge implements PlaceholderBridge {
        final String name;
        final boolean available, providing;

        Bridge(String name, boolean available, boolean providing) { this.name = name; this.available = available; this.providing = providing; }

        @Override public String name() { return name; }
        @Override public boolean available() { return available; }
        @Override public boolean provide(Source source) { return providing; }
        @Override public boolean providing() { return providing; }
        @Override public String consume(OfflinePlayer viewer, Player other, String placeholder) { return null; }
    }

    @Test
    void givenNamespacesAndBridges_whenOverviewRequested_thenListedWithOwnerCountsAndBridgeStates() {
        testing.bridge(new Bridge("PlaceholderAPI", true, true)).bridge(new Bridge("OtherPlaceholders", true, false)).bridge(new Bridge("Missing", false, false));
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders"});
        List<String> lines = lines(admin);
        assertEquals("Placeholders – 2 namespaces with 3 values", lines.get(0));
        assertTrue(lines.get(1).contains("%vystorm_NAMESPACE_KEY%") && lines.get(1).contains("{NAMESPACE:KEY}"), lines.get(1));
        assertEquals("· boards VystormBoards – 0 values, further keys answered by the plugin", lines.get(2));
        assertEquals("· xpvault XPVaultEconomy – 3 values", lines.get(3));
        assertEquals("Other placeholder plugins:", lines.get(4));
        assertTrue(lines.get(5).startsWith("· PlaceholderAPI: connected"), lines.get(5));
        assertTrue(lines.get(6).startsWith("· OtherPlaceholders: installed"), lines.get(6));
        assertEquals("· Missing: not installed", lines.get(7));
        assertTrue(lines.get(8).startsWith("/vadmin placeholders"), lines.get(8));
        assertEquals(9, lines.size());
        ClickEvent click = sent(admin).get(3).clickEvent();
        assertNotNull(click);
        assertEquals("/vadmin placeholders xpvault", click.value());
    }

    @Test
    void givenNamespace_whenValuesRequested_thenBothFormsDescriptionAndThreadNote() {
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders", "XPVault"});
        List<String> lines = lines(admin);
        assertEquals("xpvault – 3 values of XPVaultEconomy, page 1/1", lines.get(0));
        assertEquals("· %vystorm_xpvault_balance% {xpvault:balance} – XP in the account (server thread only)", lines.get(1));
        assertEquals("· %vystorm_xpvault_accounts% {xpvault:accounts} – Players online", lines.get(2));
        assertEquals("· %vystorm_xpvault_top_<rank>_<stat>% {xpvault:top:<rank>:<stat>} – no description (server thread only)", lines.get(3));
        assertEquals(4, lines.size());
        assertEquals("/vadmin placeholders test %vystorm_xpvault_balance%", sent(admin).get(1).clickEvent().value());
        assertFalse(sent(admin).get(3).clickEvent() != null, "a value with arguments has no ready-made test command");

        Player other = player("Other");
        PlaceholdersAdmin.handle(other, placeholders, new String[] {"placeholders", "boards"});
        assertEquals(List.of("boards – 0 values of VystormBoards, page 1/1", "· The plugin answers further keys of boards that are not listed here."), lines(other));
    }

    @Test
    void givenManyValues_whenPagesRequested_thenTwelvePerPageAndAHintToTheNext() {
        var big = placeholders.register(PlaceholderTesting.plugin("Big"), "big");
        for (int i = 0; i < 14; i++) big.global("value" + i, "", () -> 1);
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders", "big"});
        List<String> first = lines(admin);
        assertEquals(14, first.size());
        assertEquals("big – 14 values of Big, page 1/2", first.get(0));
        assertEquals("Next page: /vadmin placeholders big 2", first.get(13));
        Player second = player("Second");
        PlaceholdersAdmin.handle(second, placeholders, new String[] {"placeholders", "big", "2"});
        assertEquals(3, lines(second).size());
        Player wrong = player("Wrong");
        PlaceholdersAdmin.handle(wrong, placeholders, new String[] {"placeholders", "big", "two"});
        assertTrue(lines(wrong).get(0).startsWith("/vadmin placeholders"));
    }

    @Test
    void givenUnknownNamespace_whenRequested_thenKnownOnesAreNamed() {
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders", "nothing"});
        assertEquals(List.of("There is no namespace nothing. Known: boards, xpvault"), lines(admin));
    }

    @Test
    void givenText_whenTested_thenResolvedForTheSenderAndShownLiterally() {
        Player admin = player("Admin");
        PlaceholdersAdmin.handle(admin, placeholders, new String[] {"placeholders", "test", "Balance:", "%vystorm_xpvault_balance%", "<red>of", "{xpvault:accounts}"});
        assertEquals(List.of("Result: Balance: Admin=120 <red>of 7"), lines(admin));

        Player unchanged = player("Unchanged");
        PlaceholdersAdmin.handle(unchanged, placeholders, new String[] {"placeholders", "test", "50", "%", "{nothing:here}"});
        assertEquals(List.of("Nothing was replaced: 50 % {nothing:here}"), lines(unchanged));

        Player usage = player("Usage");
        PlaceholdersAdmin.handle(usage, placeholders, new String[] {"placeholders", "test"});
        assertTrue(lines(usage).get(0).startsWith("/vadmin placeholders test"));
    }

    @Test
    void givenConsole_whenTested_thenPlayerValuesStayAndAHintSaysWhy() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        PlaceholdersAdmin.handle(console, placeholders, new String[] {"placeholders", "test", "%vystorm_xpvault_balance%", "{xpvault:accounts}"});
        List<String> lines = lines(console);
        assertEquals("Result: %vystorm_xpvault_balance% 7", lines.get(0));
        assertTrue(lines.get(1).startsWith("The console is no player"), lines.get(1));
    }

    @Test
    void givenTypedArguments_whenCompleting_thenNamespacesTestAndReadyPlaceholders() {
        assertEquals(List.of("test", "boards", "xpvault"), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", ""}));
        assertEquals(List.of("xpvault"), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", "XP"}));
        assertEquals(List.of("test"), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", "t"}));
        assertEquals(List.of("%vystorm_xpvault_balance%", "%vystorm_xpvault_accounts%"),
                PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", "test", "Online:", "%vy"}));
        assertEquals(List.of(), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", "test", "Onl"}));
        assertEquals(List.of(), PlaceholdersAdmin.complete(placeholders, new String[] {"placeholders", "xpvault", ""}));
    }
}
