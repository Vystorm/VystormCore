package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.web.WebLogin;
import Vystorm.vystorm_core.web.internal.clientproto.LoginToken;
import Vystorm.vystorm_core.web.internal.clientproto.ModuleIds;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.ProtocolException;
import Vystorm.vystorm_core.web.internal.clientproto.TokenBucket;
import Vystorm.vystorm_core.web.internal.clientproto.WebMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Server-Seite des Kanals {@code vystorm:web} für den optionalen Vystorm-Client-Mod (Protokoll: {@code PROTOCOL.md}
 * im Repo Vystorm/vystorm-client). Beantwortet HELLO mit WELCOME (Basis-URL der Web-Plattform), stellt auf
 * OPEN_REQUEST ein Einmal-Login-Token über {@link Logins} aus (dieselben Prüfungen wie {@code /web}) und schickt
 * SHOW/CLOSE für Plugins. Plugin-Nachrichten sind nie Autorität: sie führen höchstens zu einem Login-Token.
 *
 * <p>Streng: Nachrichten ≤ 1 KiB, richtige Richtung, keine überzähligen Bytes, HELLO nur einmal pro Verbindung,
 * OPEN_REQUEST ratenbegrenzt, nach 20 kaputten Nachrichten wird der Spieler bis zum Rejoin ignoriert. Tokens werden
 * nie geloggt. Nur Server-Thread. Core-intern.
 *
 * @since 0.19.0
 */
public final class ClientChannel {
    static final int MAX_MALFORMED = 20;

    /** Zugriff auf die Web-Plattform (in Tests ersetzbar). */
    public interface Logins {
        /** Öffentliche Basis-URL oder leer, wenn die Plattform aus ist. */
        String publicUrl();

        boolean registered(String module);

        /** Wie {@code WebServices.issueLogin}: prüft {@code vystorm.web.use} und das Modulrecht. {@code module} null = Übersicht. */
        Optional<WebLogin> issue(Player player, String module);

        /** Anzeigename des Moduls für die Overlay-Leiste (leer = keiner). */
        String title(String module);
    }

    /** Senden einer Plugin-Nachricht. */
    public interface Sender {
        void send(Player player, String channel, byte[] data);
    }

    /** Welche Web-Sitzungen beim Quit enden ({@code web.client.end-sessions-on-quit}). @since 0.20.0 */
    public enum QuitSessions {
        /** Nur Sitzungen aus Client-Mod-Tokens (Standard). */
        CLIENT,
        /** Alle Sitzungen des Spielers (auch Browser-Tabs aus {@code /web}). */
        ALL,
        /** Keine. */
        NONE;

        static QuitSessions parse(String value) {
            if (value == null) return CLIENT;
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "all" -> ALL;
                case "none", "false", "off" -> NONE;
                default -> CLIENT;
            };
        }
    }

    /** {@code web.client} aus Cores {@code config.yml}. {@code ui} = native Elemente ({@code web.client.ui.enabled}). */
    public record Settings(boolean enabled, boolean overlay, boolean preload, String hotkeyModule, int openPerMinute, boolean ui,
                           QuitSessions quitSessions) {
        public Settings {
            hotkeyModule = hotkeyModule == null || !ModuleIds.valid(hotkeyModule.trim()) ? "" : hotkeyModule.trim();
            openPerMinute = Math.max(1, Math.min(120, openPerMinute));
            if (quitSessions == null) quitSessions = QuitSessions.CLIENT;
        }

        public Settings(boolean enabled, boolean overlay, boolean preload, String hotkeyModule, int openPerMinute) {
            this(enabled, overlay, preload, hotkeyModule, openPerMinute, true, QuitSessions.CLIENT);
        }

        public static Settings from(ConfigurationSection s) {
            if (s == null) return new Settings(true, true, true, "skills", 10);
            return new Settings(s.getBoolean("enabled", true), s.getBoolean("overlay", true), s.getBoolean("preload", true),
                    s.getString("hotkey-module", "skills"), s.getInt("open-per-minute", 10), s.getBoolean("ui.enabled", true),
                    QuitSessions.parse(s.getString("end-sessions-on-quit", "client")));
        }
    }

    /** Rückmeldungen an die Plattform (Events, native Elemente). Alle Methoden optional. @since 0.20.0 */
    public interface Hooks {
        /** Nach dem WELCOME. */
        default void ready(Player player, int capabilities, String modVersion, String loader) { }

        /** Nach einer Änderung des Client-Zustands. */
        default void state(Player player, int previous, int state) { }

        /** Soll der WELCOME {@code SERVER_PANELS} setzen (Panels laufen, Client meldet CAP_PANELS)? @since 0.25.0 */
        default boolean panels(Player player, int capabilities) { return false; }
    }

    private static final class Conn {
        int version;
        int capabilities;
        int flags;
        int clientState;
        /** Zuletzt an {@link Hooks#state} gemeldeter Zustand (Ereignisse sind ratenbegrenzt, der Zustand nicht). */
        int reportedState;
        int malformed;
        /** Client hat in dieser Verbindung ein Token bekommen, also dem Web-Ursprung zugestimmt (Vorwärmen oder Taste K). */
        boolean consented;
        final TokenBucket open;
        final TokenBucket small;
        final TokenBucket show;

        Conn(Settings settings, LongSupplier nanos) {
            open = new TokenBucket(settings.openPerMinute() / 60.0, 3, nanos);
            small = new TokenBucket(2, 6, nanos);
            show = new TokenBucket(0.5, 3, nanos);
        }
    }

    private final Settings settings;
    private final Logins logins;
    private final Sender sender;
    private final Logger logger;
    private final LongSupplier nanos;
    private final Hooks hooks;
    private final Map<UUID, Conn> conns = new ConcurrentHashMap<>();

    public ClientChannel(Settings settings, Logins logins, Sender sender, Logger logger, LongSupplier nanos) {
        this(settings, logins, sender, logger, nanos, new Hooks() { });
    }

    public ClientChannel(Settings settings, Logins logins, Sender sender, Logger logger, LongSupplier nanos, Hooks hooks) {
        this.settings = settings;
        this.logins = logins;
        this.sender = sender;
        this.logger = logger;
        this.nanos = nanos;
        this.hooks = hooks == null ? new Hooks() { } : hooks;
    }

    public Settings settings() { return settings; }

    /** Eine Nachricht auf {@code vystorm:web} von {@code player}. */
    public void onMessage(Player player, byte[] data) {
        if (!settings.enabled() || player == null) return;
        Conn conn = conns.computeIfAbsent(player.getUniqueId(), id -> new Conn(settings, nanos));
        if (conn.malformed > MAX_MALFORMED) return;
        WebMessage message;
        try {
            message = WebMessage.decodeServerbound(data);
        } catch (ProtocolException bad) {
            if (++conn.malformed == MAX_MALFORMED + 1 && logger != null)
                logger.warning("[Web] Ignoring vystorm:web from " + player.getName() + " until rejoin (too many malformed messages)");
            return;
        }
        if (message == null) return;
        switch (message) {
            case WebMessage.Hello h -> hello(player, conn, h);
            case WebMessage.OpenRequest r -> openRequest(player, conn, r);
            case WebMessage.ClientState s -> {
                if (conn.version == 0) return;
                // Zustand immer übernehmen (auch das Vertrauens-Bit darf nicht im Ratenlimit verloren gehen), nur das
                // Ereignis ist begrenzt.
                conn.clientState = s.flags();
                int previous = conn.reportedState;
                if (previous == s.flags() || !conn.small.tryAcquire()) return;
                conn.reportedState = s.flags();
                safely(() -> hooks.state(player, previous, s.flags()));
            }
            case WebMessage.Closed c -> conn.small.tryAcquire();
            default -> { }
        }
    }

    private void hello(Player player, Conn conn, WebMessage.Hello h) {
        if (conn.version != 0) return;
        int version = Math.min(h.maxVersion(), Protocol.VERSION);
        if (version < Math.max(h.minVersion(), Protocol.MIN_VERSION)) return;
        conn.version = version;
        conn.capabilities = h.capabilities();
        String base = logins.publicUrl();
        conn.flags = (settings.overlay() && !base.isEmpty() ? Protocol.SERVER_OVERLAY : 0) | (settings.ui() ? Protocol.SERVER_UI : 0);
        if ((h.capabilities() & Protocol.CAP_PANELS) != 0 && hooks.panels(player, h.capabilities())) conn.flags |= Protocol.SERVER_PANELS;
        String hotkey = settings.hotkeyModule().isEmpty() || logins.registered(settings.hotkeyModule()) ? settings.hotkeyModule() : "";
        send(player, new WebMessage.Welcome(version, base, hotkey, conn.flags));
        safely(() -> hooks.ready(player, h.capabilities(), h.modVersion(), h.loader()));
    }

    private void safely(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException failure) {
            if (logger != null) logger.log(java.util.logging.Level.WARNING, "[Web] Client hook failed", failure);
        }
    }

    /** Denial texts shown by the client mod, in the player's language. */
    private static String text(Player player, String key) {
        try { return Vystorm.vystorm_core.lang.Localization.get().core().plain(player, key); }
        catch (RuntimeException | LinkageError failure) { return ""; }
    }

    private void openRequest(Player player, Conn conn, WebMessage.OpenRequest r) {
        if (conn.version == 0) return;
        if (!conn.open.tryAcquire()) {
            send(player, new WebMessage.Denied(r.requestId(), Protocol.DENY_RATE_LIMITED, text(player, "web.client-too-fast")));
            return;
        }
        if (r.target() == Protocol.TARGET_PRELOAD && !settings.preload()) {
            send(player, new WebMessage.Denied(r.requestId(), Protocol.DENY_OTHER, ""));
            return;
        }
        if (logins.publicUrl().isEmpty()) {
            send(player, new WebMessage.Denied(r.requestId(), Protocol.DENY_WEB_OFF, text(player, "web.client-not-active")));
            return;
        }
        String module = r.module().isEmpty() ? null : r.module();
        if (module != null && !logins.registered(module)) {
            send(player, new WebMessage.Denied(r.requestId(), Protocol.DENY_UNKNOWN_MODULE, text(player, "web.client-unknown")));
            return;
        }
        Optional<WebLogin> login = logins.issue(player, module);
        if (login.isEmpty() || !LoginToken.wellFormed(login.get().token())) {
            send(player, new WebMessage.Denied(r.requestId(), Protocol.DENY_NO_PERMISSION, text(player, "web.client-not-for-you")));
            return;
        }
        long seconds = Duration.between(Instant.now(), login.get().expiresAt()).toSeconds();
        int ttl = (int) Math.max(1, Math.min(LoginToken.MAX_TTL_SECONDS, seconds));
        String title = truncate(module == null ? "" : WebText.localize(logins.title(module),
                Vystorm.vystorm_core.lang.Localization.get().core().language(player)), 40);
        if (send(player, new WebMessage.Open(r.requestId(), r.module(), login.get().token(), ttl, title))) conn.consented = true;
    }

    /** Spieler hat die Verbindung beendet. */
    public void quit(UUID player) { conns.remove(player); }

    /** Alle Zustände verwerfen (Stopp). */
    public void clear() { conns.clear(); }

    public boolean hasClient(UUID player) {
        Conn c = conns.get(player);
        return c != null && c.version != 0;
    }

    public int capabilities(UUID player) {
        Conn c = conns.get(player);
        return c == null || c.version == 0 ? 0 : c.capabilities;
    }

    public int clientState(UUID player) {
        Conn c = conns.get(player);
        return c == null || c.version == 0 ? 0 : c.clientState;
    }

    /** Ob der Server beim WELCOME native Elemente angekündigt hat. */
    public boolean uiEnabled(UUID player) {
        Conn c = conns.get(player);
        return c != null && c.version != 0 && (c.flags & Protocol.SERVER_UI) != 0;
    }

    /**
     * Overlay möglich: Mod mit eingebettetem Browser, WELCOME mit Overlay-Erlaubnis und der Client würde ein SHOW
     * befolgen. Der Client befolgt SHOW nur für einen Ursprung, dem der Spieler zugestimmt hat; ohne Zustimmung zeigte
     * SHOW nur einen Hinweis und das Plugin öffnete kein Menü – bis dahin bekommt der Spieler also das normale Menü.
     *
     * <ul>
     *   <li>Protokoll ≥ 2 (Client ≥ 0.4.0, seit Core 0.21.0): der Client meldet die Zustimmung im CLIENT_STATE
     *       ({@code STATE_ORIGIN_TRUSTED}, direkt nach dem WELCOME und bei jeder Änderung) – auch ohne Vorwärmen. Die
     *       Anmeldung passiert beim ersten SHOW mit einem frischen Einmal-Token.</li>
     *   <li>Protokoll 1: erst, wenn der Client in dieser Verbindung ein Token geholt hat (0.20.0).</li>
     * </ul>
     * Ein gefälschtes Bit bewirkt nur, dass Core SHOW schickt; der Client prüft die Zustimmung selbst.
     */
    public boolean overlayAvailable(UUID player) {
        Conn c = conns.get(player);
        if (c == null || c.version == 0) return false;
        boolean follows = c.version >= Protocol.VERSION_TRUST_STATE ? (c.clientState & Protocol.STATE_ORIGIN_TRUSTED) != 0 : c.consented;
        return follows && (c.capabilities & Protocol.CAP_BROWSER_OVERLAY) != 0 && (c.flags & Protocol.SERVER_OVERLAY) != 0;
    }

    /** Vereinbarte Protokollversion, 0 ohne Handshake. @since 0.21.0 */
    public int version(UUID player) {
        Conn c = conns.get(player);
        return c == null ? 0 : c.version;
    }

    /**
     * SHOW an den Client (statt eines Kisten-/Dialogmenüs).
     *
     * @return false, wenn das Overlay nicht verfügbar, Modul/Seite ungültig oder das Ratenlimit erreicht ist
     */
    public boolean show(Player player, String module, String page) {
        if (player == null || !overlayAvailable(player.getUniqueId())) return false;
        String m = module == null ? "" : module;
        String p = page == null ? "" : page;
        if (!ModuleIds.valid(m) || !ModuleIds.validPage(p)) return false;
        if (!m.isEmpty() && !logins.registered(m)) return false;
        if (!conns.get(player.getUniqueId()).show.tryAcquire()) return false;
        return send(player, new WebMessage.Show(m, p));
    }

    /** CLOSE an den Client. */
    public boolean close(Player player, String module) {
        if (player == null || !hasClient(player.getUniqueId())) return false;
        String m = module == null ? "" : module;
        if (!ModuleIds.valid(m)) return false;
        return send(player, new WebMessage.Close(m));
    }

    private boolean send(Player player, WebMessage message) {
        try {
            sender.send(player, Protocol.CHANNEL_WEB, WebMessage.encode(message));
            return true;
        } catch (ProtocolException | IllegalArgumentException | IllegalStateException failure) {
            if (logger != null) logger.fine("[Web] vystorm:web message not sent: " + message.getClass().getSimpleName());
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
