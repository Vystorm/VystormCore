package Vystorm.vystorm_core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.blocks.internal.SharedStateTable;
import Vystorm.vystorm_core.main;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class BlocksEditedEventTest {
    private final StateTable states = new SharedStateTable(TestBlocks::data, TestBlocks::solid);
    private final int stone = states.id(TestBlocks.data(Material.STONE));
    private final int dirt = states.id(TestBlocks.data(Material.DIRT));
    private final World world = mock(World.class);
    private final Plugin source = mock(Plugin.class);
    private final List<BlocksEditedEvent> received = new ArrayList<>();
    private RegisteredListener registered;

    BlocksEditedEventTest() {
        when(world.getName()).thenReturn("w");
        when(source.getName()).thenReturn("EditPlugin");
        when(source.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void unregister() { if (registered != null) BlocksEditedEvent.getHandlerList().unregister(registered); }

    private void listen() {
        Listener listener = new Listener() {};
        EventExecutor executor = (l, event) -> { if (event instanceof BlocksEditedEvent edited) received.add(edited); };
        registered = new RegisteredListener(listener, executor, EventPriority.MONITOR, source, false);
        BlocksEditedEvent.getHandlerList().register(registered);
    }

    /** A plugin manager that hands an event to the listeners of its handler list, as the server does. */
    private static PluginManager dispatching() {
        PluginManager manager = mock(PluginManager.class);
        org.mockito.Mockito.doAnswer(call -> {
            Event event = call.getArgument(0);
            for (RegisteredListener l : event.getHandlers().getRegisteredListeners()) l.callEvent(event);
            return null;
        }).when(manager).callEvent(any(Event.class));
        return manager;
    }

    private EditedChunk chunk(UUID editId) {
        return EditedChunk.builder(editId, null, EditCause.EDIT, world, 3, -2).states(states)
                .add(48, 64, -32, StateTable.AIR, stone)
                .add(49, 70, -31, stone, dirt)
                .build();
    }

    @Test
    void givenListener_whenAnnouncedOnTheServerThread_thenItReceivesOneEventWithTheCells() {
        listen();
        UUID editId = UUID.randomUUID();
        EditedChunk chunk = chunk(editId);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            PluginManager manager = dispatching();
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            BlocksEditedEvent event = BlockWrites.announce(source, chunk);
            assertEquals(1, received.size());
            assertSame(event, received.get(0));
        }
        BlocksEditedEvent event = received.get(0);
        assertSame(source, event.source());
        EditedChunk got = event.chunk();
        assertEquals(editId, got.editId());
        assertNull(got.actor());
        assertEquals(EditCause.EDIT, got.cause());
        assertEquals(3, got.chunkX());
        assertEquals(-2, got.chunkZ());
        assertEquals(2, got.size());
        assertEquals(48, got.x(0)); assertEquals(64, got.y(0)); assertEquals(-32, got.z(0));
        assertEquals(49, got.x(1)); assertEquals(70, got.y(1)); assertEquals(-31, got.z(1));
        assertEquals(StateTable.AIR, got.beforeId(0)); assertEquals(stone, got.afterId(0));
        assertEquals(stone, got.beforeId(1)); assertEquals(dirt, got.afterId(1));
        assertTrue(got.touches(70, 80));
        assertFalse(got.touches(65, 69));
        assertFalse(event.isAsynchronous());
    }

    @Test
    void givenEditedChunk_whenBeforeAsked_thenEachCallGivesAFreshCopy() {
        EditedChunk chunk = chunk(UUID.randomUUID());
        BlockData first = chunk.before(1);
        BlockData second = chunk.before(1);
        assertNotSame(first, second);
        assertNotSame(states.data(stone), first);
        assertEquals("minecraft:stone", first.getAsString());
        assertEquals("minecraft:dirt", chunk.after(1).getAsString());
    }

    @Test
    void givenAnnounceOffTheServerThread_whenCalled_thenTheEventIsFiredFromATaskOnTheServerThread() {
        listen();
        Plugin core = mock(Plugin.class);
        when(core.isEnabled()).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<main> corePlugin = mockStatic(main.class)) {
            corePlugin.when(main::getPlugin).thenReturn(core);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            PluginManager manager = dispatching();
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            BlocksEditedEvent event = BlockWrites.announce(source, chunk(UUID.randomUUID()));
            verify(manager, never()).callEvent(any());
            assertTrue(received.isEmpty(), "nothing fired off the server thread");
            ArgumentCaptor<Runnable> hop = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTask(eq(core), hop.capture());
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            hop.getValue().run();
            assertEquals(1, received.size());
            assertSame(event, received.get(0));
        }
    }

    @Test
    void givenEvent_whenInspected_thenItIsNotCancellable() {
        assertFalse(Cancellable.class.isAssignableFrom(BlocksEditedEvent.class));
        assertSame(BlocksEditedEvent.getHandlerList(), new BlocksEditedEvent(source, chunk(UUID.randomUUID())).getHandlers());
    }

    @Test
    void givenBuilder_whenACellOutsideTheChunkIsAdded_thenItIsRefused() {
        EditedChunk.Builder builder = EditedChunk.builder(UUID.randomUUID(), UUID.randomUUID(), EditCause.UNDO, world, 0, 0).states(states);
        assertThrows(IllegalArgumentException.class, () -> builder.add(16, 0, 0, 0, stone));
        for (int i = 0; i < 200; i++) builder.add(i & 15, i, 0, 0, stone);
        EditedChunk built = builder.build();
        assertEquals(200, built.size());
        builder.clear().add(1, 1, 1, 0, dirt);
        assertEquals(200, built.size(), "a built chunk does not change with the builder");
        assertEquals(1, builder.build().size());
    }
}
