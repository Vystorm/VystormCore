package Vystorm.vystorm_core.worlds.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Review C1: nur erlaubte Befehle während eines Besuchs (Markt-Verkauf aus der Hand ist der Dupe-Weg). */
class CommandAllowListTest {
    private static final Set<String> ALLOWED = Set.of("talente", "msg", "r", "party", "spawn");
    private static final Map<String, String> PRIMARY = Map.of("talents", "talente", "talentbaum", "talente", "tell", "msg",
            "market", "market", "deepmarket:market", "market", "minecraft:give", "give", "give", "give");

    private static boolean allowed(String message) { return CommandAllowList.allowed(message, ALLOWED, PRIMARY::get); }

    @Test
    void givenMarketSellFromHand_whenInVisit_thenBlocked() {
        assertFalse(allowed("/market verkaufen 100"));
        assertFalse(allowed("/deepmarket:market sell 5"));
        assertFalse(allowed("/give Smoke diamond 64"));
        assertFalse(allowed("/minecraft:give Smoke diamond 64"));
    }

    @Test
    void givenAllowedLabelOrAlias_whenTyped_thenAllowed() {
        assertTrue(allowed("/talente welt verlassen"));
        assertTrue(allowed("/TALENTE"));
        assertTrue(allowed("/talents welt"), "alias resolves to the allowed primary name");
        assertTrue(allowed("/tell Esoren hi"));
    }

    @Test
    void givenSpoofedNamespaceWithAllowedPlainLabel_whenChecked_thenBlocked() {
        Map<String, String> primary = Map.of("evilplugin:talente", "talente", "vystormskills:talente", "talente");
        assertFalse(CommandAllowList.allowed("/evilplugin:talente", ALLOWED, primary::get));
    }

    @Test
    void givenNamespacedAliasForSameCommand_whenCheckedWithIdentity_thenAllowed() {
        Map<String, String> primary = Map.of("vystormskills:talente", "talente");
        assertTrue(CommandAllowList.allowed("/vystormskills:talente", ALLOWED, primary::get,
                (label, primaryLabel) -> label.equals("vystormskills:talente") && primaryLabel.equals("talente")));
    }

    @Test
    void givenSpoofedNamespaceForDifferentCommand_whenCheckedWithIdentity_thenBlocked() {
        Map<String, String> primary = Map.of("evilplugin:talente", "talente");
        assertFalse(CommandAllowList.allowed("/evilplugin:talente", ALLOWED, primary::get, (label, primaryLabel) -> false));
    }

    @Test
    void givenUnknownEmptyOrFailingResolver_whenChecked_thenBlocked() {
        assertFalse(allowed("/"));
        assertFalse(allowed("/unknowncmd"));
        assertFalse(CommandAllowList.allowed("/talente", Set.of(), PRIMARY::get), "empty list blocks everything");
        assertFalse(CommandAllowList.allowed("/x", ALLOWED, label -> { throw new IllegalStateException("map broken"); }));
    }

    @Test
    void givenMessages_whenLabelParsed_thenLowercaseWithNamespace() {
        assertEquals("deep:market", CommandAllowList.label("/Deep:Market verkaufen 5"));
        assertEquals("market", CommandAllowList.plain("deep:market"));
        assertEquals("", CommandAllowList.label(null));
    }
}
