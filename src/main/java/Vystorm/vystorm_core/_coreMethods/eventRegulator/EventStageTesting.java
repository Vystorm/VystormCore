package Vystorm.vystorm_core._coreMethods.eventRegulator;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.plugin.Plugin;

/**
 * Unit tests of staged event handlers without a server: an {@link EventStages} registry of its own that is not
 * connected to Bukkit (no listener, no static mocks, nothing shared with {@link EventStages#shared()} or with another
 * test). Handlers are registered exactly as in a plugin; {@link #fire} then calls them through Core's real dispatcher,
 * so stage order, configured plugin order, cancelling, the {@code OBSERVE} rule and failure handling are those of
 * the server.
 *
 * <pre>{@code
 * EventStageTesting stages = EventStageTesting.detached();
 * Plugin other = EventStageTesting.plugin("OtherPlugin");
 * stages.handle(other, BlockBreakEvent.class, EventStage.ACT, event -> calls.add("act"));
 * myPlugin.handleBlockEvents(stages.stages());            // the method that registers the plugin's handlers
 *
 * BlockBreakEvent event = stages.fire(new BlockBreakEvent(block, player));
 * assertTrue(event.isCancelled());
 * }</pre>
 *
 * A plugin that asks the hub itself ({@code api.priority().stages()}) gets the registry through {@link #priority()}:
 *
 * <pre>{@code
 * API api = mock(API.class);
 * when(api.priority()).thenReturn(stages.priority());
 * }</pre>
 *
 * Owners must answer {@code isEnabled() = true} and {@code getName()}; {@link #plugin(String)} makes one without a
 * mocking library. The plugin order is set with {@link #order(List)} and {@link #order(String, List)} (on a server it
 * comes from Core's {@code config.yml}). Events are fired on the calling thread. Not meant for production code.
 *
 * @since 0.64.0
 */
public final class EventStageTesting {
    private record Slot(Class<? extends Event> root, EventPriority priority) {}

    private final Map<Slot, Consumer<Event>> dispatchers = new ConcurrentHashMap<>();
    private final EventStages stages = new EventStages((root, priority, dispatcher) -> dispatchers.put(new Slot(root, priority), dispatcher));
    private final PriorityInterface priority = new DetachedPriority(stages);

    private EventStageTesting() {}

    /** A new, empty registry that is not connected to a server. */
    public static EventStageTesting detached() { return new EventStageTesting(); }

    /**
     * An enabled plugin with this name, to own handlers in a test (for example those of a second plugin). Every other
     * method of it returns null, false or 0.
     */
    public static Plugin plugin(String name) {
        Objects.requireNonNull(name, "name");
        return (Plugin) Proxy.newProxyInstance(EventStageTesting.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getName": return name;
                case "isEnabled": return true;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "TestPlugin[" + name + "]";
                default:
            }
            Class<?> type = method.getReturnType();
            if (!type.isPrimitive() || type == void.class) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            return 0;
        });
    }

    /** The registry itself, to hand to the code under test wherever it expects {@code api.priority().stages()}. */
    public EventStages stages() { return stages; }

    /**
     * A priority manager whose {@link PriorityInterface#stages()} is this registry, for a stubbed hub
     * ({@code when(api.priority()).thenReturn(stages.priority())}). Its NBT actions do nothing.
     */
    public PriorityInterface priority() { return priority; }

    /** Same as {@link EventStages#handle(Plugin, Class, EventStage, Consumer)} on {@link #stages()}. */
    public <E extends Event> EventStages.Registration handle(Plugin owner, Class<E> type, EventStage stage, Consumer<? super E> handler) {
        return stages.handle(owner, type, stage, handler);
    }

    /** Same as {@link EventStages#handle(Plugin, Class, EventStage, boolean, Consumer)} on {@link #stages()}. */
    public <E extends Event> EventStages.Registration handle(Plugin owner, Class<E> type, EventStage stage, boolean receiveCancelled,
                                                             Consumer<? super E> handler) {
        return stages.handle(owner, type, stage, receiveCancelled, handler);
    }

    /** Same as {@link EventStages#remove(EventStages.Registration)} on {@link #stages()}. */
    public boolean remove(EventStages.Registration registration) { return stages.remove(registration); }

    /** Same as {@link EventStages#removeAll(Plugin)} on {@link #stages()}: what Core does when a plugin is disabled. */
    public int removeAll(Plugin owner) { return stages.removeAll(owner); }

    /** Same as {@link EventStages#order(List)} on {@link #stages()}; returns this. */
    public EventStageTesting order(List<String> pluginNames) {
        stages.order(pluginNames);
        return this;
    }

    /** Same as {@link EventStages#order(String, List)} on {@link #stages()}; returns this. */
    public EventStageTesting order(String event, List<String> pluginNames) {
        stages.order(event, pluginNames);
        return this;
    }

    /** The handlers that receive an event of this class, in the order {@link #fire} calls them. */
    public List<EventStages.Registration> handlers(Class<? extends Event> type) { return stages.handlers(type); }

    /**
     * Calls the registered handlers for this event the way the server does: Core's dispatcher once per stage in use,
     * in the order of Bukkit's priorities (LOWEST to MONITOR). Handlers of other listeners do not exist here. An
     * exception in a handler is logged like on a server and does not reach the test.
     *
     * @return the same event, to assert on
     * @throws IllegalArgumentException if the event class has no handler list
     */
    public <E extends Event> E fire(E event) {
        Objects.requireNonNull(event, "event");
        Class<? extends Event> root = EventStages.root(event.getClass());
        List<Consumer<Event>> calls = new ArrayList<>();
        for (EventPriority bukkit : EventPriority.values()) {
            Consumer<Event> dispatcher = dispatchers.get(new Slot(root, bukkit));
            if (dispatcher != null) calls.add(dispatcher);
        }
        for (Consumer<Event> dispatcher : calls) dispatcher.accept(event);
        return event;
    }

    /** {@link PriorityInterface} with the detached registry; the legacy NBT actions are empty. */
    private static final class DetachedPriority implements PriorityInterface {
        private final EventStages stages;

        DetachedPriority(EventStages stages) { this.stages = stages; }

        @Override public EventStages stages() { return stages; }
        @Override public void blockBreakEvent(BlockBreakEvent event) {}
        @Override public void blockPlaceEvent(BlockPlaceEvent event) {}
        @Override public void entityDamageListener(EntityDamageEvent event) {}
        @Override public void entityDeathListener(EntityDeathEvent event) {}
        @Override public void itemUseEvent(PlayerInteractEvent event) {}
        @Override public void changeItemEvent(PlayerItemHeldEvent event) {}
        @Override public void itemDamageEvent(PlayerItemDamageEvent event) {}
        @Override public void interactAtEntityEvent(PlayerInteractAtEntityEvent event) {}
        @Override public void playerHarvestBlockEvent(PlayerHarvestBlockEvent event) {}
        @Override public void run(java.util.ArrayList<String> keys, Player player) {}
        @Override public void run(java.util.ArrayList<String> keys, Block block) {}
        @Override public void run(java.util.ArrayList<String> keys, Entity entity) {}
    }
}
