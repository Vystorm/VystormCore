package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderHandler;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.StubExpansions;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The PlaceholderAPI bridge in both directions, against the hand-written stand-in for PlaceholderAPI in the test
 * sources ({@code me.clip.placeholderapi}); no jar of the real plugin is involved.
 */
class PlaceholderApiBridgeTest {
    private final AtomicReference<Plugin> installed = new AtomicReference<>();
    private boolean offer = true, ask = true, askAsync;
    private PlaceholderTesting testing;
    private PlaceholderInterface placeholders;
    private PlaceholderApiBridge bridge;
    private OfflinePlayer alex;

    private static Plugin papi(boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("PlaceholderAPI");
        when(plugin.isEnabled()).thenReturn(enabled);
        return plugin;
    }

    private static Player online(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getPlayer()).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    /** An expansion of "another plugin" in the fake PlaceholderAPI. */
    private static final class Foreign extends PlaceholderExpansion implements Relational {
        @Override public String getIdentifier() { return "other"; }
        @Override public String getAuthor() { return "someone"; }
        @Override public String getVersion() { return "1"; }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            return switch (params) {
                case "name" -> player == null ? "nobody" : player.getName();
                case "evil" -> "%vystorm_xpvault_balance% <red>{xpvault:balance}";
                case "boom" -> throw new IllegalStateException("expansion broke");
                default -> null;
            };
        }

        @Override public String onPlaceholderRequest(Player one, Player two, String identifier) {
            return identifier.equals("pair") ? one.getName() + "+" + two.getName() : null;
        }
    }

    @BeforeEach
    void setUp() {
        StubExpansions.clear();
        testing = PlaceholderTesting.detached();
        placeholders = testing.placeholders();
        alex = PlaceholderTesting.viewer("Alex");
        placeholders.register(PlaceholderTesting.plugin("XPVault"), "xpvault")
                .value("balance", player -> player.getName() + "=120")
                .add(PlaceholderValue.global("accounts", () -> 7).asyncSafe());
        bridge = new PlaceholderApiBridge(installed::get, "Vystorm", "0.65.0", () -> offer, () -> ask, () -> askAsync);
    }

    @AfterEach
    void tearDown() { StubExpansions.clear(); }

    private void install() {
        installed.set(papi(true));
        testing.bridge(bridge);
    }

    // ------------------------------------------------------------------ provide

    @Test
    void givenPlaceholderApi_whenBridgeAdded_thenOneExpansionAnswersEveryVystormPlaceholder() {
        install();
        PlaceholderExpansion expansion = StubExpansions.get("vystorm");
        assertNotNull(expansion);
        assertEquals("vystorm", expansion.getIdentifier());
        assertEquals("Vystorm", expansion.getAuthor());
        assertEquals("0.65.0", expansion.getVersion());
        assertTrue(expansion.persist() && expansion.canRegister() && expansion.isRegistered());
        assertTrue(bridge.available() && bridge.providing());
        assertEquals("PlaceholderAPI", bridge.name());

        assertEquals("Balance Alex=120 of 7", PlaceholderAPI.setPlaceholders(alex, "Balance %vystorm_xpvault_balance% of %vystorm_xpvault_accounts%"));
        assertEquals("%vystorm_xpvault_missing% %vystorm_nobody_x%", PlaceholderAPI.setPlaceholders(alex, "%vystorm_xpvault_missing% %vystorm_nobody_x%"));
        assertEquals("%vystorm_xpvault_balance%", PlaceholderAPI.setPlaceholders(null, "%vystorm_xpvault_balance%"));
        // a value registered later needs no new expansion
        placeholders.register(PlaceholderTesting.plugin("Boards"), "boards").resolver("votes", "", "<board>", context -> "v:" + context.rest(0));
        assertEquals("v:event_pvp", PlaceholderAPI.setPlaceholders(alex, "%vystorm_boards_votes_event_pvp%"));
        assertSame(expansion, StubExpansions.get("vystorm"));
    }

    @Test
    void givenTwoPlayers_whenPlaceholderApiAsksRelational_thenTheRelationalValueAnswers() {
        install();
        placeholders.register(PlaceholderTesting.plugin("Parties"), "party").relational("same", "", (one, two) -> one.getName() + ">" + two.getName());
        Player steve = online("Steve"), herobrine = online("Herobrine");
        assertEquals("Steve>Herobrine", PlaceholderAPI.setRelationalPlaceholders(steve, herobrine, "%rel_vystorm_party_same%"));
        assertEquals("%vystorm_party_same%", PlaceholderAPI.setPlaceholders(steve, "%vystorm_party_same%"));
    }

    @Test
    void givenPlaceholderApiCallsFromAnotherThread_whenValueIsNotAsyncSafe_thenItStaysAsWritten() throws Exception {
        install();
        AtomicReference<String> result = new AtomicReference<>();
        Thread chat = new Thread(() -> result.set(PlaceholderAPI.setPlaceholders(alex, "%vystorm_xpvault_balance% %vystorm_xpvault_accounts%")));
        chat.start();
        chat.join();
        assertEquals("%vystorm_xpvault_balance% 7", result.get());
    }

    @Test
    void givenProvideSwitchedOff_whenBridgeAdded_thenNoExpansionButForeignPlaceholdersWork() {
        offer = false;
        new Foreign().register();
        install();
        assertNull(StubExpansions.get("vystorm"));
        assertFalse(bridge.providing());
        assertTrue(bridge.available());
        assertEquals("Alex", placeholders.resolve(alex, "%other_name%"));
        offer = true;
        placeholders.refreshBridges();
        assertNotNull(StubExpansions.get("vystorm"));
        offer = false;
        placeholders.refreshBridges();
        assertNull(StubExpansions.get("vystorm"));
    }

    // ------------------------------------------------------------------ consume

    @Test
    void givenForeignPlaceholders_whenVystormTextResolved_thenPlaceholderApiAnswersThemOneByOne() {
        new Foreign().register();
        install();
        assertEquals("Alex has Alex=120, %other_unknown% and %nobody_x%",
                placeholders.resolve(alex, "%other_name% has {xpvault:balance}, %other_unknown% and %nobody_x%"));
        assertEquals("nobody", placeholders.resolve(null, "%other_name%"));
        // what PlaceholderAPI returns is not read again, neither by Core nor by PlaceholderAPI
        assertEquals("%vystorm_xpvault_balance% <red>{xpvault:balance}", placeholders.resolve(alex, "%other_evil%"));
        assertEquals("%other_boom% Alex", placeholders.resolve(alex, "%other_boom% %other_name%"));
    }

    @Test
    void givenSecondPlayer_whenRelationalForeignPlaceholderResolved_thenTheRelationalEntryIsUsed() {
        new Foreign().register();
        install();
        Player steve = online("Steve"), herobrine = online("Herobrine");
        assertEquals("Steve+Herobrine Steve", placeholders.resolve(steve, herobrine, "%rel_other_pair% %other_name%"));
        assertEquals("%rel_other_pair%", placeholders.resolve(steve, "%rel_other_pair%"));
    }

    @Test
    void givenConsumeSwitchedOff_whenResolved_thenForeignPlaceholdersStayButVystormIsOffered() {
        ask = false;
        new Foreign().register();
        install();
        assertEquals("%other_name%", placeholders.resolve(alex, "%other_name%"));
        assertTrue(bridge.providing());
        ask = true;
        placeholders.refreshBridges();
        assertEquals("Alex", placeholders.resolve(alex, "%other_name%"));
    }

    @Test
    void givenAnotherThread_whenForeignPlaceholderResolved_thenOnlyWithConsumeAsync() {
        new Foreign().register();
        install();
        assertEquals("%other_name% 7", testing.resolveOffThread(alex, "%other_name% {xpvault:accounts}"));
        askAsync = true;
        assertEquals("Alex 7", testing.resolveOffThread(alex, "%other_name% {xpvault:accounts}"));
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void givenNoPlaceholderApi_whenBridgeAdded_thenNothingHappensAndTextsStayAsWritten() {
        testing.bridge(bridge);
        assertFalse(bridge.available());
        assertFalse(bridge.providing());
        assertNull(StubExpansions.get("vystorm"));
        assertEquals("%other_name% Alex=120", placeholders.resolve(alex, "%other_name% {xpvault:balance}"));
        assertNull(bridge.consume(alex, null, "%other_name%"));
    }

    @Test
    void givenPlaceholderApiEnabledAfterCore_whenBridgesRefreshed_thenConnected() {
        new Foreign().register();
        testing.bridge(bridge);
        installed.set(papi(false));
        placeholders.refreshBridges();
        assertFalse(bridge.providing());
        installed.set(papi(true));
        placeholders.refreshBridges();
        assertTrue(bridge.providing());
        assertEquals("Alex=120", PlaceholderAPI.setPlaceholders(alex, "%vystorm_xpvault_balance%"));
        assertEquals("Alex", placeholders.resolve(alex, "%other_name%"));
    }

    @Test
    void givenPlaceholderApiReloaded_whenExpansionsAreDropped_thenCoreStaysOrComesBack() {
        install();
        PlaceholderExpansion first = StubExpansions.get("vystorm");
        StubExpansions.reload();
        assertSame(first, StubExpansions.get("vystorm"), "a persisting expansion survives /papi reload");
        placeholders.refreshBridges();
        assertSame(first, StubExpansions.get("vystorm"), "refreshing does not register twice");

        StubExpansions.clear();
        placeholders.refreshBridges();
        PlaceholderExpansion second = StubExpansions.get("vystorm");
        assertNotNull(second);
        assertNotSame(first, second);
        assertTrue(bridge.providing());
        assertEquals("7", PlaceholderAPI.setPlaceholders(null, "%vystorm_xpvault_accounts%"));
    }

    @Test
    void givenPlaceholderApiDisabledAndEnabledAgain_whenBridgesRefreshed_thenDisconnectedAndConnected() {
        new Foreign().register();
        install();
        Plugin old = installed.get();
        when(old.isEnabled()).thenReturn(false);
        StubExpansions.clear();
        placeholders.refreshBridges();
        assertFalse(bridge.available());
        assertFalse(bridge.providing());
        assertEquals("%other_name%", placeholders.resolve(alex, "%other_name%"));

        new Foreign().register();
        installed.set(papi(true));
        placeholders.refreshBridges();
        assertTrue(bridge.providing());
        assertNotNull(StubExpansions.get("vystorm"));
        assertEquals("Alex", placeholders.resolve(alex, "%other_name%"));
    }

    @Test
    void givenCoreDisabled_whenRegistryCleared_thenTheExpansionIsTakenOutOfPlaceholderApi() {
        install();
        assertNotNull(StubExpansions.get("vystorm"));
        ((PlaceholderHandler) placeholders).clear();
        assertNull(StubExpansions.get("vystorm"));
        assertFalse(bridge.providing());
        assertEquals("%vystorm_xpvault_accounts%", PlaceholderAPI.setPlaceholders(null, "%vystorm_xpvault_accounts%"));
    }

    @Test
    void givenAnotherExpansionNamedVystorm_whenBridgeAdded_thenCoreReportsAndDoesNotProvide() {
        PlaceholderExpansion squatter = new PlaceholderExpansion() {
            @Override public String getIdentifier() { return "vystorm"; }
            @Override public String getAuthor() { return "x"; }
            @Override public String getVersion() { return "1"; }
        };
        squatter.register();
        install();
        assertFalse(bridge.providing());
        assertSame(squatter, StubExpansions.get("vystorm"));
    }
}
