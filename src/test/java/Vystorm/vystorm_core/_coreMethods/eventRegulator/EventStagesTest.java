package Vystorm.vystorm_core._coreMethods.eventRegulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.lifecycle.CoreLifecycle;
import Vystorm.vystorm_core.main;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class EventStagesTest {
    /** A cancellable event with its own handler list. */
    static class Dig extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancel) { cancelled = cancel; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** Shares the handler list of {@link Dig}, like EntityDamageByEntityEvent and EntityDamageEvent. */
    static class DeepDig extends Dig {}

    /** Cannot be cancelled. */
    static class Notice extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** No handler list of its own. */
    abstract static class Vague extends Event {}

    private final Map<Class<? extends Event>, Map<EventPriority, Consumer<Event>>> bound = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private int bindings;
    private EventStages stages;

    @BeforeEach
    void setUp() {
        stages = new EventStages((root, priority, dispatcher) -> {
            bindings++;
            bound.computeIfAbsent(root, ignored -> new EnumMap<>(EventPriority.class)).put(priority, dispatcher);
        });
    }

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    /** Fires the event the way Bukkit does: Core's listeners from LOWEST to MONITOR. */
    private <E extends Event> E fire(E event) {
        Map<EventPriority, Consumer<Event>> listeners = bound.getOrDefault(EventStages.root(event.getClass()), Map.of());
        for (EventPriority priority : EventPriority.values()) {
            Consumer<Event> listener = listeners.get(priority);
            if (listener != null) listener.accept(event);
        }
        return event;
    }

    private Consumer<Event> note(String name) { return event -> calls.add(name); }

    @Test
    void givenHandlersInSeveralStages_whenEventFires_thenStagesRunInTheirFixedOrder() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, note("observe"));
        stages.handle(plugin, Dig.class, EventStage.ACT, note("act"));
        stages.handle(plugin, Dig.class, EventStage.PROTECT, note("protect"));
        stages.handle(plugin, Dig.class, EventStage.FINISH, note("finish"));
        stages.handle(plugin, Dig.class, EventStage.PREPARE, note("prepare"));
        stages.handle(plugin, Dig.class, EventStage.ADJUST, note("adjust"));

        fire(new Dig());

        assertEquals(List.of("prepare", "protect", "adjust", "act", "finish", "observe"), calls);
    }

    @Test
    void givenStages_whenMappedToBukkit_thenEachHasItsOwnPriorityInRisingOrder() {
        EventPriority[] priorities = EventPriority.values();
        EventStage[] all = EventStage.values();
        assertEquals(priorities.length, all.length);
        for (int i = 0; i < all.length; i++) assertSame(priorities[i], all[i].bukkit());
    }

    @Test
    void givenNoConfiguredOrder_whenSeveralPluginsShareAStage_thenTheyRunByNameNotByRegistration() {
        stages.handle(plugin("Zeta"), Dig.class, EventStage.PROTECT, note("Zeta"));
        stages.handle(plugin("alpha"), Dig.class, EventStage.PROTECT, note("alpha"));
        stages.handle(plugin("Mid"), Dig.class, EventStage.PROTECT, note("Mid"));

        fire(new Dig());

        assertEquals(List.of("alpha", "Mid", "Zeta"), calls);
    }

    @Test
    void givenConfiguredOrder_whenEventFires_thenListedPluginsRunFirstInListOrderThenTheRestByName() {
        stages.handle(plugin("Alpha"), Dig.class, EventStage.ACT, note("Alpha"));
        stages.handle(plugin("Beta"), Dig.class, EventStage.ACT, note("Beta"));
        stages.handle(plugin("Locks"), Dig.class, EventStage.ACT, note("Locks"));
        stages.handle(plugin("Estates"), Dig.class, EventStage.ACT, note("Estates"));

        stages.order(List.of("locks", "ESTATES"));
        fire(new Dig());

        assertEquals(List.of("Locks", "Estates", "Alpha", "Beta"), calls);
        assertEquals(List.of("locks", "estates"), stages.order(Dig.class));
    }

    @Test
    void givenOrderForOneEvent_whenEventFires_thenItReplacesTheGeneralOrderOnlyThere() {
        Plugin alpha = plugin("Alpha"), beta = plugin("Beta");
        stages.handle(alpha, Dig.class, EventStage.ACT, note("dig Alpha"));
        stages.handle(beta, Dig.class, EventStage.ACT, note("dig Beta"));
        stages.handle(alpha, Notice.class, EventStage.ACT, note("notice Alpha"));
        stages.handle(beta, Notice.class, EventStage.ACT, note("notice Beta"));
        stages.order(List.of("Alpha", "Beta"));

        stages.order("Dig", List.of("Beta"));
        fire(new Dig());
        fire(new Notice());

        assertEquals(List.of("dig Beta", "dig Alpha", "notice Alpha", "notice Beta"), calls);

        calls.clear();
        stages.order("Dig", List.of());
        fire(new Dig());
        assertEquals(List.of("dig Alpha", "dig Beta"), calls);
    }

    @Test
    void givenConfigSection_whenConfigured_thenGeneralAndPerEventOrderApplyAndNullClearsThem() {
        stages.handle(plugin("Alpha"), Dig.class, EventStage.ACT, note("Alpha"));
        stages.handle(plugin("Beta"), Dig.class, EventStage.ACT, note("Beta"));
        stages.handle(plugin("Gamma"), Dig.class, EventStage.ACT, note("Gamma"));
        YamlConfiguration config = new YamlConfiguration();
        config.set("event-order.plugins", List.of("Gamma"));
        config.set("event-order.events.Dig", List.of("Beta", "Gamma"));

        stages.configure(config.getConfigurationSection("event-order"));
        fire(new Dig());
        assertEquals(List.of("Beta", "Gamma", "Alpha"), calls);
        assertEquals(List.of("gamma"), stages.order(Notice.class));

        calls.clear();
        stages.configure(null);
        fire(new Dig());
        assertEquals(List.of("Alpha", "Beta", "Gamma"), calls);
    }

    @Test
    void givenSeveralHandlersOfOnePlugin_whenEventFires_thenTheyRunInRegistrationOrder() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.ACT, note("first"));
        stages.handle(plugin, Dig.class, EventStage.ACT, note("second"));
        stages.handle(plugin, Dig.class, EventStage.ACT, note("third"));

        fire(new Dig());

        assertEquals(List.of("first", "second", "third"), calls);
    }

    @Test
    void givenProtectionCancels_whenEventFires_thenLaterHandlersAreSkippedExceptThoseThatAskForCancelledEvents() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.PREPARE, note("prepare"));
        stages.handle(plugin, Dig.class, EventStage.PROTECT, event -> { calls.add("protect"); event.setCancelled(true); });
        stages.handle(plugin, Dig.class, EventStage.ACT, note("act"));
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, note("observe"));
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, true, note("observe cancelled too"));

        Dig event = fire(new Dig());

        assertEquals(List.of("prepare", "protect", "observe cancelled too"), calls);
        assertTrue(event.isCancelled());
    }

    @Test
    void givenHandlerThatUncancels_whenEventFires_thenFollowingHandlersRunAgain() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.PROTECT, event -> event.setCancelled(true));
        stages.handle(plugin, Dig.class, EventStage.ADJUST, true, event -> { calls.add("override"); event.setCancelled(false); });
        stages.handle(plugin, Dig.class, EventStage.ACT, note("act"));

        Dig event = fire(new Dig());

        assertEquals(List.of("override", "act"), calls);
        assertFalse(event.isCancelled());
    }

    @Test
    void givenEventCancelledBeforeCore_whenEventFires_thenOnlyHandlersForCancelledEventsRun() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.ACT, note("act"));
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, true, note("observe"));
        Dig event = new Dig();
        event.setCancelled(true);

        fire(event);

        assertEquals(List.of("observe"), calls);
    }

    @Test
    void givenObserveHandlerThatChangesTheCancelledState_whenEventFires_thenCoreRestoresIt() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, event -> event.setCancelled(true));
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, note("still runs"));

        Dig event = fire(new Dig());

        assertFalse(event.isCancelled());
        assertEquals(List.of("still runs"), calls);

        Dig cancelled = new Dig();
        cancelled.setCancelled(true);
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, true, e -> e.setCancelled(false));
        fire(cancelled);
        assertTrue(cancelled.isCancelled());
    }

    @Test
    void givenEventThatCannotBeCancelled_whenEventFires_thenEveryHandlerRuns() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Notice.class, EventStage.PROTECT, note("protect"));
        stages.handle(plugin, Notice.class, EventStage.OBSERVE, note("observe"));

        fire(new Notice());

        assertEquals(List.of("protect", "observe"), calls);
    }

    @Test
    void givenHandlerThatThrows_whenEventFires_thenTheOthersStillRun() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.PROTECT, event -> { throw new IllegalStateException("broken handler"); });
        stages.handle(plugin, Dig.class, EventStage.PROTECT, event -> { throw new NoClassDefFoundError("missing/Type"); });
        stages.handle(plugin, Dig.class, EventStage.PROTECT, note("same stage"));
        stages.handle(plugin, Dig.class, EventStage.ACT, note("later stage"));

        Dig event = fire(new Dig());

        assertEquals(List.of("same stage", "later stage"), calls);
        assertFalse(event.isCancelled());
    }

    @Test
    void givenDisabledPlugin_whenEventFires_thenItsHandlersAreSkippedAndRegisteringIsRejected() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.ACT, note("act"));
        when(plugin.isEnabled()).thenReturn(false);

        fire(new Dig());

        assertTrue(calls.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> stages.handle(plugin, Dig.class, EventStage.ACT, note("late")));
    }

    @Test
    void givenHandlersOfTwoPlugins_whenOnePluginIsRemoved_thenOnlyItsHandlersGo() {
        Plugin alpha = plugin("Alpha"), beta = plugin("Beta");
        stages.handle(alpha, Dig.class, EventStage.ACT, note("Alpha dig"));
        stages.handle(alpha, Notice.class, EventStage.ACT, note("Alpha notice"));
        stages.handle(beta, Dig.class, EventStage.ACT, note("Beta dig"));

        assertEquals(2, stages.removeAll(alpha));
        assertEquals(0, stages.removeAll(alpha));
        assertEquals(0, stages.removeAll(null));
        fire(new Dig());
        fire(new Notice());

        assertEquals(List.of("Beta dig"), calls);
        assertEquals(1, stages.handlers(Dig.class).size());
        assertTrue(stages.handlers(Notice.class).isEmpty());
    }

    @Test
    void givenRegistration_whenRemoved_thenTheHandlerIsNotCalledAndASecondRemoveSaysFalse() {
        Plugin plugin = plugin("Alpha");
        EventStages.Registration registration = stages.handle(plugin, Dig.class, EventStage.ACT, note("act"));
        assertSame(plugin, registration.owner());
        assertSame(Dig.class, registration.type());
        assertSame(EventStage.ACT, registration.stage());
        assertFalse(registration.receivesCancelled());

        assertTrue(stages.remove(registration));
        assertFalse(stages.remove(registration));
        assertFalse(stages.remove(null));
        fire(new Dig());

        assertTrue(calls.isEmpty());
    }

    @Test
    void givenHandlersForParentAndSubclass_whenEventsFire_thenBothShareOneOrderAndTheSubclassHandlerSeesOnlyItsEvents() {
        stages.handle(plugin("Beta"), Dig.class, EventStage.ACT, note("Beta any dig"));
        stages.handle(plugin("Alpha"), DeepDig.class, EventStage.ACT, note("Alpha deep dig"));

        fire(new DeepDig());
        assertEquals(List.of("Alpha deep dig", "Beta any dig"), calls);
        assertEquals(2, stages.handlers(DeepDig.class).size());

        calls.clear();
        fire(new Dig());
        assertEquals(List.of("Beta any dig"), calls);
        assertEquals(1, stages.handlers(Dig.class).size());
        assertSame(Dig.class, EventStages.root(DeepDig.class));
    }

    @Test
    void givenEventClassWithoutHandlerList_whenRegistered_thenItIsRejected() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> stages.handle(plugin("Alpha"), Vague.class, EventStage.ACT, event -> {}));
        assertTrue(rejected.getMessage().contains("no handler list"));
    }

    @Test
    void givenManyHandlers_whenRegistered_thenCoreBindsOneListenerPerEventAndStageInUse() {
        Plugin alpha = plugin("Alpha"), beta = plugin("Beta");
        stages.handle(alpha, Dig.class, EventStage.ACT, note("a"));
        stages.handle(beta, Dig.class, EventStage.ACT, note("b"));
        stages.handle(beta, DeepDig.class, EventStage.ACT, note("c"));
        stages.handle(alpha, Dig.class, EventStage.OBSERVE, note("d"));
        stages.handle(alpha, Notice.class, EventStage.ACT, note("e"));

        assertEquals(3, bindings);
        assertEquals(List.of(EventPriority.HIGH, EventPriority.MONITOR), List.copyOf(bound.get(Dig.class).keySet()));
    }

    @Test
    void givenNoHandlerForAnEvent_whenNothingRegistered_thenCoreBindsNoListener() {
        assertEquals(0, bindings);
        assertTrue(stages.handlers(Dig.class).isEmpty());
        stages.dispatch(Dig.class, EventPriority.NORMAL, new Dig());
        assertTrue(calls.isEmpty());
    }

    @Test
    void givenCleared_whenHandlerRegisteredAgain_thenTheListenerIsBoundAgain() {
        Plugin plugin = plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.ACT, note("before"));
        stages.clear();
        assertTrue(stages.handlers(Dig.class).isEmpty());

        stages.handle(plugin, Dig.class, EventStage.ACT, note("after"));

        assertEquals(2, bindings);
        fire(new Dig());
        assertEquals(List.of("after"), calls);
    }

    @Test
    void givenSharedRegistry_whenUsedThroughBukkit_thenCoreRegistersItsOwnListenerAndLifecycleRemovesThePlugin() throws Exception {
        Plugin core = plugin("vystorm_core"), consumer = plugin("Consumer");
        PluginManager manager = mock(PluginManager.class);
        EventStages shared = EventStages.shared();
        shared.clear();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<main> entry = mockStatic(main.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            entry.when(main::getPlugin).thenReturn(core);

            shared.handle(consumer, Dig.class, EventStage.PROTECT, event -> { calls.add("protect"); event.setCancelled(true); });

            ArgumentCaptor<EventExecutor> executor = ArgumentCaptor.forClass(EventExecutor.class);
            verify(manager).registerEvent(eq(Dig.class), any(Listener.class), eq(EventPriority.LOW), executor.capture(), eq(core), eq(false));
            Dig event = new Dig();
            executor.getValue().execute(mock(Listener.class), event);
            executor.getValue().execute(mock(Listener.class), new Notice());
            assertEquals(List.of("protect"), calls);
            assertTrue(event.isCancelled());

            CoreLifecycle.releaseAll(consumer);
            assertTrue(shared.handlers(Dig.class).isEmpty());
            executor.getValue().execute(mock(Listener.class), new Dig());
            assertEquals(List.of("protect"), calls);
        } finally {
            shared.clear();
        }
    }
}
