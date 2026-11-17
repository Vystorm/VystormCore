package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebMethod;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Die gesamte HTTP-Logik der Web-Plattform, unabhängig vom Transport: Host-Prüfung, Pfad-Normalisierung, statische
 * Dateien, Login, Sitzungen, CSRF/Origin, Berechtigungen, Ratenlimits, Körpergrenzen, Handler-Ausführung mit Timeout,
 * Sicherheits-Header und Audit. Der eingebettete Server und der Gateway-Tunnel rufen nur {@link #handle} auf.
 * Threadsicher. Core-intern.
 *
 * @since 0.17.0
 */
public final class WebDispatcher implements AutoCloseable {
    /** Header, in dem Seiten das CSRF-Token bei jeder Nicht-GET-Anfrage mitsenden. */
    public static final String CSRF_HEADER = "x-vystorm-csrf";
    /** Grundrecht für die Weboberfläche (Standard: alle Spieler). */
    public static final String USE_PERMISSION = "vystorm.web.use";
    static final int LOGIN_BODY_LIMIT = 1024;
    static final int MAX_TARGET = 2048;
    static final int MAX_QUERY_PARAMS = 64;
    static final int MAX_JSON_DEPTH = 32;
    static final int MAX_PARAM_LENGTH = 128;

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
            + "connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
    private static final Gson GSON = new Gson();
    private static final Set<String> CORE_ENDPOINTS = Set.of("login", "logout", "session", "live", "i18n");

    private final WebSettings settings;
    private final ModuleRegistry modules;
    private final SessionStore sessions;
    private final RateLimiter limiter;
    private final PlayerAccess players;
    private final AuditLog audit;
    private final Map<String, StaticAssets.Asset> coreAssets;
    private final Map<String, StaticAssets.Asset> ui;
    private final LiveHub live;
    private final Logger logger;
    private final ExecutorService handlers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Vystorm-Web-Handler-", 0).factory());
    private final Semaphore handlerSlots;
    private final String cookieName;

    public WebDispatcher(WebSettings settings, ModuleRegistry modules, SessionStore sessions, RateLimiter limiter,
                         PlayerAccess players, AuditLog audit, Map<String, StaticAssets.Asset> coreAssets, Logger logger) {
        this(settings, modules, sessions, limiter, players, audit, coreAssets, Map.of(), null, logger);
    }

    /**
     * @param ui   gebaute Web-Oberfläche (vystorm-web-ui, {@code index.html} + {@code assets/}); leer = eingebaute Übersicht
     * @param live Live-Kanal; null = {@code /core/live} antwortet 404
     */
    public WebDispatcher(WebSettings settings, ModuleRegistry modules, SessionStore sessions, RateLimiter limiter,
                         PlayerAccess players, AuditLog audit, Map<String, StaticAssets.Asset> coreAssets,
                         Map<String, StaticAssets.Asset> ui, LiveHub live, Logger logger) {
        this.settings = settings;
        this.ui = ui == null || !ui.containsKey("index.html") ? Map.of() : Map.copyOf(ui);
        this.live = live;
        this.modules = modules;
        this.sessions = sessions;
        this.limiter = limiter;
        this.players = players;
        this.audit = audit;
        this.coreAssets = Map.copyOf(coreAssets);
        this.logger = logger;
        this.handlerSlots = new Semaphore(settings.maxConcurrentHandlers());
        this.cookieName = settings.https() ? "__Host-vystorm_session" : "vystorm_session";
    }

    public String cookieName() { return cookieName; }

    /** Bearbeitet eine Anfrage vollständig; wirft nie. */
    public HttpResponseData handle(HttpRequestData request) {
        Reply reply;
        try {
            reply = route(request);
        } catch (WebException expected) {
            reply = Reply.error(expected.status(), expected.getMessage());
        } catch (RuntimeException | Error failure) {
            if (failure instanceof VirtualMachineError fatal && !(fatal instanceof StackOverflowError)) throw fatal;
            if (logger != null) logger.log(Level.WARNING, "[Web] Request failed: " + failure);
            reply = Reply.error(500, WebText.error("internal"));
        }
        if (reply.message != null) reply.localize(language(request));
        return reply.finish(settings.https(), "HEAD".equals(request.method()));
    }

    /** Language of the request (manual switch, the session's player, Accept-Language); never touches the session. */
    String language(HttpRequestData req) {
        UUID player = null;
        try { player = sessions.peek(cookie(req)).map(SessionStore.Session::player).orElse(null); }
        catch (RuntimeException ignored) { /* anonymous */ }
        return WebText.language(req, player);
    }

    @Override public void close() { handlers.shutdownNow(); }

    // ---------------------------------------------------------------- Routing

    private Reply route(HttpRequestData req) {
        String ip = req.clientIp() == null ? "?" : req.clientIp();
        long retry = limiter.acquire("ip:" + RateLimiter.clientKey(ip), settings.ipPerMinute(), Math.max(30, settings.ipPerMinute() / 4));
        if (retry > 0) return Reply.error(429, WebText.error("too-many-requests")).header("Retry-After", Long.toString(retry));

        if (!hostAllowed(req.header("host"))) return Reply.error(421, WebText.error("unknown-host")).close();
        if (req.headerCount("host") > 1 || req.headerCount("content-length") > 1) return Reply.error(400, WebText.error("bad-request")).close();

        String target = req.target();
        if (target == null || target.isEmpty() || target.length() > MAX_TARGET || target.charAt(0) != '/' || target.startsWith("//"))
            return Reply.error(400, WebText.error("bad-path")).close();
        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        String rawQuery = q < 0 ? "" : target.substring(q + 1);
        int hash = rawQuery.indexOf('#');
        if (hash >= 0 || rawPath.indexOf('#') >= 0) return Reply.error(400, WebText.error("bad-path"));
        Path path = Path.parse(rawPath);
        if (path == null) return Reply.error(400, WebText.error("bad-path"));

        List<String> s = path.segments();
        String method = req.method();
        boolean hasUi = !ui.isEmpty();
        if (s.isEmpty()) return hasUi ? uiPage(req) : page(req, "index.html", false);
        String first = s.getFirst();
        if (s.size() == 1 && first.equals("login")) return hasUi ? uiPage(req) : page(req, "login.html", true);
        if (hasUi && first.equals("app")) return uiPage(req);
        if (hasUi && first.equals("assets") && s.size() >= 2) {
            StaticAssets.Asset asset = ui.get(String.join("/", s));
            return asset == null ? Reply.error(404, WebText.error("not-found")) : staticReply(req, asset, "public, max-age=31536000, immutable");
        }
        if (hasUi && s.size() == 1 && !path.trailingSlash() && ui.containsKey(first) && !first.equals("index.html"))
            return staticReply(req, ui.get(first), false);
        if (first.equals("data") && s.size() == 3 && !path.trailingSlash()) return data(req, s.get(1), s.get(2));
        if (first.equals("core") && s.size() == 2 && !path.trailingSlash()) {
            String name = s.get(1);
            if (CORE_ENDPOINTS.contains(name)) {
                return switch (name) {
                    case "login" -> onlyMethod(method, "POST", () -> login(req, ip));
                    case "logout" -> onlyMethod(method, "POST", () -> logout(req, ip));
                    case "live" -> onlyMethod(method, "GET", () -> live(req, ip, rawQuery));
                    case "i18n" -> onlyMethod(method, "GET", () -> i18n(req, rawQuery));
                    default -> onlyMethod(method, "GET", () -> session(req));
                };
            }
            StaticAssets.Asset asset = coreAssets.get(name);
            return asset == null ? Reply.error(404, WebText.error("not-found")) : staticReply(req, asset, false);
        }
        if (first.equals("m") && s.size() >= 2) {
            Optional<ModuleRegistry.Registered> module = modules.get(s.get(1));
            if (module.isEmpty()) return Reply.error(404, WebText.error("module-not-found"));
            if (s.size() == 2 && !path.trailingSlash()) return Reply.status(308).header("Location", "/m/" + s.get(1) + "/");
            String rest = String.join("/", s.subList(2, s.size()));
            if (rest.isEmpty() || path.trailingSlash()) rest = rest.isEmpty() ? module.get().module().entry() : rest + "/index.html";
            StaticAssets.Asset asset = module.get().assets().get(rest);
            return asset == null ? Reply.error(404, WebText.error("not-found")) : staticReply(req, asset, false);
        }
        if (first.equals("api") && s.size() >= 2) return api(req, ip, s.get(1), s.subList(2, s.size()), rawQuery, path.trailingSlash());
        return Reply.error(404, WebText.error("not-found"));
    }

    private Reply onlyMethod(String method, String allowed, java.util.function.Supplier<Reply> action) {
        if (!allowed.equals(method)) return Reply.error(405, WebText.error("method-not-allowed")).header("Allow", allowed);
        return action.get();
    }

    private Reply page(HttpRequestData req, String name, boolean noStore) {
        StaticAssets.Asset asset = coreAssets.get(name);
        if (asset == null) return Reply.error(404, WebText.error("not-found"));
        return staticReply(req, asset, noStore);
    }

    private Reply staticReply(HttpRequestData req, StaticAssets.Asset asset, boolean noStore) {
        return staticReply(req, asset, noStore ? "no-store" : "no-cache");
    }

    private Reply staticReply(HttpRequestData req, StaticAssets.Asset asset, String cache) {
        String method = req.method();
        if (!"GET".equals(method) && !"HEAD".equals(method)) return Reply.error(405, WebText.error("method-not-allowed")).header("Allow", "GET, HEAD");
        if (req.body().length > 0) return Reply.error(400, WebText.error("unexpected-body")).close();
        boolean noStore = cache.equals("no-store");
        String inm = req.header("if-none-match");
        if (!noStore && inm != null && etagMatches(inm, asset.etag()))
            return Reply.status(304).header("ETag", asset.etag()).header("Cache-Control", cache);
        if (asset.contentType().startsWith("text/html") && WebPages.templated(asset.data())) {
            String language = language(req);
            String etag = WebPages.etag(asset.etag(), language);
            if (!noStore && inm != null && etagMatches(inm, etag))
                return Reply.status(304).header("ETag", etag).header("Cache-Control", cache).header("Vary", "Cookie, Accept-Language");
            return new Reply(200, WebPages.render(asset.data(), language)).header("Content-Type", asset.contentType()).header("ETag", etag)
                    .header("Cache-Control", cache).header("Vary", "Cookie, Accept-Language");
        }
        return new Reply(200, asset.data()).header("Content-Type", asset.contentType()).header("ETag", asset.etag()).header("Cache-Control", cache);
    }

    /**
     * {@code GET /core/i18n[?ns=<plugin>]}: texts below {@code web.ui.} of Core's (or the plugin's) language bundle in the
     * request's language, plus the language list for a switch. Public, no session needed.
     */
    private Reply i18n(HttpRequestData req, String rawQuery) {
        if (req.body().length > 0) return Reply.error(400, WebText.error("unexpected-body")).close();
        List<String> ns = parseQuery(rawQuery).getOrDefault("ns", List.of());
        String namespace = ns.isEmpty() ? "vystorm_core" : ns.getFirst();
        if (!namespace.matches("[a-z0-9_.-]{1,64}")) return Reply.error(400, WebText.error("bad-param"));
        Optional<Vystorm.vystorm_core.lang.Lang> bundle = Vystorm.vystorm_core.lang.Localization.get().bundle(namespace);
        if (bundle.isEmpty()) return Reply.error(404, WebText.error("not-found"));
        return Reply.json(200, WebPages.i18n(bundle.get(), language(req))).header("Vary", "Cookie, Accept-Language");
    }

    private static boolean etagMatches(String header, String etag) {
        for (String part : header.split(",")) {
            String t = part.trim();
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals(etag) || t.equals("*")) return true;
        }
        return false;
    }

    /** Einstiegsseite der gebauten Oberfläche; Client-Routen ({@code /app/…}, {@code /login}) bekommen dieselbe Datei. */
    private Reply uiPage(HttpRequestData req) { return staticReply(req, ui.get("index.html"), "no-cache"); }

    // ---------------------------------------------------------------- Spieldaten

    /** {@code /data/<modul>/<name>.json} (aktuell, revalidieren) oder {@code /data/<modul>/<name>.<version>.json} (unveränderlich). */
    private Reply data(HttpRequestData req, String moduleId, String file) {
        if (!"GET".equals(req.method()) && !"HEAD".equals(req.method())) return Reply.error(405, WebText.error("method-not-allowed")).header("Allow", "GET, HEAD");
        if (!file.endsWith(".json")) return Reply.error(404, WebText.error("not-found"));
        String stem = file.substring(0, file.length() - 5);
        int dot = stem.indexOf('.');
        String name = dot < 0 ? stem : stem.substring(0, dot);
        String version = dot < 0 ? null : stem.substring(dot + 1);
        Optional<ModuleRegistry.Registered> module = modules.get(moduleId);
        Optional<ModuleRegistry.Data> entry = module.isEmpty() ? Optional.empty() : modules.data(moduleId, name);
        if (entry.isEmpty() || (version != null && !version.equals(entry.get().version()))) return Reply.error(404, WebText.error("not-found"));
        Optional<String> permission = module.get().module().permission();
        String visibility = "public";
        if (permission.isPresent()) {
            Optional<SessionStore.Session> session = sessions.resolve(cookie(req));
            if (session.isEmpty()) return unauthorized();
            UUID player = session.get().player();
            if (!players.has(player, USE_PERMISSION) || !players.has(player, permission.get())) return Reply.error(403, WebText.error("no-permission"));
            visibility = "private";
        }
        StaticAssets.Asset asset = new StaticAssets.Asset(entry.get().json(), "application/json; charset=utf-8", entry.get().etag());
        return staticReply(req, asset, version == null ? visibility + ", no-cache" : visibility + ", max-age=31536000, immutable");
    }

    // ---------------------------------------------------------------- Live-Kanal

    private Reply live(HttpRequestData req, String ip, String rawQuery) {
        if (live == null) return Reply.error(404, WebText.error("not-found"));
        if (req.body().length > 0) return Reply.error(400, WebText.error("unexpected-body")).close();
        // EventSource sendet (same-origin) meist kein Origin; wenn doch, muss es passen. Sec-Fetch-Site zusätzlich.
        if ((req.header("origin") != null || req.header("sec-fetch-site") != null) && !sameOriginIfPresent(req))
            return Reply.error(403, WebText.error("bad-origin"));
        Optional<SessionStore.Session> found = sessions.resolve(cookie(req));
        if (found.isEmpty()) return unauthorized();
        SessionStore.Session session = found.get();
        if (!players.has(session.player(), USE_PERMISSION)) return Reply.error(403, WebText.error("no-web-permission"));
        long retry = limiter.acquire("live:" + session.id(), 30, 10);
        if (retry > 0) return Reply.error(429, WebText.error("too-many-connects")).header("Retry-After", Long.toString(retry));
        java.util.Set<String> filter = new java.util.TreeSet<>();
        for (String list : parseQuery(rawQuery).getOrDefault("modules", List.of()))
            for (String id : list.split(",")) {
                String trimmed = id.trim();
                if (trimmed.isEmpty()) continue;
                if (!WebModule.ID.matcher(trimmed).matches() || filter.size() >= 32) return Reply.error(400, WebText.error("bad-module-list"));
                filter.add(trimmed);
            }
        LiveHub.Subscription subscription = live.subscribe(session, filter);
        try {
            subscription.retry(3000);
            boolean online = players.online(session.player());
            for (ModuleRegistry.Registered r : modules.all()) {
                WebModule m = r.module();
                if (m.snapshot().isEmpty() || (!filter.isEmpty() && !filter.contains(m.id())) || !mayOpen(session.player(), m)) continue;
                RequestImpl context = new RequestImpl(session.player(), session.name(), m.id(), WebMethod.GET, null, "/", Map.of(), Map.of(),
                        JsonNull.INSTANCE, ip, session.id(), online);
                context.language = WebText.language(req, session.player());
                Map<String, JsonElement> state = snapshot(r, context);
                if (state != null) state.forEach((type, value) -> subscription.snapshot(m.id(), type, value));
            }
            subscription.ready();
        } catch (RuntimeException | Error failure) {
            subscription.close(); // sonst belegt der nie ausgelieferte Kanal einen Platz je Spieler
            throw failure;
        }
        Reply reply = new Reply(200, new byte[0]).header("Content-Type", "text/event-stream; charset=utf-8")
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no");
        reply.stream = subscription;
        return reply;
    }

    private Map<String, JsonElement> snapshot(ModuleRegistry.Registered module, RequestImpl context) {
        boolean acquired;
        try { acquired = handlerSlots.tryAcquire(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        if (!acquired) return null;
        Future<Map<String, JsonElement>> future = null;
        try {
            future = submitHoldingSlot(() -> module.module().snapshot().get().snapshot(context));
            return future.get(settings.handlerTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failed) {
            if (future != null) future.cancel(true);
            Throwable cause = failed instanceof ExecutionException e && e.getCause() != null ? e.getCause() : failed;
            ProviderFailures.report("WebServices", ownerName(module), "snapshot", cause);
            return null;
        }
    }

    /**
     * Startet {@code task} auf einem Handler-Thread; der zuvor belegte Platz in {@code handlerSlots} wird erst frei, wenn
     * der Task wirklich endet, nicht schon beim Timeout des Aufrufers. So bleibt {@code max-concurrent-handlers} eine echte
     * Obergrenze, auch für Handler, die Interrupts ignorieren (sonst ließen sich beliebig viele hängende Handler anhäufen).
     */
    private <T> Future<T> submitHoldingSlot(Callable<T> task) {
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) handlerSlots.release(); };
        Future<T> future;
        try {
            future = handlers.submit(() -> {
                started.set(true);
                try { return task.call(); } finally { release.run(); }
            });
        } catch (RuntimeException rejected) {
            release.run();
            throw rejected;
        }
        return new Future<>() {
            @Override public boolean cancel(boolean interrupt) {
                boolean cancelled = future.cancel(interrupt);
                if (cancelled && !started.get()) release.run(); // nie gestartet: finally läuft nicht
                return cancelled;
            }
            @Override public boolean isCancelled() { return future.isCancelled(); }
            @Override public boolean isDone() { return future.isDone(); }
            @Override public T get() throws InterruptedException, ExecutionException { return future.get(); }
            @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return future.get(timeout, unit);
            }
        };
    }

    // ---------------------------------------------------------------- Login / Sitzung

    private Reply login(HttpRequestData req, String ip) {
        if (!sameOrigin(req)) { auditAnonymous("login", null, ip, "origin"); return Reply.error(403, WebText.error("bad-origin")); }
        long retry = limiter.acquire("login:" + RateLimiter.clientKey(ip), settings.loginPerMinute(), Math.min(5, settings.loginPerMinute()));
        if (retry > 0) return Reply.error(429, WebText.error("too-many-logins")).header("Retry-After", Long.toString(retry));
        if (req.body().length > LOGIN_BODY_LIMIT) return Reply.error(413, WebText.error("too-large")).close();
        if (!jsonContentType(req.header("content-type"))) return Reply.error(415, WebText.error("json-expected"));
        JsonElement body = parseJson(req.body());
        String token = body.isJsonObject() && body.getAsJsonObject().get("token") instanceof JsonElement t && t.isJsonPrimitive()
                && t.getAsJsonPrimitive().isString() ? t.getAsString() : null;
        Optional<SessionStore.LoginToken> login = sessions.consumeLoginToken(token);
        if (login.isEmpty()) {
            audit.log("login", null, null, null, ip, "denied", "invalid or expired token");
            return Reply.error(401, WebText.error("link-invalid"));
        }
        SessionStore.LoginToken used = login.get();
        if (!players.has(used.player(), USE_PERMISSION)) {
            audit.log("login", used.player(), used.name(), null, ip, "denied", "permission");
            return Reply.error(403, WebText.error("no-web-permission"));
        }
        // Eine mitgebrachte alte Sitzung dieses Browsers wird ersetzt (keine Session-Fixation).
        sessions.resolve(cookie(req)).ifPresent(sessions::revoke);
        SessionStore.Issued issued = sessions.createSession(used.player(), used.name(), used.issuer());
        audit.log("login", used.player(), used.name(), used.module(), ip, "ok", "session " + issued.session().id());
        JsonObject out = new JsonObject();
        out.addProperty("player", used.name());
        out.addProperty("csrf", issued.session().csrf());
        String redirect = "/";
        if (used.module() != null) {
            Optional<ModuleRegistry.Registered> module = modules.get(used.module());
            if (module.isPresent()) {
                if (!module.get().assets().isEmpty()) redirect = module.get().module().pagePath();
                else if (!ui.isEmpty()) redirect = "/app/" + used.module();
            }
            out.addProperty("module", used.module());
        }
        out.addProperty("redirect", redirect);
        return Reply.json(200, out).header("Set-Cookie", sessionCookie(issued.token()));
    }

    private Reply logout(HttpRequestData req, String ip) {
        if (!sameOrigin(req)) return Reply.error(403, WebText.error("bad-origin"));
        Optional<SessionStore.Session> session = sessions.resolve(cookie(req));
        if (session.isEmpty()) return Reply.error(401, WebText.error("not-logged-in")).header("Set-Cookie", clearCookie());
        if (!SessionStore.csrfMatches(session.get(), req.header(CSRF_HEADER))) return Reply.error(403, WebText.error("bad-csrf"));
        sessions.revoke(session.get());
        audit.log("logout", session.get().player(), session.get().name(), null, ip, "ok", null);
        return Reply.status(204).header("Set-Cookie", clearCookie());
    }

    private Reply session(HttpRequestData req) {
        if (req.body().length > 0) return Reply.error(400, WebText.error("unexpected-body")).close();
        Optional<SessionStore.Session> found = sessions.resolve(cookie(req));
        if (found.isEmpty()) return unauthorized();
        SessionStore.Session session = found.get();
        if (!players.has(session.player(), USE_PERMISSION)) return Reply.error(403, WebText.error("no-web-permission"));
        JsonObject out = new JsonObject();
        out.addProperty("player", session.name());
        out.addProperty("uuid", session.player().toString());
        out.addProperty("csrf", session.csrf());
        out.addProperty("online", players.online(session.player()));
        out.addProperty("expiresAt", session.expiresAt());
        out.addProperty("idleSeconds", settings.sessionIdleMs() / 1000);
        out.addProperty("core", CoreApi.VERSION);
        String language = language(req);
        out.addProperty("language", language);
        out.add("languages", WebPages.languages());
        JsonArray list = new JsonArray();
        for (ModuleRegistry.Registered r : modules.all()) {
            WebModule m = r.module();
            if (!m.listed() || !mayOpen(session.player(), m)) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", m.id());
            entry.addProperty("title", WebText.localize(m.title(), language));
            entry.addProperty("description", WebText.localize(m.description(), language));
            entry.addProperty("url", m.pagePath());
            entry.addProperty("assets", !r.assets().isEmpty());
            entry.addProperty("live", m.snapshot().isPresent());
            JsonObject data = new JsonObject();
            modules.data(m.id()).forEach((name, d) -> data.addProperty(name, d.url()));
            entry.add("data", data);
            list.add(entry);
        }
        out.add("modules", list);
        return Reply.json(200, out);
    }

    private boolean mayOpen(UUID player, WebModule module) {
        return module.permission().map(node -> players.has(player, node)).orElse(true);
    }

    // ---------------------------------------------------------------- API

    private Reply api(HttpRequestData req, String ip, String moduleId, List<String> segments, String rawQuery, boolean trailingSlash) {
        WebMethod method;
        try { method = WebMethod.valueOf(req.method()); }
        catch (IllegalArgumentException unknown) { return Reply.error(405, WebText.error("method-not-allowed")); }
        Optional<ModuleRegistry.Registered> module = modules.get(moduleId);
        if (module.isEmpty() || trailingSlash) return Reply.error(404, WebText.error("not-found"));
        boolean[] exists = {false};
        Optional<ModuleRegistry.Match> match = ModuleRegistry.match(module.get(), method, segments, exists);
        if (match.isEmpty()) {
            if (!exists[0]) return Reply.error(404, WebText.error("not-found"));
            return Reply.error(405, WebText.error("method-not-allowed")).header("Allow", String.join(", ", ModuleRegistry.allowed(module.get(), segments)));
        }
        WebRoute route = match.get().route();
        String routeName = method + " /api/" + moduleId + route.path();

        if (method.unsafe() && !sameOrigin(req)) {
            auditAnonymous("api", routeName, ip, "origin");
            return Reply.error(403, WebText.error("bad-origin"));
        }
        Optional<SessionStore.Session> found = sessions.resolve(cookie(req));
        if (found.isEmpty()) return unauthorized();
        SessionStore.Session session = found.get();
        if (method.unsafe() && !SessionStore.csrfMatches(session, req.header(CSRF_HEADER))) {
            audit.log("api", session.player(), session.name(), routeName, ip, "denied", "csrf");
            return Reply.error(403, WebText.error("bad-csrf"));
        }
        UUID player = session.player();
        if (!players.has(player, USE_PERMISSION) || !mayOpen(player, module.get().module())
                || route.permission().map(node -> !players.has(player, node)).orElse(false)) {
            if (route.mutating()) audit.log("api", player, session.name(), routeName, ip, "denied", "permission");
            return Reply.error(403, WebText.error("no-permission"));
        }
        long retry = limiter.acquire("s:" + session.id(), settings.sessionPerMinute());
        if (retry == 0) retry = limiter.acquire("r:" + session.id() + ":" + routeName, route.ratePerMinute());
        if (retry > 0) return Reply.error(429, WebText.error("too-many-requests")).header("Retry-After", Long.toString(retry));
        boolean online = players.online(player);
        if (route.requiresOnline() && !online) return Reply.error(409, WebText.error("must-be-online"));

        int limit = Math.min(route.maxBodyBytes(), settings.maxBodyBytes());
        byte[] raw = req.body();
        if (raw.length > limit) return Reply.error(413, WebText.error("too-large")).close();
        JsonElement body = JsonNull.INSTANCE;
        if (raw.length > 0) {
            if (!jsonContentType(req.header("content-type"))) return Reply.error(415, WebText.error("json-expected"));
            body = parseJson(raw);
        }
        Map<String, List<String>> query = parseQuery(rawQuery);
        for (String value : match.get().params().values())
            if (value.length() > MAX_PARAM_LENGTH) return Reply.error(400, WebText.error("param-too-long"));

        RequestImpl request = new RequestImpl(player, session.name(), moduleId, method, route, "/" + String.join("/", segments),
                Collections.unmodifiableMap(new LinkedHashMap<>(match.get().params())), query, body, ip, session.id(), online);
        request.language = WebText.language(req, player);
        Reply reply = execute(module.get(), route, request);
        if (route.mutating()) audit.log("api", player, session.name(), routeName, ip, Integer.toString(reply.status), null);
        return reply;
    }

    private Reply execute(ModuleRegistry.Registered module, WebRoute route, RequestImpl request) {
        boolean acquired;
        try { acquired = handlerSlots.tryAcquire(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Reply.error(503, WebText.error("busy")); }
        if (!acquired) return Reply.error(503, WebText.error("busy-retry")).header("Retry-After", "2");
        Future<WebResponse> future = null;
        try {
            future = submitHoldingSlot(() -> route.handler().handle(request));
            WebResponse response = future.get(settings.handlerTimeoutMs(), TimeUnit.MILLISECONDS);
            if (response == null) return Reply.status(204);
            return new Reply(response.status(), response.status() == 204 ? new byte[0] : response.body())
                    .header("Content-Type", "application/json; charset=utf-8").header("Cache-Control", "no-store");
        } catch (TimeoutException timeout) {
            future.cancel(true);
            ProviderFailures.report("WebServices", ownerName(module), route.toString(), timeout);
            return Reply.error(504, WebText.error("timeout"));
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof WebException web) return Reply.error(web.status(), web.getMessage());
            ProviderFailures.report("WebServices", ownerName(module), route.toString(), cause);
            return Reply.error(500, WebText.error("internal"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (future != null) future.cancel(true);
            return Reply.error(503, WebText.error("shutting-down"));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            return Reply.error(503, WebText.error("shutting-down"));
        } catch (RuntimeException failure) {
            ProviderFailures.report("WebServices", ownerName(module), route.toString(), failure);
            return Reply.error(500, WebText.error("internal"));
        }
    }

    /**
     * Audit-Eintrag für eine abgewiesene Anfrage ohne geprüfte Sitzung. Je Client höchstens 20 pro Minute: sonst könnte
     * anonymer Verkehr (falscher Origin kostet nur das IP-Limit) die rotierende Audit-Datei fluten und echte Einträge
     * verdrängen.
     */
    private void auditAnonymous(String event, String route, String ip, String reason) {
        if (limiter.acquire("audit:" + RateLimiter.clientKey(ip), 20, 20) == 0) audit.log(event, null, null, route, ip, "denied", reason);
    }

    private static String ownerName(ModuleRegistry.Registered module) {
        try { return module.owner().getName(); } catch (RuntimeException failure) { return module.module().id(); }
    }

    private Reply unauthorized() {
        JsonObject o = new JsonObject();
        o.addProperty("error", "unauthorized");
        o.addProperty("login", true);
        return Reply.jsonMessage(401, o, WebText.error("login-required"));
    }

    // ---------------------------------------------------------------- Prüfungen

    boolean hostAllowed(String host) {
        if (host == null || host.isEmpty() || host.length() > 255) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        List<String> allowed = settings.hostAllowlist();
        if (allowed.contains(h)) return true;
        // Standard-Port weggelassen/angegeben: beide Formen akzeptieren.
        String origin = settings.publicOrigin();
        int defaultPort = origin.startsWith("https://") ? 443 : 80;
        return allowed.contains(h + ":" + defaultPort) || (h.endsWith(":" + defaultPort) && allowed.contains(h.substring(0, h.length() - (":" + defaultPort).length())));
    }

    /** {@code Origin} muss exakt der öffentliche Ursprung sein, {@code Sec-Fetch-Site} (falls gesendet) {@code same-origin}. */
    boolean sameOrigin(HttpRequestData req) {
        String origin = req.header("origin");
        if (origin == null || req.headerCount("origin") != 1) return false;
        String expected = settings.publicUrl().isEmpty()
                ? (req.secure() ? "https://" : "http://") + String.valueOf(req.header("host")).trim().toLowerCase(Locale.ROOT)
                : settings.publicOrigin();
        if (!origin.trim().equalsIgnoreCase(expected)) return false;
        String site = req.header("sec-fetch-site");
        return site == null || site.trim().equalsIgnoreCase("same-origin");
    }

    /** Wie {@link #sameOrigin}, aber ein fehlender {@code Origin} ist erlaubt (GET-Streams). */
    private boolean sameOriginIfPresent(HttpRequestData req) {
        String site = req.header("sec-fetch-site");
        if (site != null && !site.trim().equalsIgnoreCase("same-origin")) return false;
        return req.header("origin") == null || sameOrigin(req);
    }

    String cookie(HttpRequestData req) {
        List<String> values = req.headers().get("cookie");
        if (values == null) return null;
        for (String header : values) {
            for (String part : header.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(cookieName)) return p.substring(eq + 1).trim();
            }
        }
        return null;
    }

    String sessionCookie(String token) {
        return cookieName + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (settings.sessionMaxMs() / 1000)
                + (settings.https() ? "; Secure" : "");
    }

    private String clearCookie() {
        return cookieName + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (settings.https() ? "; Secure" : "");
    }

    static boolean jsonContentType(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT).trim();
        int semi = v.indexOf(';');
        String type = semi < 0 ? v : v.substring(0, semi).trim();
        if (!type.equals("application/json")) return false;
        return semi < 0 || v.substring(semi + 1).replace(" ", "").equals("charset=utf-8");
    }

    /** Strikt: gültiges UTF-8, strikte JSON-Syntax, höchstens {@value #MAX_JSON_DEPTH} Ebenen, nichts dahinter. */
    static JsonElement parseJson(byte[] raw) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException invalid) {
            throw WebException.badRequest(WebText.error("bad-utf8"));
        }
        if (depth(text) > MAX_JSON_DEPTH) throw WebException.badRequest(WebText.error("json-too-deep"));
        try (JsonReader reader = Vystorm.vystorm_core.compat.JsonCompat.reader(text)) {
            JsonElement element = new com.google.gson.Gson().getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw WebException.badRequest(WebText.error("bad-json"));
            return element;
        } catch (WebException e) {
            throw e;
        } catch (JsonParseException | java.io.IOException | IllegalStateException | StackOverflowError invalid) {
            throw WebException.badRequest(WebText.error("bad-json"));
        }
    }

    static int depth(String json) {
        int depth = 0, max = 0;
        boolean string = false, escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (string) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{' || c == '[') max = Math.max(max, ++depth);
            else if (c == '}' || c == ']') depth--;
        }
        return max;
    }

    static Map<String, List<String>> parseQuery(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        String[] pairs = raw.split("&");
        if (pairs.length > MAX_QUERY_PARAMS) throw WebException.badRequest(WebText.error("too-many-params"));
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = Path.decode(eq < 0 ? pair : pair.substring(0, eq), true);
            String value = Path.decode(eq < 0 ? "" : pair.substring(eq + 1), true);
            if (key == null || value == null) throw WebException.badRequest(WebText.error("bad-param"));
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        out.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(frozen);
    }

    // ---------------------------------------------------------------- Pfade

    /** Ein normalisierter Pfad: dekodierte Segmente ohne leere, {@code .} oder {@code ..}. */
    record Path(List<String> segments, boolean trailingSlash) {
        /** @return null bei ungültigem Pfad */
        static Path parse(String raw) {
            if (raw.equals("/")) return new Path(List.of(), false);
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~!$&'()*+,;=:@/%".indexOf(c) >= 0;
                if (!ok) return null;
            }
            boolean trailing = raw.endsWith("/");
            String body = raw.substring(1, trailing ? raw.length() - 1 : raw.length());
            List<String> out = new ArrayList<>();
            for (String part : body.split("/", -1)) {
                String decoded = decode(part, false);
                if (decoded == null || decoded.isEmpty() || decoded.equals(".") || decoded.equals("..")) return null;
                out.add(decoded);
            }
            return new Path(List.copyOf(out), trailing);
        }

        /** Prozent-Dekodierung (UTF-8, strikt); null bei Fehlern, Steuerzeichen, {@code /} oder {@code \}. */
        static String decode(String part, boolean plusIsSpace) {
            byte[] buffer = new byte[part.length()];
            int n = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c == '%') {
                    if (i + 2 >= part.length()) return null;
                    int hi = Character.digit(part.charAt(i + 1), 16), lo = Character.digit(part.charAt(i + 2), 16);
                    if (hi < 0 || lo < 0) return null;
                    buffer[n++] = (byte) ((hi << 4) | lo);
                    i += 2;
                } else if (c == '+' && plusIsSpace) {
                    buffer[n++] = ' ';
                } else if (c > 0x7e || c < 0x20) {
                    return null;
                } else {
                    buffer[n++] = (byte) c;
                }
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer, 0, n)).toString();
            } catch (CharacterCodingException invalid) {
                return null;
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isISOControl(c) || (!plusIsSpace && (c == '/' || c == '\\'))) return null;
            }
            return text;
        }
    }

    // ---------------------------------------------------------------- Anfrage für Handler

    private final class RequestImpl implements Vystorm.vystorm_core.web.WebRequest {
        private final UUID player;
        private final String name;
        private final String module;
        private final WebMethod method;
        private final WebRoute route;
        private final String path;
        private final Map<String, String> params;
        private final Map<String, List<String>> query;
        private final JsonElement body;
        private final String ip;
        private final String sessionId;
        private final boolean online;
        String language;

        RequestImpl(UUID player, String name, String module, WebMethod method, WebRoute route, String path, Map<String, String> params,
                    Map<String, List<String>> query, JsonElement body, String ip, String sessionId, boolean online) {
            this.player = player; this.name = name; this.module = module; this.method = method; this.route = route; this.path = path;
            this.params = params; this.query = query; this.body = body; this.ip = ip; this.sessionId = sessionId; this.online = online;
        }

        @Override public UUID playerId() { return player; }
        @Override public String playerName() { return name; }
        @Override public String moduleId() { return module; }
        @Override public WebMethod method() { return method; }
        @Override public WebRoute route() { return route; }
        @Override public String path() { return path; }
        @Override public Map<String, String> pathParams() { return params; }
        @Override public Map<String, List<String>> query() { return query; }
        @Override public JsonElement body() { return body.deepCopy(); }
        @Override public <T> T body(Class<T> type) {
            try {
                T value = GSON.fromJson(body, type);
                if (value == null) throw WebException.badRequest(WebText.error("body-missing"));
                return value;
            } catch (JsonParseException | IllegalArgumentException | ClassCastException invalid) {
                throw WebException.badRequest(WebText.error("body-mismatch"));
            }
        }
        @Override public String clientIp() { return ip; }
        @Override public String sessionId() { return sessionId; }
        @Override public boolean online() { return online; }
        @Override public String language() { return language != null ? language : Vystorm.vystorm_core.web.WebRequest.super.language(); }
        @Override public <T> T sync(Callable<T> task) { return sync(task, Duration.ofMillis(settings.syncTimeoutMs())); }
        @Override public <T> T sync(Callable<T> task, Duration timeout) {
            long ms = Math.max(1, Math.min(timeout == null ? settings.syncTimeoutMs() : timeout.toMillis(), settings.handlerTimeoutMs()));
            try {
                return players.sync(task, ms);
            } catch (WebException web) {
                throw web;
            } catch (TimeoutException timedOut) {
                throw new WebException(503, WebText.error("busy-now"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new WebException(503, WebText.error("aborted"));
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof WebException web) throw web;
                throw new IllegalStateException("sync task failed", failed.getCause());
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("sync task failed", failure);
            }
        }
    }

    // ---------------------------------------------------------------- Antwort

    static final class Reply {
        final int status;
        byte[] body;
        final List<Map.Entry<String, String>> headers = new ArrayList<>();
        boolean close;
        ResponseStream stream;
        /** Error message (key, Lang.ref or text), written into the body in the request's language by {@link #localize}. */
        String message;
        JsonObject template;

        Reply(int status, byte[] body) { this.status = status; this.body = body == null ? new byte[0] : body; }

        static Reply status(int status) { return new Reply(status, new byte[0]); }

        static Reply json(int status, JsonElement json) {
            return new Reply(status, GSON.toJson(json).getBytes(StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json; charset=utf-8").header("Cache-Control", "no-store");
        }

        static Reply error(int status, String message) {
            Reply reply = new Reply(status, WebResponse.error(status, message).body())
                    .header("Content-Type", "application/json; charset=utf-8").header("Cache-Control", "no-store");
            reply.message = message == null ? "" : message;
            return reply;
        }

        /** Like {@link #json} with a {@code message} field that is localized later. */
        static Reply jsonMessage(int status, JsonObject template, String message) {
            Reply reply = json(status, template);
            reply.template = template;
            reply.message = message;
            return reply;
        }

        void localize(String language) {
            if (message == null) return;
            String text = WebText.localize(message, language);
            if (template != null) {
                JsonObject o = template.deepCopy();
                o.addProperty("message", text);
                body = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
            } else {
                body = WebResponse.error(status, text).body();
            }
        }

        Reply header(String name, String value) { headers.add(Map.entry(name, value)); return this; }

        Reply close() { this.close = true; return this; }

        HttpResponseData finish(boolean https, boolean head) {
            List<Map.Entry<String, String>> all = new ArrayList<>(headers);
            if (all.stream().noneMatch(h -> h.getKey().equalsIgnoreCase("Cache-Control"))) all.add(Map.entry("Cache-Control", "no-store"));
            all.add(Map.entry("Content-Security-Policy", CSP));
            all.add(Map.entry("X-Content-Type-Options", "nosniff"));
            all.add(Map.entry("X-Frame-Options", "DENY"));
            all.add(Map.entry("Referrer-Policy", "no-referrer"));
            all.add(Map.entry("Cross-Origin-Opener-Policy", "same-origin"));
            all.add(Map.entry("Cross-Origin-Resource-Policy", "same-origin"));
            all.add(Map.entry("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=()"));
            if (https) all.add(Map.entry("Strict-Transport-Security", "max-age=31536000"));
            boolean noBody = head || status == 204 || status == 304;
            if (stream != null && head) { stream.close(); return new HttpResponseData(status, all, new byte[0], close, null); }
            return new HttpResponseData(status, all, noBody ? new byte[0] : body, close, stream);
        }
    }
}
