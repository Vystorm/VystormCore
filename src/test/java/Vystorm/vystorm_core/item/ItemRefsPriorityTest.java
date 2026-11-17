package Vystorm.vystorm_core.item;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Regression "Vystorm vor Mythic": Reihenfolge der Provider im selben Namespace. */
class ItemRefsPriorityTest {
    private final Plugin items = plugin("VystormItems");
    private final Plugin mobs = plugin("VystormMobs");
    private final ItemStack fromVystorm = stack(), fromBridge = stack();

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private static ItemStack stack() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    private static ItemRefResolver knows(String known, ItemStack result) {
        return (id, amount) -> id.equals(known) ? Optional.of(result) : Optional.empty();
    }

    @AfterEach
    void cleanup() {
        ItemRefs.unregisterAll(items);
        ItemRefs.unregisterAll(mobs);
        ItemServices.unregister(items);
    }

    @Test
    void givenVystormAndBridge_whenVystormKnowsId_thenVystormWinsRegardlessOfLoadOrder() {
        ItemRefs.register(items, "t_mythic", ItemRefs.PRIORITY_VYSTORM, knows("MM_Sword", fromVystorm));
        ItemRefs.register(mobs, "t_mythic", ItemRefs.PRIORITY_BRIDGE, knows("MM_Sword", fromBridge));
        assertSame(fromVystorm, ItemRefs.resolve("t_mythic:MM_Sword", 1).orElseThrow());
        assertEquals(List.of("VystormItems", "VystormMobs"), ItemRefs.providers("t_mythic"));
    }

    @Test
    void givenVystormDoesNotKnowId_whenResolved_thenBridgeIsFallback() {
        ItemRefs.register(mobs, "t_mythic", ItemRefs.PRIORITY_BRIDGE, knows("MM_Old", fromBridge));
        ItemRefs.register(items, "t_mythic", ItemRefs.PRIORITY_VYSTORM, knows("MM_Sword", fromVystorm));
        assertSame(fromBridge, ItemRefs.resolve("t_mythic:MM_Old", 1).orElseThrow());
        assertTrue(ItemRefs.resolve("t_mythic:MM_Unknown", 1).isEmpty(), "niemand kennt die ID");
    }

    @Test
    void givenVystormInactive_whenResolved_thenBridgeIsFallback() {
        ItemRefs.register(items, "t_mythic", ItemRefs.PRIORITY_VYSTORM, knows("MM_Sword", fromVystorm));
        ItemRefs.register(mobs, "t_mythic", ItemRefs.PRIORITY_BRIDGE, knows("MM_Sword", fromBridge));
        when(items.isEnabled()).thenReturn(false);
        assertSame(fromBridge, ItemRefs.resolve("t_mythic:MM_Sword", 1).orElseThrow());
        assertEquals(List.of("VystormMobs"), ItemRefs.providers("t_mythic"));
        ItemRefs.unregisterAll(mobs);
        assertTrue(ItemRefs.resolve("t_mythic:MM_Sword", 1).isEmpty());
    }

    @Test
    void givenFailingVystormProvider_whenResolved_thenNextProviderIsAsked() {
        ItemRefs.register(items, "t_fail", ItemRefs.PRIORITY_VYSTORM, (id, amount) -> { throw new IllegalStateException("boom"); });
        ItemRefs.register(mobs, "t_fail", ItemRefs.PRIORITY_BRIDGE, knows("x", fromBridge));
        assertSame(fromBridge, ItemRefs.resolve("t_fail:x", 1).orElseThrow());
    }

    @Test
    void givenLegacyRegistrationsWithSamePriority_whenItemEngineRegisteredFirst_thenItemEngineStillWins() {
        ItemServices.register(items, mock(ItemProvider.class));
        ItemRefs.register(items, "t_legacy", knows("A", fromVystorm));
        ItemRefs.register(mobs, "t_legacy", knows("A", fromBridge));
        assertSame(fromVystorm, ItemRefs.resolve("t_legacy:A", 1).orElseThrow());
    }

    @Test
    void givenSameOwnerRegistersAgain_whenResolved_thenOnlyNewEntryCounts() {
        AtomicInteger calls = new AtomicInteger();
        ItemRefs.register(items, "t_again", ItemRefs.PRIORITY_VYSTORM, (id, amount) -> { calls.incrementAndGet(); return Optional.empty(); });
        ItemRefs.register(items, "t_again", ItemRefs.PRIORITY_VYSTORM, knows("x", fromVystorm));
        assertSame(fromVystorm, ItemRefs.resolve("t_again:x", 1).orElseThrow());
        assertEquals(0, calls.get());
        assertEquals(List.of("VystormItems"), ItemRefs.providers("t_again"));
    }

    @Test
    void givenNoServer_whenBuiltinMythicFallbackAsked_thenEmptyWithoutException() {
        assertTrue(ItemRefs.resolve("mythic:MM_Nothing", 1).isEmpty());
        assertTrue(ItemRefs.resolve("mmoitems:SWORD:X", 1).isEmpty());
        assertFalse(ItemRefs.available("mythic"));
    }
}
