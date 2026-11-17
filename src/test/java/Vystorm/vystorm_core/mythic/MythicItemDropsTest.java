package Vystorm.vystorm_core.mythic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MythicItemDropsTest {
    @Test void givenLegacyGemWhenMmoItemsAbsentThenResolveNativeNamespace() {
        assertEquals(Optional.of("mmoitems:GEM_STONE:PYR_W_SCHADEN_I"),
                MythicItemDrops.reference("mmoitems", "GEM_STONE", "PYR_W_SCHADEN_I", false));
        assertTrue(MythicItemDrops.reference("mmoitems", "GEM_STONE", "PYR_W_SCHADEN_I", true).isEmpty());
    }

    @Test void givenNativeReferenceWhenParsingThenNoCommandOrMalformedIdIsAccepted() {
        assertEquals(Optional.of("mmoitems:gem_stone/skill_feuerbolzen"),
                MythicItemDrops.reference("vystorm", "", "mmoitems:gem_stone/skill_feuerbolzen", true));
        assertEquals(Optional.of("vystorm:weapon/blade"),
                MythicItemDrops.reference("vystormitem", "", "weapon/blade", false));
        assertTrue(MythicItemDrops.reference("mmoitems", "SWORD", "a;command=op", false).isEmpty());
        assertTrue(MythicItemDrops.reference("command", "", "foo", false).isEmpty());
        assertTrue(MythicItemDrops.reference("mmoitems", "", "foo", false).isEmpty());
    }

    @Test void givenInvalidOrOversizedAmountWhenResolvingThenBoundToOneStack() {
        assertEquals(0, MythicItemDrops.amount(Double.NaN));
        assertEquals(0, MythicItemDrops.amount(Double.POSITIVE_INFINITY));
        assertEquals(0, MythicItemDrops.amount(-1));
        assertEquals(64, MythicItemDrops.amount(1000));
        assertEquals(3, MythicItemDrops.amount(3.9));
    }
}
