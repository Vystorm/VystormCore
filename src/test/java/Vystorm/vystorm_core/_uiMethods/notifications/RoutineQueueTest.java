package Vystorm.vystorm_core._uiMethods.notifications;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutineQueueTest {
    @Test void coalescesDuplicatesAndBoundsDistinctMessages() {
        Plugin provider = mock(Plugin.class);
        when(provider.getName()).thenReturn("Example");
        when(provider.isEnabled()).thenReturn(true);
        RoutineQueue queue = new RoutineQueue();
        queue.add(provider, Component.text("same event"));
        queue.add(provider, Component.text("same event"));
        for (int i = 0; i < RoutineQueue.MAX_DISTINCT; i++) queue.add(provider, Component.text("event-" + i));

        assertEquals(RoutineQueue.MAX_DISTINCT, queue.distinctCount());
        assertTrue(queue.encodedBytes() <= RoutineQueue.MAX_TOTAL_BYTES);
        assertEquals(1, queue.omittedCount());
        var snapshot = queue.drain();
        assertEquals(2, snapshot.messages().stream().filter(m -> m.text().equals("same event")).findFirst().orElseThrow().repeats());
        assertEquals(1, snapshot.omitted());
        assertTrue(queue.isEmpty());
    }

    @Test void truncatesAtUtf8CodePointBoundariesAndDropsDisabledProviders() {
        Plugin provider = mock(Plugin.class);
        when(provider.isEnabled()).thenReturn(true);
        RoutineQueue queue = new RoutineQueue();
        String safe = RoutineQueue.safeText(Component.text("界".repeat(600)));
        assertTrue(safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= RoutineQueue.MAX_MESSAGE_BYTES);
        queue.add(provider, Component.text("pending"));
        when(provider.isEnabled()).thenReturn(false);
        queue.removeDisabledProviders();
        assertTrue(queue.isEmpty());
    }
}
