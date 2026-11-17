package Vystorm.vystorm_core.web.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lebenszyklus der Web-Plattform: liest {@code web} aus Cores {@code config.yml}, startet eingebetteten Server oder
 * Gateway-Tunnel, Server-Thread-Aufgaben (Berechtigungs-Schnappschuss, Bann-Prüfung, Aufräumen) und Listener. Die
 * Modul-Registry lebt unabhängig davon, damit Plugins jederzeit registrieren können. Core-intern.
 *
 * @since 0.17.0
 */
public final class WebPlatform {
    private static final ModuleRegistry MODULES = new ModuleRegistry();

    /** Laufende Bestandteile; null, solange die Plattform aus ist. */
    public record Runtime(WebSettings settings, SessionStore sessions, RateLimiter limiter, BukkitPlayerAccess players,
                          AuditLog audit, WebDispatcher dispatcher, EmbeddedHttpServer server, GatewayTunnel tunnel,
                          Listener listener, BukkitTask refresh, BukkitTask purge, LiveHub live, int uiFiles, String uiSource) {}

    /** Classpath folder of the web UI build bundled in Core's jar (vystorm-web-ui {@code dist/}). @since 0.24.0 */
    public static final String BUNDLED_UI = "web/bundled-ui/";

    private static volatile Runtime runtime;

    private WebPlatform() {}

    public static ModuleRegistry modules() { return MODULES; }

    public static boolean running() { return runtime != null; }

    public static Optional<Runtime> runtime() { return Optional.ofNullable(runtime); }

    /**
     * Liest die Konfiguration und startet die Plattform, falls {@code web.enabled}. Erzeugt im Gateway-Modus ein fehlendes
     * Geheimnis und speichert es in der {@code config.yml} (nie im Log). Nur Server-Thread (Core {@code onEnable}).
     */
    public static synchronized void startFromConfig(JavaPlugin core) {
        MODULES.corePlugin(core);
        try { MODULES.register(core, CoreWebModule.create()); }
        catch (RuntimeException failure) { core.getLogger().log(Level.WARNING, "[Web] Built-in profile module not registered", failure); }
        try { MODULES.register(core, SettingsWebModule.create()); }
        catch (RuntimeException | LinkageError failure) { core.getLogger().log(Level.WARNING, "[Web] Built-in settings module not registered", failure); }
        WebSettings settings = WebSettings.from(core.getConfig().getConfigurationSection("web"));
        if (!settings.enabled()) return;
        if (settings.mode() == WebSettings.Mode.GATEWAY && settings.gatewaySecret().isEmpty()) {
            String secret = SessionStore.randomToken() + SessionStore.randomToken().substring(0, 21);
            // Nur diesen Schlüssel in die Datei schreiben (getConfig() enthält beim Lesen angelegte leere Abschnitte).
            java.io.File file = new java.io.File(core.getDataFolder(), "config.yml");
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            try {
                // An unreadable config.yml must not be replaced by a file holding only the secret.
                if (file.isFile()) yaml.load(file);
                yaml.set("web.gateway.secret", secret);
                Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(file.toPath(), yaml);
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
                core.getLogger().severe("[Web] Could not save the generated gateway secret: " + failure.getMessage());
                return;
            }
            if (!protectSecretFile(file.toPath()))
                core.getLogger().warning("[Web] config.yml now holds the gateway secret; make sure other system users cannot read it.");
            core.reloadConfig();
            core.getLogger().warning("[Web] Generated a new gateway secret and saved it in config.yml (web.gateway.secret)."
                    + " Copy it into the gateway configuration (VYSTORM_GATEWAY_SECRET); it is not printed here.");
            settings = WebSettings.from(core.getConfig().getConfigurationSection("web"));
        }
        start(core, settings);
    }

    /** Startet mit fertigen Einstellungen (auch für Tests); eine laufende Plattform wird vorher beendet. */
    public static synchronized void start(JavaPlugin core, WebSettings settings) {
        stop();
        Logger logger = core.getLogger();
        var problems = settings.problems();
        if (!problems.isEmpty()) {
            for (String problem : problems) logger.severe("[Web] " + problem);
            logger.severe("[Web] Web platform NOT started because of configuration errors.");
            return;
        }
        Map<String, StaticAssets.Asset> coreAssets;
        try {
            coreAssets = StaticAssets.load(core.getClass(), core.getClass().getClassLoader(), "web/core/");
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "[Web] Core web assets missing; web platform NOT started", failure);
            return;
        }
        AuditLog audit = new AuditLog(core.getDataFolder().toPath().resolve("web"), settings.auditMaxFileBytes(), settings.auditFiles(), settings.auditEnabled(), logger);
        SessionStore sessions = new SessionStore(System::currentTimeMillis, settings.loginTokenMs(), settings.sessionIdleMs(), settings.sessionMaxMs(), settings.sessionsPerPlayer());
        RateLimiter limiter = new RateLimiter(System::nanoTime, 200_000);
        BukkitPlayerAccess players = new BukkitPlayerAccess(core, WebPlatform::neededNodes, sessions::playersWithAccess, id -> {
            int revoked = sessions.revokePlayer(id);
            if (revoked > 0) audit.log("revoke", id, null, null, null, "ok", "banned (" + revoked + " sessions)");
        });
        UiChoice uiChoice = loadUi(core.getDataFolder().toPath(), settings.uiDir(), core.getClass(), core.getClass().getClassLoader(), logger);
        Map<String, StaticAssets.Asset> ui = uiChoice.files();
        String uiSource = uiChoice.source();
        LiveHub live = new LiveHub(settings, players, sessions, MODULES);
        MODULES.onData(entry -> live.dataChanged(entry.module(), entry.name(), entry.url()));
        WebDispatcher dispatcher = new WebDispatcher(settings, MODULES, sessions, limiter, players, audit, coreAssets, ui, live, logger);
        EmbeddedHttpServer server = null;
        GatewayTunnel tunnel = null;
        try {
            if (settings.mode() == WebSettings.Mode.EMBEDDED) {
                server = new EmbeddedHttpServer(settings, dispatcher::handle, logger);
                server.start();
            } else {
                tunnel = new GatewayTunnel(settings, dispatcher::handle, MODULES::revision, logger, audit);
                GatewayTunnel t = tunnel;
                MODULES.onChange(t::assetsChanged);
                tunnel.start();
            }
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "[Web] Web platform NOT started: " + failure.getMessage());
            if (server != null) server.close();
            if (tunnel != null) tunnel.close();
            MODULES.clearListeners();
            live.close();
            dispatcher.close();
            audit.close();
            return;
        }
        for (var p : Bukkit.getOnlinePlayers()) players.joined(p.getUniqueId());
        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event) {
                players.joined(event.getPlayer().getUniqueId());
                pushStatus(live, event.getPlayer(), true);
            }
            @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) {
                players.quit(event.getPlayer().getUniqueId());
                pushStatus(live, event.getPlayer(), false);
            }
            @EventHandler(priority = EventPriority.MONITOR) public void onLevel(org.bukkit.event.player.PlayerLevelChangeEvent event) { pushStatus(live, event.getPlayer(), true); }
            @EventHandler(priority = EventPriority.MONITOR) public void onWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) { pushStatus(live, event.getPlayer(), true); }
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onKick(PlayerKickEvent event) {
                if (!event.getPlayer().isBanned()) return;
                int revoked = sessions.revokePlayer(event.getPlayer().getUniqueId());
                if (revoked > 0) audit.log("revoke", event.getPlayer().getUniqueId(), event.getPlayer().getName(), null, null, "ok", "banned");
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, core);
        BukkitTask refresh = Bukkit.getScheduler().runTaskTimer(core, players::refresh, 20L, 20L);
        BukkitTask purge = Bukkit.getScheduler().runTaskTimerAsynchronously(core, () -> { sessions.purgeExpired(); limiter.purgeIdle(); }, 1200L, 1200L);
        runtime = new Runtime(settings, sessions, limiter, players, audit, dispatcher, server, tunnel, listener, refresh, purge, live, ui.size(), uiSource);
        if (!ui.isEmpty()) logger.info("[Web] Serving web UI: " + uiSource + " (" + ui.size() + " files)");
        if (server != null) {
            logger.info("[Web] Web platform started (embedded) on " + settings.bind() + ":" + server.port() + ", public URL " + settings.effectivePublicUrl());
            if (settings.publicUrl().isEmpty())
                logger.warning("[Web] web.public-url is empty: login links point to " + settings.effectivePublicUrl() + " and only work on this machine.");
            if (!WebSettings.loopback(settings.bind()) && !settings.https())
                logger.warning("[Web] The embedded server listens on " + settings.bind() + " without TLS. Put it behind a reverse proxy with HTTPS"
                        + " (bind 127.0.0.1) or use gateway mode.");
        } else {
            logger.info("[Web] Web platform started (gateway), connecting to " + settings.gatewayUrl() + ", public URL " + settings.publicUrl());
        }
        audit.log("platform", null, null, null, null, "started", settings.mode().name().toLowerCase(java.util.Locale.ROOT));
    }

    /** The web UI Core serves: files ({@code index.html} + {@code assets/}) and where they come from. @since 0.24.0 */
    record UiChoice(Map<String, StaticAssets.Asset> files, String source) {}

    /**
     * Picks the web UI: a build with {@code index.html} in {@code web.ui-dir} (relative to Core's data folder) wins,
     * otherwise the build bundled in Core's jar, otherwise none (Core's built-in overview pages). The gateway's own
     * {@code uiDir} is served by the gateway itself and never reaches this.
     *
     * @since 0.24.0
     */
    static UiChoice loadUi(Path dataFolder, String uiDir, Class<?> anchor, ClassLoader loader, Logger logger) {
        if (uiDir != null && !uiDir.isEmpty()) {
            Path dir = dataFolder.resolve(uiDir).normalize();
            try {
                Map<String, StaticAssets.Asset> ui = StaticAssets.loadDirectory(dir);
                if (ui.containsKey("index.html")) return new UiChoice(ui, uiDir);
                if (!ui.isEmpty() && logger != null) logger.warning("[Web] " + dir + " has no index.html; using the bundled web UI");
            } catch (IOException failure) {
                if (logger != null) logger.warning("[Web] Web UI in " + dir + " not loaded: " + failure.getMessage() + "; using the bundled web UI");
            }
        }
        Map<String, StaticAssets.Asset> bundled = bundledUi(anchor, loader, logger);
        if (!bundled.isEmpty()) return new UiChoice(bundled, "bundled vystorm-web-ui " + bundledUiVersion(loader));
        return new UiChoice(Map.of(), "built-in");
    }

    /**
     * The web UI build bundled in Core's jar ({@value #BUNDLED_UI}, copied from {@code webui/vystorm-web-ui-dist.zip} by
     * the Gradle build); empty when missing or without {@code index.html}. Used when {@code web.ui-dir} has no build.
     *
     * @since 0.24.0
     */
    static Map<String, StaticAssets.Asset> bundledUi(Class<?> anchor, ClassLoader loader, Logger logger) {
        try {
            Map<String, StaticAssets.Asset> ui = StaticAssets.load(anchor, loader, BUNDLED_UI);
            return ui.containsKey("index.html") ? ui : Map.of();
        } catch (IOException | RuntimeException failure) {
            if (logger != null) logger.warning("[Web] Bundled web UI not loaded: " + failure.getMessage());
            return Map.of();
        }
    }

    /** Version of the bundled web UI ({@code web/bundled-ui.properties}), "?" if unknown. @since 0.24.0 */
    static String bundledUiVersion(ClassLoader loader) {
        try (var in = loader == null ? null : loader.getResourceAsStream("web/bundled-ui.properties")) {
            if (in == null) return "?";
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            return p.getProperty("version", "?");
        } catch (IOException | RuntimeException failure) {
            return "?";
        }
    }

    /**
     * Nimmt „andere“ Benutzer die Rechte an einer Datei mit Geheimnis (POSIX, bestmöglich; Besitzer und Gruppe bleiben,
     * damit Hoster-Panels weiter zugreifen können).
     *
     * @return ob die Datei danach für andere nicht lesbar ist (false auch ohne POSIX-Dateisystem)
     */
    static boolean protectSecretFile(Path file) {
        try {
            var perms = java.nio.file.Files.getPosixFilePermissions(file);
            perms.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
            perms.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
            perms.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            java.nio.file.Files.setPosixFilePermissions(file, perms);
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return false;
        }
    }

    /** Beendet Transport, Aufgaben und Listener und verwirft alle Sitzungen. Idempotent. */
    public static synchronized void stop() {
        Runtime r = runtime;
        runtime = null;
        if (r == null) return;
        MODULES.clearListeners();
        try { if (r.refresh() != null) r.refresh().cancel(); } catch (RuntimeException ignored) { }
        try { if (r.purge() != null) r.purge().cancel(); } catch (RuntimeException ignored) { }
        try { if (r.listener() != null) HandlerList.unregisterAll(r.listener()); } catch (RuntimeException ignored) { }
        if (r.server() != null) r.server().close();
        if (r.live() != null) r.live().close();
        if (r.tunnel() != null) r.tunnel().close();
        r.dispatcher().close();
        r.sessions().revokeAll();
        r.limiter().clear();
        r.audit().log("platform", null, null, null, null, "stopped", null);
        r.audit().close();
    }

    /** Einmal-Login; leer, wenn die Plattform aus ist. Prüft keine Rechte (das macht der Aufrufer). */
    public static Optional<Vystorm.vystorm_core.web.WebLogin> issueLogin(UUID player, String name, String module, String issuer) {
        Runtime r = runtime;
        if (r == null) return Optional.empty();
        String token = r.sessions().issueLoginToken(player, name, module, issuer);
        // Rechte jetzt (online) messen: löst der Spieler den Link erst nach dem Ausloggen ein, gilt dieser Stand.
        try { if (Bukkit.isPrimaryThread()) r.players().capture(player); } catch (RuntimeException ignored) { /* Refresh holt es nach */ }
        r.audit().log("link", player, name, module, null, "issued", issuer);
        return Optional.of(new Vystorm.vystorm_core.web.WebLogin(token, r.settings().effectivePublicUrl(), module,
                java.time.Instant.now().plusMillis(r.settings().loginTokenMs())));
    }

    /** Öffentliche Basis-URL, wenn die Plattform läuft. */
    public static Optional<String> publicUrl() {
        Runtime r = runtime;
        return r == null ? Optional.empty() : Optional.of(r.settings().effectivePublicUrl());
    }

    /** Live-Ereignis an einen Spieler; false ohne laufende Plattform oder ohne offene Verbindung des Spielers. */
    public static boolean push(UUID player, String module, String type, com.google.gson.JsonElement data) {
        Runtime r = runtime;
        return r != null && r.live().publish(player, module, type, data);
    }

    /**
     * Live-Ereignis an alle Spieler mit offener Live-Verbindung (Zustellung wie {@link #push} nur mit Modulrecht).
     *
     * @return Anzahl der Spieler, die es bekommen
     * @since 0.24.0
     */
    public static int pushAll(String module, String type, com.google.gson.JsonElement data) {
        Runtime r = runtime;
        if (r == null) return 0;
        int count = 0;
        for (UUID player : r.live().connectedPlayers()) if (r.live().publish(player, module, type, data)) count++;
        return count;
    }

    /** Beendet alle Sitzungen eines Spielers. */
    public static int revoke(UUID player, String name, String reason) {
        Runtime r = runtime;
        if (r == null) return 0;
        int revoked = r.sessions().revokePlayer(player);
        r.audit().log("revoke", player, name, null, null, "ok", reason + " (" + revoked + " sessions)");
        return revoked;
    }

    /**
     * Beendet beim Quit die Sitzungen und offenen Tokens eines Spielers, deren Kanal {@code issuer} erfüllt (z. B. nur
     * die des Client-Mods). Browser-Sitzungen aus {@code /web} bleiben so unberührt.
     *
     * @since 0.20.0
     */
    public static int revokeOnQuit(UUID player, String name, java.util.function.Predicate<String> issuer, String reason) {
        Runtime r = runtime;
        if (r == null) return 0;
        int revoked = r.sessions().revokePlayer(player, issuer);
        if (revoked > 0) r.audit().log("revoke", player, name, null, null, "ok", reason + " (" + revoked + " sessions)");
        return revoked;
    }

    private static void pushStatus(LiveHub live, org.bukkit.entity.Player player, boolean online) {
        try {
            live.publish(player.getUniqueId(), ModuleRegistry.CORE_MODULE, "status", CoreWebModule.status(online ? player : null));
        } catch (RuntimeException failure) {
            // Demo-Ereignis, darf nie das Spiel stören.
        }
    }

    private static Set<String> neededNodes() {
        Set<String> nodes = new HashSet<>(MODULES.permissionNodes());
        nodes.add(WebDispatcher.USE_PERMISSION);
        return nodes;
    }

    /** Kurzer Status für {@code /web status}. */
    public static String status() {
        Runtime r = runtime;
        if (r == null) return "off";
        String transport = r.server() != null ? "embedded, port " + r.server().port() + ", " + r.server().openConnections() + " connections"
                : "gateway " + r.settings().gatewayUrl() + " (" + r.tunnel().state() + ")";
        return transport + "; " + r.sessions().sessionCount() + " sessions, " + r.sessions().loginTokenCount() + " open links, "
                + r.live().connections() + " live connections; UI " + (r.uiFiles() > 0 ? r.uiSource() + " (" + r.uiFiles() + " files)" : "built-in") + "; URL "
                + r.settings().effectivePublicUrl();
    }

    /** Nur für Tests. */
    static Path auditFile() { Runtime r = runtime; return r == null ? null : r.audit().file(); }
}
