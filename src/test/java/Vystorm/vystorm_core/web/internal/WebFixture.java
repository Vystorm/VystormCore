package Vystorm.vystorm_core.web.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.web.WebModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/** Gemeinsamer Aufbau für die Web-Tests: Einstellungen aus YAML, Fake-Spieler, Dispatcher, Anfrage-Helfer. */
final class WebFixture implements AutoCloseable {
    static final String HOST = "127.0.0.1:8190";
    static final String ORIGIN = "http://127.0.0.1:8190";

    /** Fake-Spielerzugriff: Rechte und Online-Status frei setzbar, sync läuft direkt. */
    static final class FakePlayers implements PlayerAccess {
        final Set<UUID> online = ConcurrentHashMap.newKeySet();
        final Map<UUID, Set<String>> denied = new ConcurrentHashMap<>();
        /** Optional stand-in for the server thread; null = run sync tasks directly on the handler thread. */
        volatile java.util.concurrent.ExecutorService serverThread;
        @Override public boolean online(UUID player) { return online.contains(player); }
        @Override public boolean has(UUID player, String node) { return !denied.getOrDefault(player, Set.of()).contains(node); }
        @Override public <T> T sync(Callable<T> task, long timeoutMs) throws Exception {
            var server = serverThread;
            if (server == null) return task.call();
            try { return server.submit(task).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.ExecutionException failed) { throw failed.getCause() instanceof Exception e ? e : failed; }
        }
        void deny(UUID player, String node) { denied.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(node); }
    }

    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    final WebSettings settings;
    final ModuleRegistry modules = new ModuleRegistry();
    final SessionStore sessions;
    final RateLimiter limiter = new RateLimiter(nanos::get, 10_000);
    final FakePlayers players = new FakePlayers();
    final AuditLog audit;
    final LiveHub live;
    final WebDispatcher dispatcher;
    final Plugin owner = plugin("TestPlugin");

    WebFixture(Path dir) throws Exception { this(dir, ""); }

    WebFixture(Path dir, String extraYaml) throws Exception { this(dir, extraYaml, Map.of()); }

    WebFixture(Path dir, String extraYaml, Map<String, StaticAssets.Asset> ui) throws Exception {
        settings = settings("enabled: true\n" + extraYaml);
        sessions = new SessionStore(clock::get, settings.loginTokenMs(), settings.sessionIdleMs(), settings.sessionMaxMs(), settings.sessionsPerPlayer());
        audit = new AuditLog(dir.resolve("web"), settings.auditMaxFileBytes(), settings.auditFiles(), true, Logger.getLogger("test"));
        Map<String, StaticAssets.Asset> core = new LinkedHashMap<>();
        core.put("index.html", StaticAssets.asset("<!doctype html><title>shell</title>".getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        core.put("login.html", StaticAssets.asset("<!doctype html><title>login</title>".getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        core.put("web.js", StaticAssets.asset("/* js */".getBytes(StandardCharsets.UTF_8), "text/javascript; charset=utf-8"));
        live = new LiveHub(settings, players, sessions, modules);
        modules.onData(entry -> live.dataChanged(entry.module(), entry.name(), entry.url()));
        dispatcher = new WebDispatcher(settings, modules, sessions, limiter, players, audit, core, ui, live, Logger.getLogger("test"));
    }

    static WebSettings settings(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("web:\n" + yaml.lines().map(l -> "  " + l).reduce("", (a, l) -> a + l + "\n"));
        return WebSettings.from(config.getConfigurationSection("web"));
    }

    static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    void register(WebModule module) { modules.register(owner, module); }

    /** Meldet einen Spieler über ein frisches Login-Token an; liefert Cookie-Wert und CSRF. */
    Login login(UUID player, String name) {
        String token = sessions.issueLoginToken(player, name, null);
        HttpResponseData r = dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", null, null));
        if (r.status() != 200) throw new AssertionError("login failed: " + r.status() + " " + r.bodyText());
        String cookie = r.header("Set-Cookie");
        String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        String csrf = com.google.gson.JsonParser.parseString(r.bodyText()).getAsJsonObject().get("csrf").getAsString();
        return new Login(dispatcher.cookieName() + "=" + value, csrf);
    }

    record Login(String cookie, String csrf) {}

    static HttpRequestData get(String target, String cookie) { return request("GET", target, headers(cookie, null, null), null); }

    static HttpRequestData post(String target, String json, String cookie, String csrf) {
        Map<String, List<String>> h = headers(cookie, csrf, ORIGIN);
        if (json != null) h.put("content-type", List.of("application/json"));
        return request("POST", target, h, json == null ? null : json.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, List<String>> headers(String cookie, String csrf, String origin) {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of(HOST));
        if (cookie != null) h.put("cookie", List.of(cookie));
        if (csrf != null) h.put("x-vystorm-csrf", List.of(csrf));
        if (origin != null) h.put("origin", List.of(origin));
        return h;
    }

    static HttpRequestData request(String method, String target, Map<String, List<String>> headers, byte[] body) {
        return new HttpRequestData(method, target, headers, body, "203.0.113.7", false);
    }

    static Map<String, List<String>> with(Map<String, List<String>> h, String name, String value) {
        Map<String, List<String>> copy = new HashMap<>(h);
        copy.put(name, new ArrayList<>(List.of(value)));
        return copy;
    }

    static Set<String> set(String... values) { return new HashSet<>(List.of(values)); }

    @Override public void close() { live.close(); dispatcher.close(); audit.close(); }
}
