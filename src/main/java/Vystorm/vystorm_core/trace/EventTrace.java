package Vystorm.vystorm_core.trace;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /vadmin trace <player>}: names the plugin (and listener class) that cancels or allows a traced player's block
 * break, block place, block damage or interaction. Bukkit knows the owning plugin of every listener, so this works for
 * any plugin, not only Vystorm ones; nothing has to be signed.
 *
 * <p>While at least one player is traced, every listener of those events is wrapped in a {@link Tracing} listener
 * that compares the cancel state before and after it ran. With no trace active the original listeners are restored,
 * so normal play pays nothing. Server thread only.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class EventTrace {
    private static final List<HandlerList> EVENTS = List.of(BlockBreakEvent.getHandlerList(), BlockPlaceEvent.getHandlerList(),
            BlockDamageEvent.getHandlerList(), PlayerInteractEvent.getHandlerList());
    private static final Map<UUID, Set<CommandSender>> TRACED = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Kind>> FILTER = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();
    private static volatile boolean wrapped;

    /** What a trace records; {@code /vadmin trace <player> break place …} limits it to these. */
    public enum Kind {
        BREAK, PLACE, DAMAGE, INTERACT, MESSAGES;

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

        public static java.util.Optional<Kind> parse(String text) {
            for (Kind kind : values()) if (kind.id().equalsIgnoreCase(text)) return java.util.Optional.of(kind);
            return java.util.Optional.empty();
        }

        static Kind of(Event event) {
            if (event instanceof BlockBreakEvent) return BREAK;
            if (event instanceof BlockPlaceEvent) return PLACE;
            if (event instanceof BlockDamageEvent) return DAMAGE;
            return INTERACT;
        }
    }

    private EventTrace() {}

    public static boolean traced(Player player) { return player != null && player.getUniqueId() != null && TRACED.containsKey(player.getUniqueId()); }

    /** Toggles a full trace (every kind) for {@code target}; reports go to the target and the admin. Returns the new state. */
    public static synchronized boolean toggle(CommandSender admin, Player target) {
        if (TRACED.containsKey(target.getUniqueId())) {
            stop(target);
            return false;
        }
        trace(admin, target, java.util.EnumSet.allOf(Kind.class));
        return true;
    }

    /** Starts the trace or replaces its filter; an empty set means every kind. */
    public static synchronized void trace(CommandSender admin, Player target, Set<Kind> kinds) {
        Set<CommandSender> watchers = TRACED.computeIfAbsent(target.getUniqueId(), id -> ConcurrentHashMap.newKeySet());
        watchers.add(target);
        if (admin != null) watchers.add(admin);
        FILTER.put(target.getUniqueId(), kinds == null || kinds.isEmpty()
                ? java.util.EnumSet.allOf(Kind.class) : java.util.EnumSet.copyOf(kinds));
        wrap();
    }

    public static synchronized void stop(Player target) {
        TRACED.remove(target.getUniqueId());
        FILTER.remove(target.getUniqueId());
        LAST.remove(target.getUniqueId());
        if (TRACED.isEmpty()) unwrap();
    }

    /** Current filter of a traced player (empty if not traced). */
    public static Set<Kind> kinds(Player target) {
        Set<Kind> kinds = target == null ? null : FILTER.get(target.getUniqueId());
        return kinds == null ? Set.of() : Set.copyOf(kinds);
    }

    private static boolean wants(UUID player, Kind kind) {
        Set<Kind> kinds = FILTER.get(player);
        return kinds != null && kinds.contains(kind);
    }

    /** Stops every trace (player quit of the last tracer, Core disable). */
    public static synchronized void stopAll() {
        TRACED.clear();
        FILTER.clear();
        LAST.clear();
        unwrap();
    }

    public static synchronized void forget(Player player) {
        if (TRACED.containsKey(player.getUniqueId())) stop(player);
    }

    /** Reports a message a traced player received through Core's {@link Lang} (plugin namespace + key + text). */
    public static void message(Player viewer, String namespace, String key, String plain) {
        if (TRACED.isEmpty() || viewer == null || viewer.getUniqueId() == null) return;
        Set<CommandSender> watchers = TRACED.get(viewer.getUniqueId());
        if (watchers == null || !wants(viewer.getUniqueId(), Kind.MESSAGES)) return;
        Lang lang = Localization.get().core();
        if ("trace.message".equals(key) || (namespace != null && namespace.equals(lang.namespace()) && key != null && key.startsWith("trace."))) return;
        for (CommandSender watcher : watchers) {
            lang.send(watcher, "trace.message", "player", viewer.getName(), "plugin", namespace, "key", key, "text", plain);
        }
    }

    private static void wrap() {
        if (wrapped) return;
        for (HandlerList list : EVENTS) {
            for (RegisteredListener original : list.getRegisteredListeners()) {
                if (original instanceof Tracing) continue;
                list.unregister(original);
                list.register(new Tracing(original));
            }
        }
        wrapped = true;
    }

    private static void unwrap() {
        if (!wrapped) return;
        for (HandlerList list : EVENTS) {
            for (RegisteredListener listener : list.getRegisteredListeners()) {
                if (!(listener instanceof Tracing tracing)) continue;
                list.unregister(tracing);
                list.register(tracing.delegate);
            }
        }
        wrapped = false;
    }

    static Player playerOf(Event event) {
        if (event instanceof BlockBreakEvent e) return e.getPlayer();
        if (event instanceof BlockPlaceEvent e) return e.getPlayer();
        if (event instanceof BlockDamageEvent e) return e.getPlayer();
        if (event instanceof PlayerInteractEvent e) return e.getPlayer();
        return null;
    }

    static Block blockOf(Event event) {
        if (event instanceof BlockBreakEvent e) return e.getBlock();
        if (event instanceof BlockPlaceEvent e) return e.getBlock();
        if (event instanceof BlockDamageEvent e) return e.getBlock();
        if (event instanceof PlayerInteractEvent e) return e.getClickedBlock();
        return null;
    }

    /** Cancelled, or for interactions: block use or item use denied. */
    @SuppressWarnings("deprecation")
    static boolean denied(Event event) {
        if (event instanceof PlayerInteractEvent e) {
            return e.useInteractedBlock() == Event.Result.DENY || e.useItemInHand() == Event.Result.DENY || e.isCancelled();
        }
        return event instanceof Cancellable c && c.isCancelled();
    }

    static void report(Event event, RegisteredListener listener, boolean nowDenied) {
        Player player = playerOf(event);
        Set<CommandSender> watchers = player == null || player.getUniqueId() == null ? null : TRACED.get(player.getUniqueId());
        if (watchers == null || !wants(player.getUniqueId(), Kind.of(event))) return;
        Block block = blockOf(event);
        String where = block == null ? "-" : block.getX() + " " + block.getY() + " " + block.getZ();
        String material = block == null ? "-" : block.getType().name();
        String name = event.getEventName().replace("Event", "");
        String plugin = listener.getPlugin().getName();
        String type = listener.getListener().getClass().getName();
        String shortType = type.substring(type.lastIndexOf('.') + 1);
        String line = name + "|" + where + "|" + plugin + "|" + shortType + "|" + nowDenied;
        if (line.equals(LAST.put(player.getUniqueId(), line))) return; // same report twice in a row (e.g. held click)
        Lang lang = Localization.get().core();
        String key = nowDenied ? "trace.cancelled" : "trace.allowed";
        Object[] values = {"event", name, "block", where, "material", material, "plugin", plugin,
                "listener", shortType, "priority", listener.getPriority().name()};
        for (CommandSender watcher : watchers) lang.send(watcher, key, values);
        java.util.logging.Logger.getLogger("Vystorm").info("[Vystorm trace] " + player.getName() + ": " + name + " at " + where + " (" + material + ") "
                + (nowDenied ? "cancelled" : "allowed again") + " by " + plugin + " (" + type + ", " + listener.getPriority() + ")");
    }

    /** Wraps one listener; same plugin, priority and ignore-cancelled flag, so unregisterAll(plugin) still finds it. */
    static final class Tracing extends RegisteredListener {
        final RegisteredListener delegate;

        Tracing(RegisteredListener delegate) {
            super(delegate.getListener(), (listener, event) -> { }, delegate.getPriority(), delegate.getPlugin(), delegate.isIgnoringCancelled());
            this.delegate = delegate;
        }

        @Override
        public void callEvent(Event event) throws EventException {
            Player player = playerOf(event);
            if (player == null || player.getUniqueId() == null || !TRACED.containsKey(player.getUniqueId())) {
                delegate.callEvent(event);
                return;
            }
            boolean before = denied(event);
            delegate.callEvent(event);
            boolean after = denied(event);
            if (before != after) report(event, delegate, after);
        }
    }

    /** Last report line for a player (tests). */
    static String lastReport(UUID player) { return LAST.get(player); }

    /** For tests and the status line. */
    static boolean wrapped() { return wrapped; }

    static List<String> tracedNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : TRACED.keySet()) {
            Player p = Bukkit.getPlayer(id);
            names.add(p == null ? id.toString() : p.getName());
        }
        return names;
    }
}
