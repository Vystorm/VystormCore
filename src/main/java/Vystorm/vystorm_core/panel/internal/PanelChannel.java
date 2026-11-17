package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.ProtocolException;
import Vystorm.vystorm_core.web.internal.clientproto.TokenBucket;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelMessage;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Server-Seite von {@code vystorm:panel} (Spec §2): P_HELLO → P_WELCOME (nur für Clients, die im HELLO auf
 * {@code vystorm:web} {@code CAP_PANELS} gemeldet haben), strenges Dekodieren (≤ 4 KiB, Richtung, keine überzähligen
 * Bytes), nach 20 kaputten Nachrichten wird der Spieler bis zum Rejoin ignoriert, P_REQUEST ≤ 2/s (Burst 6; jede Anfrage
 * ist ein volles Render),
 * P_CLOSED/P_ERROR ≤ 5/s. Transfers werden gebündelt (≤ {@code chunksPerTick} pro Spieler und Tick) gesendet.
 * Plugin-Nachrichten sind nie Autorität: Aktionen prüft {@link PanelHub}. Nur Server-Thread. Core-intern.
 *
 * @since 0.25.0
 */
public final class PanelChannel {
    static final int MAX_MALFORMED = 20;
    public static final String DEFAULT_HOTKEY_PANEL = "vystorm_core:menu";

    public record Settings(boolean enabled, String hotkeyPanel, int refreshPerSecond, int chunksPerTick, double actionsPerSecond, int actionBurst) {
        public Settings {
            hotkeyPanel = hotkeyPanel == null || !PanelIds.panel(hotkeyPanel.trim()) ? "" : hotkeyPanel.trim();
            refreshPerSecond = Math.max(1, Math.min(20, refreshPerSecond));
            chunksPerTick = Math.max(1, Math.min(32, chunksPerTick));
            actionsPerSecond = Math.max(1, Math.min(100, actionsPerSecond));
            actionBurst = Math.max(1, Math.min(200, actionBurst));
        }

        public static Settings defaults() { return new Settings(true, "", 10, 8, 20, 40); }

        public static Settings from(ConfigurationSection s) {
            Settings d = defaults();
            if (s == null) return d;
            return new Settings(s.getBoolean("enabled", d.enabled()), s.getString("hotkey-panel", ""), s.getInt("refresh-per-second", d.refreshPerSecond()),
                    s.getInt("chunks-per-tick", d.chunksPerTick()), s.getDouble("actions-per-second", d.actionsPerSecond()), s.getInt("action-burst", d.actionBurst()));
        }
    }

    public interface Capabilities { int of(UUID player); }

    public interface Sender { void send(Player player, byte[] data); }

    /** Weiterleitung an den {@link PanelHub}; alle Methoden optional. */
    public interface Listener {
        default void ready(Player player) { }
        default void request(Player player, PanelMessage.Request request) { }
        default void action(Player player, PanelMessage.Action action) { }
        default void closed(Player player, PanelMessage.Closed closed) { }
        default void error(Player player, PanelMessage.Error error) { }
    }

    private static final class Conn {
        volatile int schema;   // available() darf von jedem Thread fragen
        volatile int features;
        int malformed;
        final TokenBucket requests;
        final TokenBucket small;
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        Player player;

        Conn(LongSupplier nanos) {
            requests = new TokenBucket(2, 6, nanos);
            small = new TokenBucket(5, 10, nanos);
        }
    }

    private final Settings settings;
    private final Capabilities capabilities;
    private final Sender sender;
    private final Logger logger;
    private final LongSupplier nanos;
    private final Map<UUID, Conn> conns = new ConcurrentHashMap<>();
    private Listener listener = new Listener() { };

    public PanelChannel(Settings settings, Capabilities capabilities, Sender sender, Logger logger, LongSupplier nanos) {
        this.settings = settings;
        this.capabilities = capabilities;
        this.sender = sender;
        this.logger = logger;
        this.nanos = nanos;
    }

    public void listener(Listener l) { listener = l == null ? new Listener() { } : l; }

    public Settings settings() { return settings; }

    public String hotkeyPanel() { return settings.hotkeyPanel().isEmpty() ? DEFAULT_HOTKEY_PANEL : settings.hotkeyPanel(); }

    public void onMessage(Player player, byte[] data) {
        if (!settings.enabled() || player == null) return;
        Conn c = conns.computeIfAbsent(player.getUniqueId(), id -> new Conn(nanos));
        c.player = player;
        if (c.malformed > MAX_MALFORMED) return;
        PanelMessage m;
        try {
            m = PanelMessage.decodeServerbound(data);
        } catch (ProtocolException bad) {
            if (++c.malformed == MAX_MALFORMED + 1 && logger != null)
                logger.warning("[Panels] Ignoring vystorm:panel from " + player.getName() + " until rejoin (too many malformed messages)");
            return;
        }
        if (m == null) return;
        switch (m) {
            case PanelMessage.Hello h -> hello(player, c, h);
            case PanelMessage.Request r -> {
                if (c.schema == 0) return;
                if (!c.requests.tryAcquire()) {
                    send(player, new PanelMessage.Denied(r.requestId(), Protocol.DENY_RATE_LIMITED, ""));
                    return;
                }
                safely(() -> listener.request(player, r));
            }
            case PanelMessage.Action a -> { if (c.schema != 0) safely(() -> listener.action(player, a)); }
            case PanelMessage.Closed cl -> { if (c.schema != 0 && c.small.tryAcquire()) safely(() -> listener.closed(player, cl)); }
            case PanelMessage.Error e -> { if (c.schema != 0 && c.small.tryAcquire()) safely(() -> listener.error(player, e)); }
            default -> { }   // P_BLOB_NEED: Phase 1 ohne Blobs
        }
    }

    private void hello(Player player, Conn c, PanelMessage.Hello h) {
        if (c.schema != 0) return;
        if ((capabilities.of(player.getUniqueId()) & Protocol.CAP_PANELS) == 0) return;
        int schema = Math.min(h.schemaMax(), SCHEMA_MAX);
        if (schema < Math.max(h.schemaMin(), SCHEMA_MIN)) return;
        c.schema = schema;
        c.features = h.features() & FEATURES_PHASE1;
        send(player, new PanelMessage.Welcome(schema, c.features, hotkeyPanel()));
        safely(() -> listener.ready(player));
    }

    public boolean ready(UUID player) {
        Conn c = conns.get(player);
        return c != null && c.schema != 0 && c.malformed <= MAX_MALFORMED;
    }

    public int features(UUID player) {
        Conn c = conns.get(player);
        return c == null ? 0 : c.features;
    }

    /** Sofort senden – außer es warten noch Transfer-Teile für diesen Spieler: dann hinten anstellen (Reihenfolge). */
    public boolean send(Player player, PanelMessage m) {
        byte[] data;
        try {
            data = PanelMessage.encode(m);
        } catch (ProtocolException failure) {
            if (logger != null) logger.fine("[Panels] message not encodable: " + m.getClass().getSimpleName());
            return false;
        }
        Conn c = conns.get(player.getUniqueId());
        if (c != null && !c.queue.isEmpty()) {
            c.queue.addLast(data);
            return true;
        }
        try {
            sender.send(player, data);
            return true;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (logger != null) logger.fine("[Panels] message not sent: " + m.getClass().getSimpleName());
            return false;
        }
    }

    /** In die Warteschlange (Transfers): {@link #flush()} sendet höchstens {@code chunksPerTick} pro Spieler und Tick. */
    public boolean queue(Player player, List<PanelMessage> messages) {
        Conn c = conns.get(player.getUniqueId());
        if (c == null || c.schema == 0 || c.queue.size() + messages.size() > 1024) return false;
        for (PanelMessage m : messages) c.queue.addLast(PanelMessage.encode(m));
        return true;
    }

    public void flush() {
        for (Conn c : conns.values()) {
            for (int i = 0; i < settings.chunksPerTick() && !c.queue.isEmpty(); i++) {
                byte[] next = c.queue.pollFirst();
                try { sender.send(c.player, next); } catch (RuntimeException failure) { c.queue.clear(); }
            }
        }
    }

    /** Wartende Transfer-Teile des Spielers verwerfen (alle seine Panels werden gerade geschlossen). */
    public void dropQueued(Player player) {
        Conn c = conns.get(player.getUniqueId());
        if (c != null) c.queue.clear();
    }

    public void quit(UUID player) { conns.remove(player); }

    public void clear() { conns.clear(); }

    private void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException failure) {
            if (logger != null) logger.log(java.util.logging.Level.WARNING, "[Panels] Handler failed", failure);
        }
    }
}
