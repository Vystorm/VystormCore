package Vystorm.vystorm_core._coreMethods.eventRegulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.API;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The public test support runs without any static mock: nothing here touches Bukkit or Core's plugin instance. */
class EventStageTestingTest {
    /** A cancellable event with its own handler list. */
    static class Dig extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancel) { cancelled = cancel; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** Shares the handler list of {@link Dig}. */
    static class DeepDig extends Dig {}

    /** Another handler list. */
    static class Notice extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** No handler list of its own. */
    static class Vague extends Event {
        @Override public HandlerList getHandlers() { return null; }
    }

    private final EventStageTesting stages = EventStageTesting.detached();
    private final List<String> calls = new ArrayList<>();

    private <E extends Event> Consumer<E> note(String name) { return event -> calls.add(name); }

    @Test
    void givenHandlersInSeveralStages_whenEventFires_thenTheyRunInStageOrderWhateverTheRegistrationOrder() {
        Plugin plugin = EventStageTesting.plugin("Alpha");
        EventStage[] all = EventStage.values();
        for (int i = all.length - 1; i >= 0; i--) stages.handle(plugin, Dig.class, all[i], note(all[i].name()));

        Dig event = new Dig();

        assertSame(event, stages.fire(event));
        assertEquals(List.of("PREPARE", "PROTECT", "ADJUST", "ACT", "FINISH", "OBSERVE"), calls);
    }

    @Test
    void givenSeveralPluginsInOneStage_whenEventFires_thenListedPluginsRunFirstThenByNameThenByRegistration() {
        Plugin alpha = EventStageTesting.plugin("Alpha"), beta = EventStageTesting.plugin("beta"), gamma = EventStageTesting.plugin("Gamma");
        stages.handle(gamma, Dig.class, EventStage.ACT, note("Gamma 1"));
        stages.handle(beta, Dig.class, EventStage.ACT, note("beta"));
        stages.handle(alpha, Dig.class, EventStage.ACT, note("Alpha"));
        stages.handle(gamma, Dig.class, EventStage.ACT, note("Gamma 2"));

        stages.fire(new Dig());
        assertEquals(List.of("Alpha", "beta", "Gamma 1", "Gamma 2"), calls);

        calls.clear();
        assertSame(stages, stages.order(List.of("GAMMA")));
        stages.fire(new Dig());
        assertEquals(List.of("Gamma 1", "Gamma 2", "Alpha", "beta"), calls);

        calls.clear();
        stages.order("Dig", List.of("beta", "Alpha"));
        stages.fire(new DeepDig());
        assertEquals(List.of("beta", "Alpha", "Gamma 1", "Gamma 2"), calls);
        assertEquals(List.of("beta", "alpha"), stages.stages().order(DeepDig.class));
        assertEquals(4, stages.handlers(Dig.class).size());
    }

    @Test
    void givenCancelledEvent_whenEventFires_thenOnlyHandlersThatAskedForCancelledEventsRun() {
        Plugin plugin = EventStageTesting.plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.PROTECT, event -> event.setCancelled(true));
        stages.handle(plugin, Dig.class, EventStage.ACT, note("act"));
        stages.handle(plugin, Dig.class, EventStage.ACT, true, note("act, also cancelled"));
        stages.handle(plugin, Dig.class, EventStage.FINISH, true, event -> event.setCancelled(false));
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, note("observe"));

        Dig event = stages.fire(new Dig());

        assertFalse(event.isCancelled());
        assertEquals(List.of("act, also cancelled", "observe"), calls);
    }

    @Test
    void givenObserveHandlerThatChangesTheCancelledState_whenEventFires_thenCoreRestoresIt() {
        Plugin plugin = EventStageTesting.plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, event -> event.setCancelled(true));
        stages.handle(plugin, Dig.class, EventStage.OBSERVE, note("next observer"));
        assertFalse(stages.fire(new Dig()).isCancelled());
        assertEquals(List.of("next observer"), calls);

        EventStageTesting other = EventStageTesting.detached();
        other.handle(plugin, Dig.class, EventStage.OBSERVE, true, event -> event.setCancelled(false));
        Dig cancelled = new Dig();
        cancelled.setCancelled(true);
        assertTrue(other.fire(cancelled).isCancelled());
    }

    @Test
    void givenHandlerThatThrows_whenEventFires_thenTheOthersStillRunAndTheTestSeesNoException() {
        Plugin plugin = EventStageTesting.plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.PROTECT, event -> { throw new IllegalStateException("broken handler"); });
        stages.handle(plugin, Dig.class, EventStage.PROTECT, note("same stage"));
        stages.handle(plugin, Dig.class, EventStage.ACT, note("later stage"));

        Dig event = stages.fire(new Dig());

        assertEquals(List.of("same stage", "later stage"), calls);
        assertFalse(event.isCancelled());
    }

    @Test
    void givenHandlersOfTwoPlugins_whenOneIsUnregistered_thenOnlyTheOtherIsCalled() {
        Plugin alpha = EventStageTesting.plugin("Alpha"), beta = EventStageTesting.plugin("Beta");
        stages.handle(alpha, Dig.class, EventStage.ACT, note("Alpha dig"));
        stages.handle(alpha, Notice.class, EventStage.ACT, note("Alpha notice"));
        stages.handle(beta, Dig.class, EventStage.ACT, note("Beta dig"));
        EventStages.Registration single = stages.handle(beta, Dig.class, EventStage.OBSERVE, note("Beta observe"));

        assertEquals(2, stages.removeAll(alpha));
        assertTrue(stages.remove(single));
        assertFalse(stages.remove(single));
        stages.fire(new Dig());
        stages.fire(new Notice());

        assertEquals(List.of("Beta dig"), calls);
    }

    @Test
    void givenHandlerForABaseEvent_whenASubclassOrAnotherEventFires_thenOnlyMatchingHandlersRun() {
        Plugin plugin = EventStageTesting.plugin("Alpha");
        stages.handle(plugin, Dig.class, EventStage.ACT, note("dig"));
        stages.handle(plugin, DeepDig.class, EventStage.ACT, note("deep dig"));

        stages.fire(new Dig());
        stages.fire(new Notice());
        assertEquals(List.of("dig"), calls);

        stages.fire(new DeepDig());
        assertEquals(List.of("dig", "dig", "deep dig"), calls);
        assertThrows(IllegalArgumentException.class, () -> stages.fire(new Vague()));
    }

    @Test
    void givenTwoDetachedRegistries_whenUsed_thenTheyShareNothingWithEachOtherOrTheSharedRegistry() {
        Plugin plugin = EventStageTesting.plugin("Alpha");
        EventStageTesting other = EventStageTesting.detached();
        stages.handle(plugin, Dig.class, EventStage.ACT, note("first"));
        stages.order(List.of("Alpha"));

        other.fire(new Dig());

        assertTrue(calls.isEmpty());
        assertNotSame(stages.stages(), other.stages());
        assertNotSame(EventStages.shared(), stages.stages());
        assertTrue(other.handlers(Dig.class).isEmpty());
        assertTrue(other.stages().order(Dig.class).isEmpty());
        assertTrue(EventStages.shared().handlers(Dig.class).isEmpty());
    }

    @Test
    void givenStubbedHub_whenThePluginAsksForTheStages_thenItRegistersOnTheDetachedRegistry() {
        API api = mock(API.class);
        when(api.priority()).thenReturn(stages.priority());
        Plugin plugin = EventStageTesting.plugin("Alpha");

        // what a plugin does in onEnable
        api.priority().stages().handle(plugin, Dig.class, EventStage.PROTECT, event -> event.setCancelled(true));

        assertSame(stages.stages(), api.priority().stages());
        assertTrue(stages.fire(new Dig()).isCancelled());
        // the legacy NBT actions of the stub do nothing
        api.priority().run(new ArrayList<>(), (org.bukkit.entity.Player) null);
        api.priority().blockBreakEvent(null);
    }

    @Test
    void givenTestPlugin_whenAsked_thenItIsEnabledNamedAndUsableAsAKey() {
        Plugin alpha = EventStageTesting.plugin("Alpha"), twin = EventStageTesting.plugin("Alpha");

        assertEquals("Alpha", alpha.getName());
        assertTrue(alpha.isEnabled());
        assertEquals(alpha, alpha);
        assertNotEquals(alpha, twin);
        assertEquals(alpha.hashCode(), alpha.hashCode());
        assertTrue(alpha.toString().contains("Alpha"));
        assertFalse(alpha.isNaggable());
        assertEquals(null, alpha.getServer());
        assertThrows(NullPointerException.class, () -> EventStageTesting.plugin(null));
    }
}
