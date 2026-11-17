package Vystorm.vystorm_core.item;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ItemServicesTest {
    @Test void anvilPreviewUsesCopiesAndMissingOrFailingProvidersFailClosed() {
        var player = mock(org.bukkit.entity.Player.class);
        var original = mock(ItemStack.class);
        var originalCopy = mock(ItemStack.class);
        var proposed = mock(ItemStack.class);
        var proposedCopy = mock(ItemStack.class);
        var result = mock(ItemStack.class);
        var resultCopy = mock(ItemStack.class);
        when(original.clone()).thenReturn(originalCopy);
        when(proposed.clone()).thenReturn(proposedCopy);
        when(result.clone()).thenReturn(resultCopy);
        assertTrue(ItemServices.prepareAnvil(original, null, proposed, "name", player).isEmpty());
        assertFalse(ItemServices.commitAnvil(result, player));
        var provider = mock(ItemProvider.class);
        when(provider.validate(any())).thenReturn(ItemValidation.valid());
        when(provider.prepareAnvil(originalCopy, null, proposedCopy, "name", player)).thenReturn(Optional.of(result));
        ItemServices.register(items, provider);
        assertSame(resultCopy, ItemServices.prepareAnvil(original, null, proposed, "name", player).orElseThrow());
        when(provider.commitAnvil(resultCopy, player)).thenReturn(true);
        assertTrue(ItemServices.commitAnvil(result, player));
        when(provider.prepareAnvil(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("test"));
        assertTrue(ItemServices.prepareAnvil(original, null, proposed, "name", player).isEmpty());
        when(items.isEnabled()).thenReturn(false);
        assertFalse(ItemServices.commitAnvil(result, player));
    }
    private final Plugin items = plugin("items");

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    @AfterEach
    void cleanup() {
        ItemServices.unregister(items);
        ItemRefs.unregisterAll(items);
    }

    @Test
    void givenNoProvider_whenFacadeUsed_thenVanillaFallbacks() {
        assertFalse(ItemServices.available());
        assertTrue(ItemServices.create(null).isEmpty());
        assertEquals(List.of(), ItemServices.generateLoot("x", null));
        assertEquals(Map.of(), ItemServices.equipmentStats(null));
        assertEquals(Map.of(), ItemServices.equipmentStatContributions(null));
        assertTrue(ItemServices.encode(null).isEmpty());
        assertTrue(ItemServices.decode(new StoredItem(1, "t", UUID.randomUUID(), "f", 1, "")).isEmpty());
        assertTrue(ItemServices.repair(null, 1, null).isEmpty());
        assertTrue(ItemServices.damage(null, 1, null).isEmpty());
        assertFalse(ItemServices.templateExists("x"));
        assertTrue(ItemServices.provenance(UUID.randomUUID()).isEmpty());
    }

    @Test
    void givenSourceProvider_whenQueried_thenFacadeCopiesNestedMaps() {
        ItemProvider provider = mock(ItemProvider.class);
        var sources = new java.util.HashMap<String, Vystorm.vystorm_core.stat.StatSum.Contribution>();
        sources.put("head", new Vystorm.vystorm_core.stat.StatSum.Contribution(
                Vystorm.vystorm_core.stat.StatSum.Operation.ADD, 3));
        when(provider.equipmentStatContributions(any())).thenReturn(Map.of("armor", sources));
        ItemServices.register(items, provider);
        var result = ItemServices.equipmentStatContributions(null);
        sources.clear();
        assertEquals(3, new Vystorm.vystorm_core.stat.StatSum(result.get("armor")).value());
        assertThrows(UnsupportedOperationException.class, () -> result.get("armor").clear());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void givenFailingProvider_whenFacadeUsed_thenExceptionsAreContained() {
        ItemProvider provider = mock(ItemProvider.class);
        RuntimeException boom = new IllegalStateException("boom");
        when(provider.create(any())).thenThrow(boom);
        when(provider.validate(any())).thenThrow(boom);
        when(provider.durability(any())).thenThrow(boom);
        when(provider.generateLoot(any(), any())).thenThrow(boom);
        when(provider.equipmentStats(any())).thenThrow(boom);
        when(provider.equipmentStatContributions(any())).thenThrow(boom);
        when(provider.decode(any())).thenThrow(boom);
        when(provider.templateExists(any())).thenThrow(boom);
        ItemServices.register(items, provider);
        assertTrue(ItemServices.create(null).isEmpty());
        assertNotNull(ItemServices.validate(null));
        assertTrue(ItemServices.durability(null).isEmpty());
        assertEquals(List.of(), ItemServices.generateLoot("x", null));
        assertEquals(Map.of(), ItemServices.equipmentStats(null));
        assertEquals(Map.of(), ItemServices.equipmentStatContributions(null));
        assertTrue(ItemServices.decode(new StoredItem(1, "t", UUID.randomUUID(), "f", 1, "")).isEmpty());
        assertFalse(ItemServices.templateExists("x"));
    }

    @Test
    void givenProvider_whenFacadeUsed_thenDelegates() {
        ItemProvider provider = mock(ItemProvider.class);
        UUID instance = UUID.randomUUID();
        when(provider.templateExists("sword")).thenReturn(true);
        when(provider.provenance(instance)).thenReturn(Optional.of("player:x"));
        ItemServices.register(items, provider);
        assertTrue(ItemServices.available());
        assertTrue(ItemServices.templateExists("sword"));
        assertEquals("player:x", ItemServices.provenance(instance).orElseThrow());
        ItemServices.unregister(plugin("other"));
        assertTrue(ItemServices.available(), "fremder Owner entfernt nichts");
        ItemServices.unregister(items);
        assertFalse(ItemServices.available());
    }

    @Test
    void givenNamespaces_whenResolved_thenMinecraftBuiltinCustomAndFailuresHandled() {
        assertTrue(ItemRefs.resolve("", 1).isEmpty());
        assertTrue(ItemRefs.resolve("unknown_ns:x", 1).isEmpty());
        ItemStack stack = mock(ItemStack.class);
        when(stack.clone()).thenReturn(stack);
        ItemRefs.register(items, "Items_Test", (id, amount) -> id.equals("ok") ? Optional.of(stack) : Optional.empty());
        assertTrue(ItemRefs.available("items_test"));
        assertSame(stack, ItemRefs.resolve("ITEMS_TEST:ok", 2).orElseThrow());
        assertTrue(ItemRefs.resolve("items_test:missing", 1).isEmpty());
        ItemRefs.register(items, "items_fail", (id, amount) -> { throw new IllegalStateException("boom"); });
        assertTrue(ItemRefs.resolve("items_fail:x", 1).isEmpty());
        ItemRefs.unregisterAll(items);
        assertFalse(ItemRefs.available("items_test"));
    }

    @Test
    void givenSameNamespaceFromTwoPlugins_whenRegistered_thenLastWinsAndDisableOfFirstKeepsSecond() {
        Plugin mobs = plugin("mobs");
        ItemStack fromItems = mock(ItemStack.class), fromMobs = mock(ItemStack.class);
        when(fromItems.clone()).thenReturn(fromItems);
        when(fromMobs.clone()).thenReturn(fromMobs);
        ItemRefs.register(items, "items_dup", (id, amount) -> Optional.of(fromItems));
        ItemRefs.register(mobs, "items_dup", (id, amount) -> Optional.of(fromMobs));
        assertSame(fromMobs, ItemRefs.resolve("items_dup:x", 1).orElseThrow());
        ItemRefs.unregisterAll(items);
        assertSame(fromMobs, ItemRefs.resolve("items_dup:x", 1).orElseThrow());
        ItemRefs.unregisterAll(mobs);
        assertFalse(ItemRefs.available("items_dup"));
    }
}
