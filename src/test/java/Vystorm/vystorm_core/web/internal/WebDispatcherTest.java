package Vystorm.vystorm_core.web.internal;

import static Vystorm.vystorm_core.web.internal.WebFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDispatcherTest {
    @TempDir Path dir;
    private WebFixture f;
    private final UUID alice = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        f = new WebFixture(dir, "limits:\n  handler-timeout-seconds: 1\n");
        f.players.online.add(alice);
        f.register(WebModule.builder("demo", "Demo")
                .asset("index.html", "<!doctype html><p>demo".getBytes(StandardCharsets.UTF_8))
                .asset("app.js", "console.log(1)".getBytes(StandardCharsets.UTF_8))
                .asset("sub/index.html", "sub".getBytes(StandardCharsets.UTF_8))
                .route(WebRoute.get("/hello", r -> WebResponse.json(Map.of("hi", r.playerName()))).build())
                .route(WebRoute.get("/items/{id}", r -> WebResponse.json(Map.of("id", r.pathParam("id")))).build())
                .route(WebRoute.post("/change", r -> { calls.incrementAndGet(); return WebResponse.json(Map.of("ok", true, "body", r.body().toString())); })
                        .maxBody(200).rateLimit(3).build())
                .route(WebRoute.post("/json", r -> { calls.incrementAndGet(); return WebResponse.json(Map.of("ok", true)); }).maxBody(200).build())
                .route(WebRoute.post("/admin", r -> WebResponse.json(Map.of("ok", true))).permission("demo.admin").build())
                .route(WebRoute.post("/ingame", r -> WebResponse.json(Map.of("ok", true))).requiresOnline().build())
                .route(WebRoute.get("/boom", r -> { throw new IllegalStateException("secret internal detail at /srv/x"); }).build())
                .route(WebRoute.get("/nope", r -> { throw new WebException(409, "Nicht genug Punkte."); }).build())
                .route(WebRoute.get("/slow", r -> { Thread.sleep(5000); return WebResponse.json(Map.of()); }).build())
                .build());
    }

    @AfterEach
    void tearDown() { f.close(); }

    private static JsonObject json(HttpResponseData r) { return JsonParser.parseString(r.bodyText()).getAsJsonObject(); }

    // ------------------------------------------------------------ Header und Host

    @Test
    void givenAnyResponse_whenReturned_thenStrictSecurityHeadersPresent() {
        for (HttpResponseData r : List.of(f.dispatcher.handle(get("/", null)), f.dispatcher.handle(get("/nothing", null)),
                f.dispatcher.handle(get("/api/demo/hello", null)))) {
            assertEquals(WebDispatcher.CSP, r.header("Content-Security-Policy"));
            assertTrue(r.header("Content-Security-Policy").contains("frame-ancestors 'none'"));
            assertEquals("nosniff", r.header("X-Content-Type-Options"));
            assertEquals("no-referrer", r.header("Referrer-Policy"));
            assertEquals("same-origin", r.header("Cross-Origin-Opener-Policy"));
            assertEquals("same-origin", r.header("Cross-Origin-Resource-Policy"));
            assertNotNull(r.header("Permissions-Policy"));
            assertNull(r.header("Strict-Transport-Security"), "no HSTS over http");
        }
    }

    @Test
    void givenHttpsPublicUrl_whenResponding_thenHstsAndSecureHostCookie() throws Exception {
        try (WebFixture https = new WebFixture(dir.resolve("s"), "public-url: https://web.example.net\n")) {
            String token = https.sessions.issueLoginToken(alice, "Alice", null);
            var headers = headers(null, null, "https://web.example.net");
            headers.put("host", List.of("web.example.net"));
            headers.put("content-type", List.of("application/json"));
            HttpResponseData r = https.dispatcher.handle(request("POST", "/core/login", headers, ("{\"token\":\"" + token + "\"}").getBytes()));
            assertEquals(200, r.status(), r.bodyText());
            assertEquals("max-age=31536000", r.header("Strict-Transport-Security"));
            String cookie = r.header("Set-Cookie");
            assertTrue(cookie.startsWith("__Host-vystorm_session="), cookie);
            assertTrue(cookie.contains("; Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Path=/"));
            assertFalse(cookie.contains("Domain="));
        }
    }

    @Test
    void givenForeignHost_whenRequested_thenMisdirected() {
        var h = with(headers(null, null, null), "host", "evil.example");
        assertEquals(421, f.dispatcher.handle(request("GET", "/", h, null)).status());
        assertEquals(421, f.dispatcher.handle(request("GET", "/", Map.of(), null)).status(), "missing Host");
        assertEquals(200, f.dispatcher.handle(request("GET", "/", with(headers(null, null, null), "host", "LOCALHOST:8190"), null)).status());
    }

    // ------------------------------------------------------------ Statische Dateien und Pfade

    @Test
    void givenModuleAsset_whenRequested_thenServedWithEtagAndRevalidated() {
        HttpResponseData r = f.dispatcher.handle(get("/m/demo/app.js", null));
        assertEquals(200, r.status());
        assertEquals("text/javascript; charset=utf-8", r.header("Content-Type"));
        assertEquals("no-cache", r.header("Cache-Control"));
        String etag = r.header("ETag");
        assertNotNull(etag);
        HttpResponseData again = f.dispatcher.handle(request("GET", "/m/demo/app.js", with(headers(null, null, null), "if-none-match", etag), null));
        assertEquals(304, again.status());
        assertEquals(0, again.body().length);
        assertEquals(308, f.dispatcher.handle(get("/m/demo", null)).status());
        assertEquals("<!doctype html><p>demo", f.dispatcher.handle(get("/m/demo/", null)).bodyText());
        assertEquals("sub", f.dispatcher.handle(get("/m/demo/sub/", null)).bodyText());
        HttpResponseData head = f.dispatcher.handle(request("HEAD", "/m/demo/app.js", headers(null, null, null), null));
        assertEquals(200, head.status());
        assertEquals(0, head.body().length);
    }

    @Test
    void givenTraversalAttempts_whenRequested_thenRejectedAndNothingLeaks() {
        List<String> attacks = List.of("/m/demo/../demo/app.js", "/m/demo/%2e%2e/%2e%2e/etc/passwd", "/m/demo/..%2fapp.js",
                "/m/demo/%2E%2E%5Capp.js", "/m/demo//app.js", "/m/demo/./app.js", "/m/demo/app.js%00.png",
                "/m/demo/\\app.js", "/m/demo/%c0%ae%c0%ae/app.js", "/m/demo/sub/../../app.js", "//evil.example/m/demo/app.js",
                "/m/demo/%", "/m/demo/%zz", "/core/../m/demo/app.js", "/m/demo/.hidden", "/m/demo/META-INF/MANIFEST.MF");
        for (String target : attacks) {
            HttpResponseData r = f.dispatcher.handle(get(target, null));
            assertTrue(r.status() == 400 || r.status() == 404, target + " -> " + r.status());
            assertFalse(r.bodyText().contains("console.log"), target);
        }
    }

    @Test
    void givenStaticFile_whenPosted_thenMethodNotAllowed() {
        HttpResponseData r = f.dispatcher.handle(post("/m/demo/app.js", "{}", null, null));
        assertEquals(405, r.status());
        assertEquals("GET, HEAD", r.header("Allow"));
    }

    // ------------------------------------------------------------ Login

    @Test
    void givenValidToken_whenLoggingIn_thenCookieAndCsrfAndTokenIsSingleUse() {
        String token = f.sessions.issueLoginToken(alice, "Alice", "demo");
        HttpResponseData r = f.dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", null, null));
        assertEquals(200, r.status());
        assertEquals("no-store", r.header("Cache-Control"));
        String cookie = r.header("Set-Cookie");
        assertTrue(cookie.startsWith("vystorm_session=") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"));
        assertFalse(cookie.contains("Secure"), "plain http localhost");
        JsonObject body = json(r);
        assertEquals("/m/demo/", body.get("redirect").getAsString());
        assertEquals(43, body.get("csrf").getAsString().length());
        assertFalse(r.bodyText().contains(token));
        HttpResponseData replay = f.dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", null, null));
        assertEquals(401, replay.status());
    }

    @Test
    void givenClientToken_whenLoggingIn_thenSessionKeepsIssuer() {
        String token = f.sessions.issueLoginToken(alice, "Alice", "demo", "client");
        HttpResponseData r = f.dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", null, null));
        assertEquals(200, r.status());
        assertEquals(1, f.sessions.revokePlayer(alice, "client"::equals), "session created from the client token");
    }

    @Test
    void givenLoginWithoutOrExternalOrigin_whenPosted_thenForbiddenAndTokenStillUnused() {
        String token = f.sessions.issueLoginToken(alice, "Alice", null);
        String body = "{\"token\":\"" + token + "\"}";
        var noOrigin = headers(null, null, null);
        noOrigin.put("content-type", List.of("application/json"));
        assertEquals(403, f.dispatcher.handle(request("POST", "/core/login", noOrigin, body.getBytes())).status());
        var evil = with(noOrigin, "origin", "https://evil.example");
        assertEquals(403, f.dispatcher.handle(request("POST", "/core/login", evil, body.getBytes())).status());
        var crossSite = with(with(noOrigin, "origin", ORIGIN), "sec-fetch-site", "cross-site");
        assertEquals(403, f.dispatcher.handle(request("POST", "/core/login", crossSite, body.getBytes())).status());
        assertEquals(200, f.dispatcher.handle(post("/core/login", body, null, null)).status());
    }

    @Test
    void givenExpiredToken_whenLoggingIn_thenUnauthorized() {
        String token = f.sessions.issueLoginToken(alice, "Alice", null);
        f.clock.addAndGet(61_000);
        assertEquals(401, f.dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", null, null)).status());
    }

    @Test
    void givenManyLoginAttempts_whenRateLimited_then429() {
        int limited = 0;
        for (int i = 0; i < 20; i++) {
            HttpResponseData r = f.dispatcher.handle(post("/core/login", "{\"token\":\"" + SessionStore.randomToken() + "\"}", null, null));
            if (r.status() == 429) { limited++; assertNotNull(r.header("Retry-After")); }
            else assertEquals(401, r.status());
        }
        assertTrue(limited >= 10, "login limited per IP: " + limited);
    }

    @Test
    void givenPlayerWithoutUsePermission_whenLoggingIn_thenForbidden() {
        f.players.deny(alice, WebDispatcher.USE_PERMISSION);
        String token = f.sessions.issueLoginToken(alice, "Alice", null);
        assertEquals(403, f.dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", null, null)).status());
    }

    @Test
    void givenOldSessionCookie_whenLoggingInAgain_thenOldSessionRevoked() {
        Login first = f.login(alice, "Alice");
        String token = f.sessions.issueLoginToken(alice, "Alice", null);
        HttpResponseData r = f.dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", first.cookie(), null));
        assertEquals(200, r.status());
        assertEquals(401, f.dispatcher.handle(get("/core/session", first.cookie())).status());
    }

    // ------------------------------------------------------------ Sitzung und API

    @Test
    void givenSession_whenQueried_thenPlayerAndPermittedModules() {
        f.register(WebModule.builder("secret", "Geheim").permission("secret.view").build());
        f.players.deny(alice, "secret.view");
        Login login = f.login(alice, "Alice");
        HttpResponseData r = f.dispatcher.handle(get("/core/session", login.cookie()));
        assertEquals(200, r.status());
        JsonObject s = json(r);
        assertEquals("Alice", s.get("player").getAsString());
        assertTrue(s.get("online").getAsBoolean());
        String modules = s.get("modules").toString();
        assertTrue(modules.contains("\"demo\""));
        assertFalse(modules.contains("secret"), "module without permission is not listed");
    }

    @Test
    void givenNoSession_whenApiCalled_thenUnauthorizedWithLoginHint() {
        HttpResponseData r = f.dispatcher.handle(get("/api/demo/hello", null));
        assertEquals(401, r.status());
        assertTrue(json(r).get("login").getAsBoolean());
        assertEquals(401, f.dispatcher.handle(get("/api/demo/hello", f.dispatcher.cookieName() + "=" + SessionStore.randomToken())).status());
    }

    @Test
    void givenSession_whenGetRouteCalled_thenHandlerRunsWithParams() {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = f.dispatcher.handle(get("/api/demo/items/a%20b", login.cookie()));
        assertEquals(200, r.status());
        assertEquals("a b", json(r).get("id").getAsString());
        assertEquals("no-store", r.header("Cache-Control"));
        assertEquals(404, f.dispatcher.handle(get("/api/demo/items", login.cookie())).status());
        assertEquals(404, f.dispatcher.handle(get("/api/unknown/x", login.cookie())).status());
        HttpResponseData wrongMethod = f.dispatcher.handle(post("/api/demo/hello", null, login.cookie(), login.csrf()));
        assertEquals(405, wrongMethod.status());
        assertEquals("GET", wrongMethod.header("Allow"));
    }

    @Test
    void givenMutatingCall_whenCsrfMissingOrWrong_thenForbiddenAndHandlerNotRun() {
        Login login = f.login(alice, "Alice");
        assertEquals(403, f.dispatcher.handle(post("/api/demo/change", "{}", login.cookie(), null)).status());
        assertEquals(403, f.dispatcher.handle(post("/api/demo/change", "{}", login.cookie(), SessionStore.randomToken())).status());
        var badOrigin = with(headers(login.cookie(), login.csrf(), "https://evil.example"), "content-type", "application/json");
        assertEquals(403, f.dispatcher.handle(request("POST", "/api/demo/change", badOrigin, "{}".getBytes())).status());
        var noOrigin = with(headers(login.cookie(), login.csrf(), null), "content-type", "application/json");
        assertEquals(403, f.dispatcher.handle(request("POST", "/api/demo/change", noOrigin, "{}".getBytes())).status());
        assertEquals(0, calls.get());
        HttpResponseData ok = f.dispatcher.handle(post("/api/demo/change", "{\"a\":1}", login.cookie(), login.csrf()));
        assertEquals(200, ok.status(), ok.bodyText());
        assertEquals(1, calls.get());
    }

    @Test
    void givenCsrfOfOtherSession_whenUsed_thenForbidden() {
        Login alices = f.login(alice, "Alice");
        Login bobs = f.login(UUID.randomUUID(), "Bob");
        assertEquals(403, f.dispatcher.handle(post("/api/demo/change", "{}", alices.cookie(), bobs.csrf())).status());
    }

    @Test
    void givenMissingRoutePermission_whenCalled_thenForbidden() {
        f.players.deny(alice, "demo.admin");
        Login login = f.login(alice, "Alice");
        HttpResponseData r = f.dispatcher.handle(post("/api/demo/admin", "{}", login.cookie(), login.csrf()));
        assertEquals(403, r.status());
        f.players.denied.clear();
        assertEquals(200, f.dispatcher.handle(post("/api/demo/admin", "{}", login.cookie(), login.csrf())).status(), "re-evaluated per request");
    }

    @Test
    void givenRevokedUsePermission_whenApiCalled_thenForbiddenImmediately() {
        Login login = f.login(alice, "Alice");
        f.players.deny(alice, WebDispatcher.USE_PERMISSION);
        assertEquals(403, f.dispatcher.handle(get("/api/demo/hello", login.cookie())).status());
    }

    @Test
    void givenOfflinePlayer_whenOnlineRouteCalled_thenConflictButOtherRoutesWork() {
        Login login = f.login(alice, "Alice");
        f.players.online.remove(alice);
        assertEquals(409, f.dispatcher.handle(post("/api/demo/ingame", "{}", login.cookie(), login.csrf())).status());
        assertEquals(200, f.dispatcher.handle(get("/api/demo/hello", login.cookie())).status());
    }

    @Test
    void givenRouteRateLimit_whenExceeded_thenTooManyRequests() {
        Login login = f.login(alice, "Alice");
        int ok = 0, limited = 0;
        for (int i = 0; i < 10; i++) {
            HttpResponseData r = f.dispatcher.handle(post("/api/demo/change", "{}", login.cookie(), login.csrf()));
            if (r.status() == 200) ok++;
            if (r.status() == 429) { limited++; assertNotNull(r.header("Retry-After")); }
        }
        assertEquals(3, ok, "route limit 3/min, burst 3");
        assertEquals(7, limited);
    }

    @Test
    void givenBodyProblems_whenPosted_thenRejectedBeforeHandler() {
        Login login = f.login(alice, "Alice");
        String big = "{\"a\":\"" + "x".repeat(300) + "\"}";
        assertEquals(413, f.dispatcher.handle(post("/api/demo/json", big, login.cookie(), login.csrf())).status());
        var text = with(headers(login.cookie(), login.csrf(), ORIGIN), "content-type", "text/plain");
        assertEquals(415, f.dispatcher.handle(request("POST", "/api/demo/json", text, "{}".getBytes())).status());
        assertEquals(400, f.dispatcher.handle(post("/api/demo/json", "{a:1}", login.cookie(), login.csrf())).status(), "strict JSON");
        assertEquals(400, f.dispatcher.handle(post("/api/demo/json", "{} {}", login.cookie(), login.csrf())).status(), "trailing data");
        assertEquals(400, f.dispatcher.handle(post("/api/demo/json", "[".repeat(40) + "]".repeat(40), login.cookie(), login.csrf())).status(), "depth");
        var badUtf8 = with(headers(login.cookie(), login.csrf(), ORIGIN), "content-type", "application/json");
        assertEquals(400, f.dispatcher.handle(request("POST", "/api/demo/json", badUtf8, new byte[]{'"', (byte) 0xC3, '"'})).status());
        assertEquals(0, calls.get());
    }

    @Test
    void givenFailingHandler_whenCalled_thenGeneric500WithoutDetails() {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = f.dispatcher.handle(get("/api/demo/boom", login.cookie()));
        assertEquals(500, r.status());
        assertFalse(r.bodyText().contains("secret"));
        assertFalse(r.bodyText().contains("IllegalState"));
        assertFalse(r.bodyText().contains("at "));
        assertEquals("internal_error", json(r).get("error").getAsString());
    }

    @Test
    void givenWebException_whenThrown_thenStatusAndMessagePassedThrough() {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = f.dispatcher.handle(get("/api/demo/nope", login.cookie()));
        assertEquals(409, r.status());
        assertEquals("Nicht genug Punkte.", json(r).get("message").getAsString());
    }

    @Test
    void givenSlowHandler_whenTimeoutReached_thenGatewayTimeout() {
        Login login = f.login(alice, "Alice");
        long start = System.nanoTime();
        HttpResponseData r = f.dispatcher.handle(get("/api/demo/slow", login.cookie()));
        assertEquals(504, r.status());
        assertTrue(System.nanoTime() - start < 3_000_000_000L);
    }

    @Test
    void givenLogout_whenCsrfValid_thenSessionEndsAndCookieCleared() {
        Login login = f.login(alice, "Alice");
        assertEquals(403, f.dispatcher.handle(post("/core/logout", null, login.cookie(), null)).status());
        HttpResponseData r = f.dispatcher.handle(post("/core/logout", null, login.cookie(), login.csrf()));
        assertEquals(204, r.status());
        assertTrue(r.header("Set-Cookie").contains("Max-Age=0"));
        assertEquals(401, f.dispatcher.handle(get("/api/demo/hello", login.cookie())).status());
    }

    @Test
    void givenUnregisteredModule_whenRequested_thenRoutesAndAssetsGone() {
        Login login = f.login(alice, "Alice");
        assertEquals(200, f.dispatcher.handle(get("/api/demo/hello", login.cookie())).status());
        f.modules.unregister(f.owner);
        assertEquals(404, f.dispatcher.handle(get("/api/demo/hello", login.cookie())).status());
        assertEquals(404, f.dispatcher.handle(get("/m/demo/app.js", null)).status());
    }

    @Test
    void givenMutatingCallsAndLogins_whenAudited_thenLoggedWithoutSecrets() throws Exception {
        Login login = f.login(alice, "Alice");
        f.dispatcher.handle(post("/api/demo/change", "{\"password\":\"hunter2\"}", login.cookie(), login.csrf()));
        f.dispatcher.handle(post("/api/demo/change", "{}", login.cookie(), null));
        f.audit.flush(2000);
        String log = Files.readString(dir.resolve("web/audit.log"));
        assertTrue(log.contains("event=login") && log.contains("result=ok"), log);
        assertTrue(log.contains("route=POST_/api/demo/change") && log.contains("result=200"), log);
        assertTrue(log.contains("result=denied") && log.contains("csrf"), log);
        assertTrue(log.contains("ip=203.0.113.7"));
        assertFalse(log.contains("hunter2"));
        assertFalse(log.contains(login.csrf()));
        assertFalse(log.contains(login.cookie().substring(login.cookie().indexOf('=') + 1)));
    }

    @Test
    void givenGlobalIpLimit_whenFlooded_then429() throws Exception {
        try (WebFixture small = new WebFixture(dir.resolve("small"), "rate-limits:\n  ip-per-minute: 10\n")) {
            int limited = 0;
            for (int i = 0; i < 60; i++) if (small.dispatcher.handle(get("/", null)).status() == 429) limited++;
            assertTrue(limited >= 25, "limited " + limited);
        }
    }

    @Test
    void givenHandlerIgnoringInterrupts_whenItTimesOut_thenItKeepsItsSlotUntilItReallyEnds() throws Exception {
        // Ein Handler, der den Timeout ignoriert, läuft weiter. Gab der Dispatcher den Platz trotzdem frei, konnte ein
        // Spieler mit wiederholten Aufrufen beliebig viele hängende Handler anhäufen (max-concurrent-handlers wirkungslos).
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (WebFixture one = new WebFixture(dir.resolve("one"), "limits:\n  handler-timeout-seconds: 1\n  max-concurrent-handlers: 1\n")) {
            one.register(WebModule.builder("stuck", "Stuck")
                    .route(WebRoute.get("/spin", r -> {
                        while (release.getCount() > 0) Thread.onSpinWait(); // ignoriert Interrupts
                        return WebResponse.json(Map.of());
                    }).build())
                    .route(WebRoute.get("/ok", r -> WebResponse.json(Map.of("ok", true))).build())
                    .build());
            Login login = one.login(alice, "Alice");
            assertEquals(504, one.dispatcher.handle(get("/api/stuck/spin", login.cookie())).status());
            assertEquals(503, one.dispatcher.handle(get("/api/stuck/ok", login.cookie())).status(), "slot still held by the runaway handler");
            release.countDown();
            long until = System.nanoTime() + 3_000_000_000L;
            int status = 0;
            while (System.nanoTime() < until && (status = one.dispatcher.handle(get("/api/stuck/ok", login.cookie())).status()) != 200) Thread.sleep(50);
            assertEquals(200, status, "slot free again once the handler finished");
        } finally {
            release.countDown();
        }
    }

    @Test
    void givenIpv6Clients_whenInSameSlash64_thenTheyShareOneIpBucket() throws Exception {
        try (WebFixture small = new WebFixture(dir.resolve("v6"), "rate-limits:\n  ip-per-minute: 10\n")) {
            int limited = 0;
            for (int i = 0; i < 60; i++) {
                String ip = "2001:db8:1:2:" + Integer.toHexString(i + 1) + "::" + Integer.toHexString(i + 7);
                HttpRequestData r = new HttpRequestData("GET", "/", headers(null, null, null), null, ip, false);
                if (small.dispatcher.handle(r).status() == 429) limited++;
            }
            assertTrue(limited >= 25, "rotating addresses inside one /64 must not bypass the ip limit, limited " + limited);
            HttpRequestData other = new HttpRequestData("GET", "/", headers(null, null, null), null, "2001:db8:1:3::1", false);
            assertEquals(200, small.dispatcher.handle(other).status(), "another /64 is another client");
            assertEquals("2001:db8:1:2::/64", RateLimiter.clientKey("2001:0db8:0001:0002:ffff:0:0:1"));
            assertEquals("203.0.113.7", RateLimiter.clientKey("203.0.113.7"));
            assertEquals("203.0.113.7", RateLimiter.clientKey("::ffff:203.0.113.7"));
        }
    }

    @Test
    void givenFloodOfForeignOriginPosts_whenAudited_thenAnonymousDenialsAreCappedPerClient() throws Exception {
        for (int i = 0; i < 200; i++) {
            java.util.Map<String, List<String>> h = headers(null, null, "https://evil.example");
            h.put("content-type", List.of("application/json"));
            f.dispatcher.handle(request("POST", "/api/demo/change", h, "{}".getBytes(StandardCharsets.UTF_8)));
        }
        f.audit.flush(2000);
        long lines = Files.readAllLines(dir.resolve("web/audit.log")).stream().filter(l -> l.contains("detail=\"origin\"")).count();
        assertTrue(lines > 0 && lines <= 25, "anonymous denials must not flood (and rotate away) the audit log: " + lines);
    }

    @Test
    void givenQueryAndPathHelpers_whenParsed_thenStrict() {
        assertEquals(List.of("1", "2"), WebDispatcher.parseQuery("a=1&a=2").get("a"));
        assertEquals("x y", WebDispatcher.parseQuery("q=x+y").get("q").getFirst());
        assertThrows(WebException.class, () -> WebDispatcher.parseQuery("a=%zz"));
        assertThrows(WebException.class, () -> WebDispatcher.parseQuery("a=%00"));
        assertNull(WebDispatcher.Path.parse("/a/%2F/b"));
        assertNull(WebDispatcher.Path.parse("/a/../b"));
        assertEquals(List.of("a", "b c"), WebDispatcher.Path.parse("/a/b%20c").segments());
        assertTrue(WebDispatcher.jsonContentType("application/json; charset=UTF-8"));
        assertFalse(WebDispatcher.jsonContentType("application/json; charset=latin1"));
        assertFalse(WebDispatcher.jsonContentType("text/plain"));
    }
}
