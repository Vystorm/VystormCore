package Vystorm.vystorm_core.pack;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core.main;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Zentraler Resource-Pack-Manager (Items §20, ADR-002): Plugins registrieren Assets über {@link #assets()}, Core baut
 * daraus ein versioniertes Pack (SHA-1), veröffentlicht es (eingebauter HTTP-Server oder externe URL), sendet es beim
 * Join und verfolgt den {@link PackState} je Spieler. Konfiguration: {@code plugins/vystorm_core/resource-pack.yml}.
 *
 * <p>Modus {@code public-url}: Core baut das Pack weiterhin lokal nach {@code plugins/vystorm_core/resource-pack/vystorm-pack.zip}
 * und sendet dessen SHA-1. Die Datei muss nach jeder Asset-Änderung manuell unter der externen URL hochgeladen werden,
 * sonst lehnen Clients das Pack wegen Hash-Abweichung ab (FAILED). Ohne externe Infrastruktur den eingebauten
 * HTTP-Server mit {@code http.public-host} verwenden.
 *
 * <p>Thread-Vertrag: {@link #register}, {@link #ensurePack} und {@link #state} vom Server-Thread aufrufen; der Aufbau
 * läuft auf einem virtuellen Thread, veröffentlicht wird wieder auf dem Server-Thread.
 * <p>Preferred access: {@code api.ressource().packs()}.
 */
public final class ResourcePacks {
    public record Settings(boolean enabled, boolean sendOnJoin, boolean required, String prompt, int packFormat, String publicUrl,
                           boolean httpEnabled, String bind, int port, String publicHost) {}

    /** Veröffentlichter Stand: ZIP, Hash und Registry-Revision werden gemeinsam und atomar getauscht. */
    record Published(byte[] zip, String sha1, long revision) {}

    static final int DEFAULT_PORT = 8165;
    static final int DEFAULT_PACK_FORMAT = 88;

    private static final AssetRegistry ASSETS = new AssetRegistry();
    private static final Map<UUID, PackState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SENT_HASH = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<PackState>> WAITING = new ConcurrentHashMap<>();
    private static final AtomicBoolean LIFECYCLE = new AtomicBoolean();
    private static final AtomicBoolean REBUILD_QUEUED = new AtomicBoolean();
    /** The built-in HTTP server starts with the first pack module, not at boot (no open port while nothing is served). */
    private static final AtomicBoolean HTTP_TRIED = new AtomicBoolean();
    /** Earlier default prompts; they (and an empty prompt) are shown in the player's language ({@code pack.prompt}). */
    static final java.util.Set<String> DEFAULT_PROMPTS = java.util.Set.of("",
            "Vystorm benötigt sein Resource-Pack für eigene Items und Oberflächen.",
            "Vystorm needs its resource pack for custom items and interfaces.");
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("vystorm-core-pack".getBytes());
    private static volatile Settings settings;
    private static volatile Published published;
    private static volatile List<String> conflicts = List.of();
    private static volatile HttpServer http;
    private static volatile Listener listener;

    private ResourcePacks() {}

    public static AssetRegistry assets() { ensureLifecycle(); return ASSETS; }

    /** Registriert ein Pack-Modul und plant einen Neuaufbau. */
    public static void register(Plugin owner, String module, List<String> namespaces, Map<String, byte[]> files, int priority) {
        assets().register(owner, module, namespaces, files, priority);
        rebuildSoon();
    }

    public static PackState state(Player player) { return STATES.getOrDefault(player.getUniqueId(), PackState.NOT_SENT); }

    /** Pack geladen und enthält alle geforderten Module in der aktuellen Version. */
    public static boolean hasRequiredAssets(Player player, AssetRequirement requirement) {
        Published current = published;
        if (state(player) != PackState.LOADED || current == null || !current.sha1().equals(SENT_HASH.get(player.getUniqueId()))) return false;
        for (String module : requirement.modules()) if (!ASSETS.hasModule(module)) return false;
        return true;
    }

    /** Sendet das Pack falls nötig; das Ergebnis endet in LOADED, DECLINED oder FAILED. Server-Thread. */
    public static CompletionStage<PackState> ensurePack(Player player) {
        UUID id = player.getUniqueId();
        PackState current = state(player);
        Published pack = published;
        boolean currentHash = pack != null && pack.sha1().equals(SENT_HASH.get(id));
        if (current == PackState.LOADED && currentHash) return CompletableFuture.completedFuture(current);
        CompletableFuture<PackState> future = WAITING.computeIfAbsent(id, key -> new CompletableFuture<>());
        // Download mit aktuellem Hash läuft bereits: nicht erneut senden (sonst doppelter Prompt).
        if (current == PackState.DOWNLOADING && currentHash) return future;
        if (!send(player)) { WAITING.remove(id);future.complete(PackState.FAILED); }
        return future;
    }

    public static Optional<String> hash() { Published current = published; return current == null ? Optional.empty() : Optional.of(current.sha1()); }

    public static List<String> conflicts() { return conflicts; }

    public static Optional<String> url() {
        Published current = published;
        return url(settings, current == null ? null : current.sha1(), serverIp());
    }

    static Optional<String> url(Settings s, String sha1, String serverIp) {
        if (s == null || sha1 == null) return Optional.empty();
        if (!s.publicUrl().isBlank()) return Optional.of(s.publicUrl());
        if (!s.httpEnabled()) return Optional.empty();
        return Optional.of("http://" + host(s, serverIp) + ":" + s.port() + "/vystorm-pack-" + sha1 + ".zip");
    }

    /** Öffentlicher Host: {@code http.public-host}, sonst {@code server-ip} (außer Wildcard), sonst {@code localhost}. */
    static String host(Settings s, String serverIp) {
        if (!s.publicHost().isBlank()) return s.publicHost();
        if (serverIp != null && !serverIp.isBlank() && !isWildcard(serverIp)) return serverIp;
        return "localhost";
    }

    static boolean isWildcard(String host) { return host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]"); }

    /** true, wenn die berechnete URL für entfernte Clients vermutlich nicht erreichbar ist. */
    static boolean unreachableForRemoteClients(Settings s, String serverIp) {
        if (s == null || !s.enabled() || !s.publicUrl().isBlank() || !s.httpEnabled()) return false;
        String host = host(s, serverIp).toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.startsWith("127.") || host.equals("::1");
    }

    private static String serverIp() {
        try { return Bukkit.getServer() == null ? "" : Bukkit.getIp(); } catch (RuntimeException ignored) { return ""; }
    }

    private static boolean send(Player player) {
        Settings s = settings;
        Published current = published;
        if (s == null || !s.enabled() || current == null) return false;
        Optional<String> url = url(s, current.sha1(), serverIp());
        if (url.isEmpty()) return false;
        SENT_HASH.put(player.getUniqueId(), current.sha1());
        setState(player, PackState.DOWNLOADING);
        Component prompt = DEFAULT_PROMPTS.contains(s.prompt().trim())
                ? Vystorm.vystorm_core.lang.Localization.get().core().component(player, "pack.prompt") : Component.text(s.prompt());
        Vystorm.vystorm_core.compat.PlatformCompat.setResourcePack(player, PACK_ID, url.get(), HexFormat.of().parseHex(current.sha1()), prompt, s.required());
        return true;
    }

    /** Neuaufbau gebündelt im nächsten Tick; ZIP-Bau läuft auf einem virtuellen Thread. */
    public static void rebuildSoon() {
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || settings == null || !REBUILD_QUEUED.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskLater(core, () -> {
            REBUILD_QUEUED.set(false);
            Settings s = settings;
            if (s == null) return;
            if (!ASSETS.modules().isEmpty() && HTTP_TRIED.compareAndSet(false, true)) startHttp(core);
            long revision = ASSETS.revision();
            List<AssetRegistry.Module> modules = ASSETS.modules();
            Thread.ofVirtual().name("vystorm-pack-build").start(() -> {
                try {
                    PackBuilder.Built built = PackBuilder.build(modules, s.packFormat(), "Vystorm");
                    File out = new File(core.getDataFolder(), "resource-pack/vystorm-pack.zip");
                    out.getParentFile().mkdirs();
                    Files.write(out.toPath(), built.zip());
                    if (core.isEnabled()) Bukkit.getScheduler().runTask(core, () -> publish(built, revision));
                } catch (IOException | RuntimeException failure) {
                    core.getLogger().log(Level.WARNING, "Resource pack could not be built", failure);
                }
            });
        }, 20L);
    }

    /**
     * Übernimmt ein fertiges Pack, sofern es nicht älter als das veröffentlichte ist. Hat sich der Hash geändert
     * (auch beim allerersten Build), bekommen alle Online-Spieler das neue Pack. Server-Thread.
     *
     * @return false, wenn der Build veraltet war und verworfen wurde
     */
    static synchronized boolean publish(PackBuilder.Built built, long revision) {
        Published previous = published;
        if (previous != null && revision < previous.revision()) return false;
        boolean changed = previous == null || !previous.sha1().equals(built.sha1());
        published = new Published(built.zip(), built.sha1(), revision);
        conflicts = built.conflicts();
        Logger log = logger();
        for (String conflict : conflicts) log.warning("[ResourcePacks] Asset conflict " + conflict);
        if (changed) log.info("[ResourcePacks] Pack " + built.sha1().substring(0, 10) + " with " + built.files() + " files from "
                + ASSETS.modules().size() + " modules" + url().map(u -> " at " + u).orElse(" (no URL configured)"));
        if (!changed) return true;
        Settings s = settings;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (STATES.get(player.getUniqueId()) == PackState.LOADED) setState(player, PackState.OUTDATED);
            if (s != null && s.sendOnJoin()) send(player);
        }
        return true;
    }

    private static Logger logger() {
        Plugin core = main.getPlugin();
        return core == null ? Logger.getLogger("vystorm_core") : core.getLogger();
    }

    private static void setState(Player player, PackState state) {
        PackState previous = STATES.put(player.getUniqueId(), state);
        if (previous != state) Bukkit.getPluginManager().callEvent(new PackStateChangeEvent(player, previous == null ? PackState.NOT_SENT : previous, state));
        if (state == PackState.LOADED || state == PackState.FAILED || state == PackState.DECLINED) {
            CompletableFuture<PackState> future = WAITING.remove(player.getUniqueId());
            if (future != null) future.complete(state);
        }
    }

    static PackState map(PlayerResourcePackStatusEvent.Status status) {
        return switch (status) {
            case SUCCESSFULLY_LOADED -> PackState.LOADED;
            case DECLINED -> PackState.DECLINED;
            case ACCEPTED, DOWNLOADED -> PackState.DOWNLOADING;
            default -> PackState.FAILED;
        };
    }

    /** resource-pack.yml was unreadable in this run: it was moved aside and is not written again until a restart. */
    private static volatile boolean settingsFileBroken;

    private static Settings loadSettings(Plugin core) {
        YamlConfiguration yaml = loadSettingsYaml(new File(core.getDataFolder(), "resource-pack.yml").toPath(), core.getLogger());
        Settings raw = new Settings(yaml.getBoolean("enabled"), yaml.getBoolean("send-on-join"), yaml.getBoolean("required"), yaml.getString("prompt", ""),
                yaml.getInt("pack-format"), yaml.getString("public-url", ""), yaml.getBoolean("http.enabled"), yaml.getString("http.bind", "0.0.0.0"),
                yaml.getInt("http.port"), yaml.getString("http.public-host", ""));
        List<String> problems = new java.util.ArrayList<>();
        Settings valid = validate(raw, problems);
        for (String problem : problems) core.getLogger().warning("[ResourcePacks] resource-pack.yml: " + problem);
        return valid;
    }

    /**
     * Reads {@code resource-pack.yml} with defaults and writes missing defaults back atomically. An unreadable file is
     * moved aside ({@link AtomicFiles#readYaml}); then the defaults are used in memory and nothing is written until the
     * next restart.
     */
    static YamlConfiguration loadSettingsYaml(java.nio.file.Path file, Logger log) {
        YamlConfiguration yaml = settingsFileBroken ? null : AtomicFiles.readYaml(file, log);
        if (yaml == null) settingsFileBroken = true;
        if (yaml == null) yaml = new YamlConfiguration();
        yaml.addDefault("enabled", true);
        yaml.addDefault("send-on-join", true);
        yaml.addDefault("required", false);
        // The default prompt is shown in each player's language (lang key pack.prompt); any other text as written.
        yaml.addDefault("prompt", "Vystorm needs its resource pack for custom items and interfaces.");
        yaml.addDefault("pack-format", DEFAULT_PACK_FORMAT);
        yaml.addDefault("public-url", "");
        yaml.addDefault("http.enabled", true);
        yaml.addDefault("http.bind", "0.0.0.0");
        yaml.addDefault("http.port", DEFAULT_PORT);
        yaml.addDefault("http.public-host", "");
        yaml.options().copyDefaults(true);
        if (settingsFileBroken) return yaml;
        try {
            AtomicFiles.write(file, yaml);
        } catch (IOException failure) {
            log.log(Level.WARNING, "[ResourcePacks] " + file + " could not be saved; using the loaded values", failure);
        }
        return yaml;
    }

    /** Prüft Port, Pack-Format und das URL-Schema; ungültige Werte werden durch Defaults ersetzt und in {@code problems} gemeldet. */
    static Settings validate(Settings s, List<String> problems) {
        int port = s.port();
        if (port < 1 || port > 65535) { problems.add("http.port " + port + " is outside 1-65535; using " + DEFAULT_PORT);port = DEFAULT_PORT; }
        int format = s.packFormat();
        if (format < 1) { problems.add("pack-format " + format + " is invalid; using " + DEFAULT_PACK_FORMAT);format = DEFAULT_PACK_FORMAT; }
        String publicUrl = s.publicUrl() == null ? "" : s.publicUrl().trim();
        if (!publicUrl.isEmpty()) {
            String lower = publicUrl.toLowerCase(Locale.ROOT);
            boolean ok = lower.startsWith("http://") || lower.startsWith("https://");
            try { if (ok) java.net.URI.create(publicUrl).toURL(); } catch (IllegalArgumentException | java.net.MalformedURLException failure) { ok = false; }
            if (!ok) { problems.add("public-url '" + publicUrl + "' is not an http(s) URL and is ignored");publicUrl = ""; }
        }
        String bind = s.bind() == null || s.bind().isBlank() ? "0.0.0.0" : s.bind().trim();
        String publicHost = s.publicHost() == null ? "" : s.publicHost().trim();
        String prompt = s.prompt() == null ? "" : s.prompt();
        return new Settings(s.enabled(), s.sendOnJoin(), s.required(), prompt, format, publicUrl, s.httpEnabled(), bind, port, publicHost);
    }

    /** Antwort des HTTP-Servers für einen Pfad: die ZIP-Bytes nur für den exakten Pfad des veröffentlichten Hashes, sonst null (404). */
    static byte[] serve(Published current, String path) {
        if (current == null || path == null) return null;
        return path.equals("/vystorm-pack-" + current.sha1() + ".zip") ? current.zip() : null;
    }

    private static void startHttp(Plugin core) {
        Settings s = settings;
        if (!s.enabled() || !s.httpEnabled() || !s.publicUrl().isBlank()) return;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(s.bind(), s.port()), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                try (exchange) {
                    String method = exchange.getRequestMethod();
                    if (!"GET".equals(method) && !"HEAD".equals(method)) { exchange.sendResponseHeaders(405, -1);return; }
                    byte[] body = serve(published, exchange.getRequestURI().getPath());
                    if (body == null) { exchange.sendResponseHeaders(404, -1);return; }
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    if ("HEAD".equals(method)) { exchange.sendResponseHeaders(200, -1);return; }
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
                }
            });
            server.start();
            http = server;
        } catch (IOException | RuntimeException failure) {
            core.getLogger().warning("[ResourcePacks] HTTP server on port " + s.port() + " could not start (" + failure.getMessage()
                    + "); set http.port or public-url in resource-pack.yml");
            http = null;
        }
        if (unreachableForRemoteClients(s, serverIp()))
            core.getLogger().warning("[ResourcePacks] The pack URL points to '" + host(s, serverIp()) + "' and cannot be reached by remote players."
                    + " Set http.public-host or public-url in resource-pack.yml, otherwise every download ends in FAILED.");
    }

    /**
     * Initialisiert Einstellungen, HTTP-Server und Listener. Wird von Core in {@code onEnable} aufgerufen, damit die
     * Initialisierung nicht vom ersten (evtl. asynchronen) Aufrufer abhängt. Mehrfachaufrufe sind wirkungslos.
     */
    public static void initialize() { ensureLifecycle(); }

    private static void ensureLifecycle() {
        if (LIFECYCLE.get() || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) return;
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || !LIFECYCLE.compareAndSet(false, true)) return;
        settings = loadSettings(core);
        Listener created = new Listener() {
            @EventHandler public void onJoin(PlayerJoinEvent event) {
                STATES.put(event.getPlayer().getUniqueId(), PackState.NOT_SENT);
                Settings s = settings;
                if (s != null && s.sendOnJoin() && !ASSETS.modules().isEmpty()) send(event.getPlayer());
            }
            @EventHandler public void onQuit(PlayerQuitEvent event) {
                UUID id = event.getPlayer().getUniqueId();
                STATES.remove(id);
                SENT_HASH.remove(id);
                CompletableFuture<PackState> future = WAITING.remove(id);
                if (future != null) future.complete(PackState.FAILED);
            }
            @EventHandler public void onStatus(PlayerResourcePackStatusEvent event) {
                if (event.getID() != null && !event.getID().equals(PACK_ID)) return;
                PackState state = map(event.getStatus());
                setState(event.getPlayer(), state);
                if (state == PackState.FAILED || state == PackState.DECLINED) notifyProblem(event.getPlayer(), event.getStatus());
            }
            @EventHandler public void onDisable(PluginDisableEvent event) {
                if (event.getPlugin() == core) { shutdown();return; }
                if (ASSETS.modules().stream().anyMatch(module -> module.owner() == event.getPlugin())) {
                    ASSETS.unregisterAll(event.getPlugin());
                    rebuildSoon();
                }
            }
        };
        listener = created;
        Bukkit.getPluginManager().registerEvents(created, core);
        // Module, die vor der Initialisierung registriert wurden, sofort bauen.
        if (!ASSETS.modules().isEmpty()) rebuildSoon();
    }

    private static void notifyProblem(Player player, PlayerResourcePackStatusEvent.Status status) {
        Settings s = settings;
        if (s == null || s.required()) return; // required: der Client wird ohnehin getrennt
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        String reason = status == PlayerResourcePackStatusEvent.Status.DECLINED ? lang.plain(player, "pack.declined")
                : lang.plain(player, "pack.failed", "status", status.name().toLowerCase(Locale.ROOT));
        lang.send(player, "pack.problem", "reason", reason);
        logger().info("[ResourcePacks] " + player.getName() + ": " + status);
    }

    /**
     * Beendet HTTP-Server und Listener und setzt den Laufzeitzustand zurück; ein erneutes Enable lädt die Konfiguration neu.
     * Core-intern (wird in {@code onDisable} aufgerufen); registrierte Assets bleiben erhalten.
     */
    public static synchronized void shutdown() {
        HttpServer server = http;
        http = null;
        if (server != null) server.stop(0);
        Listener registered = listener;
        listener = null;
        if (registered != null) HandlerList.unregisterAll(registered);
        for (CompletableFuture<PackState> future : WAITING.values()) future.complete(PackState.FAILED);
        WAITING.clear();
        STATES.clear();
        SENT_HASH.clear();
        published = null;
        conflicts = List.of();
        settings = null;
        REBUILD_QUEUED.set(false);
        HTTP_TRIED.set(false);
        LIFECYCLE.set(false);
    }

    /** Nur für Tests: setzt Einstellungen ohne Dateizugriff. */
    static void configureForTest(Settings s) { settings = s; }
}
