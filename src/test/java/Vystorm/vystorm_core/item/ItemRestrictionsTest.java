package Vystorm.vystorm_core.item;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/** Review C1: Besuchs-Werkzeuge sind für Markt und Lager gesperrt – auch ohne Item-Provider. */
class ItemRestrictionsTest {
    private static ItemStack stack(String reason) {
        ItemStack stack = mock(ItemStack.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(eq(new NamespacedKey("vystorm_core", "restricted")), eq(PersistentDataType.STRING))).thenReturn(reason);
        return stack;
    }

    @Test
    void givenVisitTool_whenValidated_thenQuarantinedAndNotOk() {
        ItemValidation validation = ItemServices.validate(stack(ItemRestrictions.VISIT_TOOL));
        assertEquals(ItemValidation.Status.QUARANTINED, validation.status());
        assertFalse(validation.ok());
        assertTrue(validation.restricted());
        assertEquals(java.util.List.of("restricted:visit-tool"), validation.messages());
    }

    @Test
    void givenProviderMessageWithRestrictedPrefix_whenValidated_thenItIsNotTreatedAsCoreRestricted() {
        ItemValidation validation = new ItemValidation(ItemValidation.Status.QUARANTINED, java.util.List.of("restricted:provider-note"));
        assertFalse(validation.restricted());
    }

    @Test
    void givenLegacyTalentWorldToolWithoutCoreMarker_whenValidated_thenRestricted() {
        ItemStack stack = mock(ItemStack.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(eq(new NamespacedKey("vystormskills", "tw_item")), eq(PersistentDataType.STRING))).thenReturn(true);
        assertEquals(java.util.Optional.of(ItemRestrictions.VISIT_TOOL), ItemRestrictions.reason(stack));
        assertTrue(ItemRestrictions.restricted(stack));
        assertTrue(ItemServices.validate(stack).restricted());
    }

    @Test
    void givenUnreadablePersistentDataLinkage_whenCheckingRestriction_thenErrorPropagates() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getItemMeta()).thenThrow(new LinkageError("broken server API"));
        assertThrows(LinkageError.class, () -> ItemRestrictions.reason(stack));
    }

    @Test
    void givenNormalItemOrNull_whenValidatedWithoutProvider_thenNotManaged() {
        assertEquals(ItemValidation.Status.NOT_MANAGED, ItemServices.validate(stack(null)).status());
        assertFalse(ItemServices.validate(stack(null)).restricted());
        assertFalse(ItemRestrictions.restricted(null));
    }

    @Test
    void givenItem_whenMarked_thenPdcWritten() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.editMeta(any())).thenReturn(true);
        assertSame(stack, ItemRestrictions.mark(stack, "visit-tool"));
        verify(stack).editMeta(any());
    }
}
