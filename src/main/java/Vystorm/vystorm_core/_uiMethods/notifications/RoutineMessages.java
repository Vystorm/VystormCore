package Vystorm.vystorm_core._uiMethods.notifications;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.schedule.TaskGroup;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit low-priority notification channel. Critical confirmations must continue using direct messages.
 * <p>Preferred access: {@code api.message().routine()}.
 */
public final class RoutineMessages {
    public static final String FREQUENCY_IMMEDIATE = "immediate";
    public static final String FREQUENCY_15_SECONDS = "15s";
    public static final String FREQUENCY_60_SECONDS = "60s";
    private static final String PREF_FREQUENCY = "routine-notification-frequency";
    private static RoutineMessages active;

    private final Plugin core;
    private final API api;
    private final Map<UUID, PlayerQueue> queues = new HashMap<>();
    private final TaskGroup tasks;
    private final Listener listener;
    private boolean stopped;

    private static final class PlayerQueue {
        final RoutineQueue buffer = new RoutineQueue();
        long delayMillis;
        long dueAtMillis;
        PlayerQueue(long delayMillis) { this.delayMillis = delayMillis; this.dueAtMillis = System.currentTimeMillis() + delayMillis; }
    }

    private RoutineMessages(Plugin core, API api) {
        this.core = core;
        this.api = api;
        this.tasks = TaskGroup.of(core, "routine-messages");
        this.listener = new Listener() {
            @EventHandler public void onQuit(PlayerQuitEvent event) { queues.remove(event.getPlayer().getUniqueId()); }
            @EventHandler public void onPluginDisable(PluginDisableEvent event) {
                if (event.getPlugin() == core) stopInternal();
                else releaseInternal(event.getPlugin());
            }
        };
    }

    /** Starts the Core-owned timer, player setting, and provider cleanup listener. Call once during Core enable. */
    public static void start(Plugin core, API api) {
        requireMainThread();
        if (core == null || api == null || !core.isEnabled()) throw new IllegalArgumentException("Core plugin and API must be ready");
        if (active != null) {
            if (active.core == core && active.api == api && !active.stopped) return;
            active.stopInternal();
        }
        RoutineMessages service = new RoutineMessages(core, api);
        active = service;
        service.registerSetting();
        core.getServer().getPluginManager().registerEvents(service.listener, core);
        service.tasks.timer(20L, 20L, service::drainDue);
    }

    /** Stops delivery and discards queued routine messages. */
    public static void stop() {
        requireMainThread();
        if (active != null) active.stopInternal();
    }

    /** Remove all pending messages supplied by a plugin being released. */
    public static void release(Plugin provider) {
        requireMainThread();
        if (active != null && provider != null && provider != active.core) active.releaseInternal(provider);
    }

    /** Send a routine-only notification. Must be called from the primary server thread. */
    public static void send(Plugin provider, Player viewer, Component routineMessage) {
        requireMainThread();
        RoutineMessages service = active;
        if (service == null || service.stopped || provider == null || viewer == null || routineMessage == null
                || !service.core.isEnabled() || !provider.isEnabled() || !viewer.isOnline()) return;
        service.enqueue(provider, viewer, routineMessage);
    }

    private void registerSetting() {
        var lang = Localization.get().core();
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put(FREQUENCY_IMMEDIATE, lang.ref("notifications.preference.immediate"));
        options.put(FREQUENCY_15_SECONDS, lang.ref("notifications.preference.15s"));
        options.put(FREQUENCY_60_SECONDS, lang.ref("notifications.preference.60s"));
        Setting frequency = Setting.choice(PREF_FREQUENCY, lang.ref("notifications.preference.label"),
                lang.ref("notifications.preference.description"), options,
                player -> PlayerPrefs.text(core, player, PREF_FREQUENCY, FREQUENCY_IMMEDIATE),
                (player, value) -> {
                    PlayerPrefs.setText(core, player, PREF_FREQUENCY, value);
                    frequencyChanged(player, value);
                    return null;
                });
        api.settings().register(core, SettingsSection.player("routine-notifications", lang.ref("notifications.preference.title"),
                lang.ref("notifications.preference.section-description"), java.util.List.of(frequency)));
    }

    private void enqueue(Plugin provider, Player viewer, Component message) {
        String frequency = frequency(core, viewer);
        long delay = delayFor(frequency);
        UUID id = viewer.getUniqueId();
        PlayerQueue current = queues.get(id);
        if (delay == 0) {
            if (current != null) flush(id, viewer, current);
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, Component.text(RoutineQueue.safeText(message)));
            return;
        }
        if (current == null) {
            current = new PlayerQueue(delay);
            queues.put(id, current);
        } else if (current.delayMillis != delay) {
            current.delayMillis = delay;
            current.dueAtMillis = System.currentTimeMillis() + delay;
        }
        current.buffer.add(provider, message);
    }

    private void frequencyChanged(Player player, String value) {
        UUID id = player.getUniqueId();
        PlayerQueue current = queues.get(id);
        if (current == null) return;
        long delay = delayFor(value);
        if (delay == 0) flush(id, player, current);
        else {
            current.delayMillis = delay;
            current.dueAtMillis = System.currentTimeMillis() + delay;
        }
    }

    private void drainDue() {
        if (stopped || !core.isEnabled()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PlayerQueue>> iterator = queues.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) { iterator.remove(); continue; }
            PlayerQueue queue = entry.getValue();
            removeDisabledProviders(queue);
            if (queue.buffer.isEmpty()) { iterator.remove(); continue; }
            long currentDelay = delayFor(frequency(core, player));
            if (currentDelay == 0) {
                iterator.remove();
                deliver(player, queue.buffer.drain());
            } else if (currentDelay != queue.delayMillis) {
                queue.delayMillis = currentDelay;
                queue.dueAtMillis = now + currentDelay;
            } else if (now >= queue.dueAtMillis) {
                iterator.remove();
                deliver(player, queue.buffer.drain());
            }
        }
    }

    private void removeDisabledProviders(PlayerQueue queue) {
        queue.buffer.removeDisabledProviders();
    }

    private void flush(UUID id, Player player, PlayerQueue queue) {
        queues.remove(id, queue);
        removeDisabledProviders(queue);
        deliver(player, queue.buffer.drain());
    }

    private void deliver(Player viewer, RoutineQueue.Snapshot snapshot) {
        var messages = snapshot.messages().stream().filter(m -> m.provider().isEnabled()).toList();
        if (messages.isEmpty() && snapshot.omitted() == 0) return;
        var lang = Localization.get().core();
        int count = messages.size() + (snapshot.omitted() > 0 ? 1 : 0);
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, lang.component(viewer, "notifications.routine-title", "count", count));
        for (RoutineQueue.Pending pending : messages) {
            Component text = Component.text(pending.text());
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, pending.repeats() <= 1 ? text : lang.component(viewer, "notifications.routine-repeat",
                    "message", text, "count", pending.repeats()));
        }
        if (snapshot.omitted() > 0) Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, lang.component(viewer, "notifications.routine-omitted", "count", snapshot.omitted()));
    }

    private void releaseInternal(Plugin provider) {
        for (PlayerQueue queue : queues.values()) {
            queue.buffer.remove(provider);
            if (queue.buffer.isEmpty()) queue.dueAtMillis = 0;
        }
        queues.values().removeIf(queue -> queue.buffer.isEmpty());
    }

    private void stopInternal() {
        if (stopped) return;
        stopped = true;
        queues.clear();
        tasks.close();
        org.bukkit.event.HandlerList.unregisterAll(listener);
        if (active == this) active = null;
    }

    private static String frequency(Plugin core, Player player) {
        String value = PlayerPrefs.text(core, player, PREF_FREQUENCY, FREQUENCY_IMMEDIATE);
        return value.equals(FREQUENCY_15_SECONDS) || value.equals(FREQUENCY_60_SECONDS) ? value : FREQUENCY_IMMEDIATE;
    }

    private static long delayFor(String frequency) {
        return switch (frequency) {
            case FREQUENCY_15_SECONDS -> 15_000L;
            case FREQUENCY_60_SECONDS -> 60_000L;
            default -> 0L;
        };
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Routine messages are server-thread only");
    }
}
