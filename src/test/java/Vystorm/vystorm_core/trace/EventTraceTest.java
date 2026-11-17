package Vystorm.vystorm_core.trace;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventTraceTest {
    /** A protection plugin's listener that refuses every break (like an unknown plugin on a foreign server). */
    static final class AcaciaGuard implements Listener {}

    private RegisteredListener guard;

    @AfterEach void cleanup() {
        EventTrace.stopAll();
        if (guard != null) BlockBreakEvent.getHandlerList().unregister(guard);
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        return player;
    }

    private static BlockBreakEvent breakEvent(Player player) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.ACACIA_LOG);
        when(block.getX()).thenReturn(120);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(-30);
        return new BlockBreakEvent(block, player);
    }

    private void registerGuard() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("ForeignProtect");
        when(plugin.isEnabled()).thenReturn(true);
        guard = new RegisteredListener(new AcaciaGuard(), (listener, event) -> ((BlockBreakEvent) event).setCancelled(true),
                EventPriority.NORMAL, plugin, false);
        BlockBreakEvent.getHandlerList().register(guard);
    }

    private static void fire(BlockBreakEvent event) throws Exception {
        for (RegisteredListener listener : BlockBreakEvent.getHandlerList().getRegisteredListeners()) listener.callEvent(event);
    }

    @Test
    void givenTracedPlayerWhenForeignPluginCancelsBreakThenThatPluginIsNamed() throws Exception {
        registerGuard();
        Player esoren = player("esoren");
        assertTrue(EventTrace.toggle(null, esoren));
        assertTrue(EventTrace.wrapped());
        BlockBreakEvent event = breakEvent(esoren);
        fire(event);
        assertTrue(event.isCancelled(), "the original listener still runs");
        String report = EventTrace.lastReport(esoren.getUniqueId());
        assertNotNull(report);
        assertTrue(report.contains("ForeignProtect"), report);
        assertTrue(report.contains("AcaciaGuard"), report);
        assertTrue(report.contains("120 64 -30"), report);
        assertTrue(report.endsWith("true"), report);
    }

    @Test
    void givenUntracedPlayerThenNothingIsRecordedAndEventStillCancelled() throws Exception {
        registerGuard();
        Player traced = player("traced");
        Player other = player("other");
        EventTrace.toggle(null, traced);
        BlockBreakEvent event = breakEvent(other);
        fire(event);
        assertTrue(event.isCancelled());
        assertNull(EventTrace.lastReport(other.getUniqueId()));
    }

    @Test
    void givenFilterWithoutBreakThenCancelledBreakIsNotReportedButWithBreakItIs() throws Exception {
        registerGuard();
        Player esoren = player("esoren");
        EventTrace.trace(null, esoren, java.util.EnumSet.of(EventTrace.Kind.PLACE, EventTrace.Kind.MESSAGES));
        assertEquals(java.util.Set.of(EventTrace.Kind.PLACE, EventTrace.Kind.MESSAGES), EventTrace.kinds(esoren));
        fire(breakEvent(esoren));
        assertNull(EventTrace.lastReport(esoren.getUniqueId()), "break is filtered out");
        EventTrace.trace(null, esoren, java.util.EnumSet.of(EventTrace.Kind.BREAK));
        fire(breakEvent(esoren));
        assertNotNull(EventTrace.lastReport(esoren.getUniqueId()));
        assertEquals(java.util.Optional.of(EventTrace.Kind.INTERACT), EventTrace.Kind.parse("Interact"));
        assertTrue(EventTrace.Kind.parse("explode").isEmpty());
    }

    @Test
    void givenLastTraceStoppedThenOriginalListenersAreRestored() {
        registerGuard();
        Player esoren = player("esoren");
        EventTrace.toggle(null, esoren);
        assertTrue(Arrays.stream(BlockBreakEvent.getHandlerList().getRegisteredListeners()).anyMatch(l -> l instanceof EventTrace.Tracing));
        assertFalse(EventTrace.toggle(null, esoren));
        assertFalse(EventTrace.wrapped());
        assertTrue(Arrays.stream(BlockBreakEvent.getHandlerList().getRegisteredListeners()).noneMatch(l -> l instanceof EventTrace.Tracing));
        assertTrue(Arrays.asList(BlockBreakEvent.getHandlerList().getRegisteredListeners()).contains(guard));
    }
}
