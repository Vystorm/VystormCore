package Vystorm.vystorm_core._coreMethods.eventRegulator;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * One place that decides in which order Vystorm plugins handle a shared Bukkit event, reached through
 * {@code api.priority().stages()}.
 * <p>
 * A plugin registers a handler with a named {@link EventStage} instead of writing its own
 * {@code @EventHandler(priority = ...)}. Core owns the Bukkit listener (one per event and stage in use, registered
 * when the first handler arrives) and calls the handlers in this order:
 * <ol>
 *   <li>by stage, in the order of {@link EventStage};</li>
 *   <li>inside a stage by the configured plugin order: the list for that event if there is one, otherwise the
 *       general list ({@code event-order} in Core's {@code config.yml}, or {@link #order(List)}); plugins on the list
 *       run in list order and before plugins that are not on it;</li>
 *   <li>plugins that are not listed run by plugin name (case-insensitive), so the order does not depend on the load
 *       order of the server;</li>
 *   <li>several handlers of one plugin run in the order they were registered.</li>
 * </ol>
 *
 * <h2>Cancelling</h2>
 * A handler cancels with {@code event.setCancelled(true)} as usual. A handler registered with
 * {@link #handle(Plugin, Class, EventStage, Consumer)} is skipped while the event is cancelled, like
 * {@code ignoreCancelled = true}; use {@link #handle(Plugin, Class, EventStage, boolean, Consumer)} with
 * {@code receiveCancelled = true} to see cancelled events too. The state is checked before every single handler, so
 * a handler that un-cancels makes the following ones run again. Handlers of {@link EventStage#OBSERVE} cannot change
 * the cancelled state: Core puts it back and reports the handler. Events that cannot be cancelled reach every handler.
 *
 * <h2>Failures and lifecycle</h2>
 * An exception in one handler is reported through {@link ProviderFailures} and does not stop the others. Handlers
 * of a disabled plugin are skipped and removed when the plugin is disabled. Handlers run on the thread that fires
 * the event (asynchronous events on their own thread); registering and removing is allowed from any thread.
 * <p>
 * A handler registered for an event class also receives its subclasses (for example
 * {@code EntityDamageByEntityEvent} for {@code EntityDamageEvent}). Event classes that share a Bukkit handler list are
 * ordered together. Abstract event classes without a handler list (such as {@code PlayerEvent}) are rejected.
 * <p>
 * Nothing changes for an event until a plugin registers a handler for it here. The NBT actions of
 * {@link PriorityInterface} keep running from Core's own listeners at Bukkit priority NORMAL, before the handlers
 * of {@link EventStage#ADJUST}.
 *
 * @since 0.64.0
 */
public final class EventStages {
    /** A registered handler. Pass it to {@link #remove(Registration)} to remove it. */
    public interface Registration {
        Plugin owner();
        Class<? extends Event> type();
        EventStage stage();
        /** Whether the handler is also called for cancelled events. */
        boolean receivesCancelled();
    }

    /** Connects one handler list and Bukkit priority to a dispatcher. The default registers a Bukkit listener. */
    interface Binder {
        void bind(Class<? extends Event> root, EventPriority priority, Consumer<Event> dispatcher);
    }

    private static final class Entry implements Registration {
        final long sequence;
        final Plugin owner;
        final String ownerName;
        final Class<? extends Event> type;
        final EventStage stage;
        final boolean receivesCancelled;
        final Consumer<Event> handler;

        Entry(long sequence, Plugin owner, Class<? extends Event> type, EventStage stage, boolean receivesCancelled, Consumer<Event> handler) {
            this.sequence = sequence; this.owner = owner; this.ownerName = owner.getName(); this.type = type;
            this.stage = stage; this.receivesCancelled = receivesCancelled; this.handler = handler;
        }

        @Override public Plugin owner() { return owner; }
        @Override public Class<? extends Event> type() { return type; }
        @Override public EventStage stage() { return stage; }
        @Override public boolean receivesCancelled() { return receivesCancelled; }
        @Override public String toString() { return ownerName + ":" + type.getSimpleName() + "@" + stage; }
    }

    private record Slot(Class<? extends Event> root, EventPriority priority) {}

    private static final Listener LISTENER = new Listener() {};
    private static final EventStages SHARED = new EventStages(EventStages::bindBukkit);

    private final Binder binder;
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final Set<Slot> bound = new HashSet<>();
    /** Handlers per handler-list class, in registration order. Guarded by {@link #lock}. */
    private final Map<Class<? extends Event>, List<Entry>> entries = new HashMap<>();
    /** Immutable snapshot read by {@link #dispatch}: handlers per handler-list class in dispatch order. */
    private volatile Map<Class<? extends Event>, List<Entry>> sorted = Map.of();
    private volatile List<String> generalOrder = List.of();
    private volatile Map<String, List<String>> eventOrder = Map.of();

    EventStages(Binder binder) { this.binder = Objects.requireNonNull(binder, "binder"); }

    /** The registry behind {@code api.priority().stages()}. */
    public static EventStages shared() { return SHARED; }

    /**
     * Registers a handler that is skipped while the event is cancelled.
     *
     * @throws IllegalArgumentException if the owner is disabled or the event class has no handler list
     */
    public <E extends Event> Registration handle(Plugin owner, Class<E> type, EventStage stage, Consumer<? super E> handler) {
        return handle(owner, type, stage, false, handler);
    }

    /**
     * Registers a handler.
     *
     * @param receiveCancelled true to be called for cancelled events too
     * @throws IllegalArgumentException if the owner is disabled or the event class has no handler list
     */
    @SuppressWarnings("unchecked")
    public <E extends Event> Registration handle(Plugin owner, Class<E> type, EventStage stage, boolean receiveCancelled,
                                                 Consumer<? super E> handler) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(handler, "handler");
        if (!owner.isEnabled()) throw new IllegalArgumentException("Plugin " + owner.getName() + " is not enabled");
        Class<? extends Event> root = root(type);
        Entry entry = new Entry(sequence.incrementAndGet(), owner, type, stage, receiveCancelled, (Consumer<Event>) handler);
        synchronized (lock) {
            Slot slot = new Slot(root, stage.bukkit());
            if (!bound.contains(slot)) {
                binder.bind(root, stage.bukkit(), event -> dispatch(root, slot.priority(), event));
                bound.add(slot);
            }
            entries.computeIfAbsent(root, ignored -> new ArrayList<>()).add(entry);
            rebuild();
        }
        return entry;
    }

    /** Removes one handler; false if it was not registered (any more). */
    public boolean remove(Registration registration) {
        if (!(registration instanceof Entry entry)) return false;
        synchronized (lock) {
            Class<? extends Event> root = root(entry.type);
            List<Entry> list = entries.get(root);
            if (list == null || !list.remove(entry)) return false;
            if (list.isEmpty()) entries.remove(root);
            rebuild();
            return true;
        }
    }

    /** Removes every handler of a plugin (Core does this when the plugin is disabled); returns how many. */
    public int removeAll(Plugin owner) {
        if (owner == null) return 0;
        synchronized (lock) {
            int removed = 0;
            for (var iterator = entries.values().iterator(); iterator.hasNext(); ) {
                List<Entry> list = iterator.next();
                int before = list.size();
                list.removeIf(entry -> entry.owner == owner);
                removed += before - list.size();
                if (list.isEmpty()) iterator.remove();
            }
            if (removed > 0) rebuild();
            return removed;
        }
    }

    /** The handlers that receive an event of this class, in the order Core calls them. */
    public List<Registration> handlers(Class<? extends Event> type) {
        List<Registration> result = new ArrayList<>();
        for (Entry entry : sorted.getOrDefault(root(type), List.of())) if (entry.type.isAssignableFrom(type)) result.add(entry);
        return List.copyOf(result);
    }

    /**
     * Sets the general plugin order inside a stage: plugin names, first runs first. Names are compared without case;
     * plugins that are not listed follow by name.
     */
    public void order(List<String> pluginNames) {
        synchronized (lock) {
            generalOrder = normalize(pluginNames);
            rebuild();
        }
    }

    /**
     * Sets the plugin order for one event; it replaces the general order for that event. An empty or null list
     * removes it.
     *
     * @param event simple or full name of the class that owns the Bukkit handler list, e.g. {@code BlockBreakEvent}
     */
    public void order(String event, List<String> pluginNames) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            Map<String, List<String>> next = new HashMap<>(eventOrder);
            List<String> names = normalize(pluginNames);
            if (names.isEmpty()) next.remove(event); else next.put(event, names);
            eventOrder = Map.copyOf(next);
            rebuild();
        }
    }

    /**
     * The configured plugin order that applies to an event (lower-case names); empty if none is configured. Events
     * that share a Bukkit handler list share one order, named after the class that owns the list
     * ({@code EntityDamageEvent} also orders {@code EntityDamageByEntityEvent}).
     */
    public List<String> order(Class<? extends Event> type) {
        Class<? extends Event> root = root(type);
        Map<String, List<String>> perEvent = eventOrder;
        List<String> specific = perEvent.get(root.getName());
        if (specific == null) specific = perEvent.get(root.getSimpleName());
        return specific != null ? specific : generalOrder;
    }

    /**
     * Reads the order from Core's {@code event-order} section: {@code plugins} (list) and {@code events.<Event>}
     * (list per event). A null section clears every configured order. Core calls this on enable and on reload.
     */
    public void configure(ConfigurationSection section) {
        synchronized (lock) {
            Map<String, List<String>> perEvent = new HashMap<>();
            ConfigurationSection events = section == null ? null : section.getConfigurationSection("events");
            if (events != null) {
                for (String key : events.getKeys(false)) {
                    List<String> names = normalize(events.getStringList(key));
                    if (!names.isEmpty()) perEvent.put(key, names);
                }
            }
            generalOrder = section == null ? List.of() : normalize(section.getStringList("plugins"));
            eventOrder = Map.copyOf(perEvent);
            rebuild();
        }
    }

    /** Core shutdown: forgets every handler and listener binding (Bukkit removes Core's listeners itself). */
    public void clear() {
        synchronized (lock) {
            entries.clear();
            bound.clear();
            sorted = Map.of();
        }
    }

    /** The class that owns the Bukkit handler list of {@code type} (the class Bukkit registers listeners for). */
    static Class<? extends Event> root(Class<? extends Event> type) {
        for (Class<?> c = type; c != null && Event.class.isAssignableFrom(c); c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("getHandlerList");
                return c.asSubclass(Event.class);
            } catch (NoSuchMethodException next) {
                // look in the superclass, like Bukkit does
            }
        }
        throw new IllegalArgumentException(type.getName() + " has no handler list; register a concrete event class");
    }

    /** Calls the handlers of one handler list and Bukkit priority. Called by Core's listener. */
    void dispatch(Class<? extends Event> root, EventPriority priority, Event event) {
        List<Entry> list = sorted.get(root);
        if (list == null) return;
        Cancellable cancellable = event instanceof Cancellable c ? c : null;
        for (Entry entry : list) {
            if (entry.stage.bukkit() != priority || !entry.type.isInstance(event) || !entry.owner.isEnabled()) continue;
            boolean cancelled = cancellable != null && cancellable.isCancelled();
            if (cancelled && !entry.receivesCancelled) continue;
            String operation = entry.type.getSimpleName() + " at " + entry.stage;
            try {
                entry.handler.accept(event);
            } catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report("EventStages", entry.ownerName, operation, failure);
            }
            if (entry.stage == EventStage.OBSERVE && cancellable != null && cancellable.isCancelled() != cancelled) {
                cancellable.setCancelled(cancelled);
                ProviderFailures.report("EventStages", entry.ownerName, operation,
                        new IllegalStateException("An OBSERVE handler changed the cancelled state; Core restored it"));
            }
        }
    }

    private void rebuild() {
        Map<Class<? extends Event>, List<Entry>> next = new HashMap<>();
        for (Map.Entry<Class<? extends Event>, List<Entry>> item : entries.entrySet()) {
            List<String> names = order(item.getKey());
            List<Entry> list = new ArrayList<>(item.getValue());
            list.sort(Comparator.<Entry>comparingInt(entry -> entry.stage.ordinal())
                    .thenComparingInt(entry -> rank(names, entry.ownerName))
                    .thenComparing(entry -> entry.ownerName.toLowerCase(Locale.ROOT))
                    .thenComparingLong(entry -> entry.sequence));
            next.put(item.getKey(), List.copyOf(list));
        }
        sorted = Map.copyOf(next);
    }

    private static int rank(List<String> names, String plugin) {
        int index = names.indexOf(plugin.toLowerCase(Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static List<String> normalize(List<String> names) {
        if (names == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String key = name.trim().toLowerCase(Locale.ROOT);
            if (!result.contains(key)) result.add(key);
        }
        return List.copyOf(result);
    }

    private static void bindBukkit(Class<? extends Event> root, EventPriority priority, Consumer<Event> dispatcher) {
        Plugin core = Vystorm.vystorm_core.main.getPlugin();
        Bukkit.getPluginManager().registerEvent(root, LISTENER, priority,
                (listener, event) -> { if (root.isInstance(event)) dispatcher.accept(event); }, core, false);
    }
}
