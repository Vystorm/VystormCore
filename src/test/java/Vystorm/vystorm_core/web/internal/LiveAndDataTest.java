package Vystorm.vystorm_core.web.internal;

import static Vystorm.vystorm_core.web.internal.WebFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebRoute;
import Vystorm.vystorm_core.web.WebResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveAndDataTest {
    @TempDir Path dir;
    private WebFixture f;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        f = new WebFixture(dir, "live:\n  coalesce-ms: 20\n  max-per-player: 2\n  max-event-bytes: 1024\n");
        f.players.online.add(alice);
        f.register(WebModule.builder("skills", "Talente")
                .snapshot(ctx -> Map.of("character", character(ctx.playerName(), 1)))
                .route(WebRoute.get("/x", r -> WebResponse.json(Map.of())).build())
                .build());
        f.register(WebModule.builder("secret", "Geheim").permission("secret.view").build());
    }

    @AfterEach
    void tearDown() { f.close(); }

    private static JsonObject character(String name, int level) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("level", level);
        return o;
    }

    /** Liest Nachrichten, bis {@code count} erreicht ist oder 2 s nichts kommt. */
    private static List<JsonObject> read(ResponseStream stream, int count) throws InterruptedException {
        List<JsonObject> out = new ArrayList<>();
        while (out.size() < count) {
            String frame = stream.next(2000);
            if (frame == null || ResponseStream.END.equals(frame)) break;
            if (!frame.startsWith("data: ")) continue;
            out.add(JsonParser.parseString(frame.substring(6).trim()).getAsJsonObject());
        }
        return out;
    }

    private HttpResponseData connect(Login login, String query) {
        return f.dispatcher.handle(request("GET", "/core/live" + query, with(headers(login.cookie(), null, null), "sec-fetch-site", "same-origin"), null));
    }

    @Test
    void givenLiveConnection_whenOpened_thenSnapshotReadyAndCoalescedPushes() throws Exception {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = connect(login, "?modules=skills");
        assertEquals(200, r.status());
        assertEquals("text/event-stream; charset=utf-8", r.header("Content-Type"));
        assertEquals("no-store", r.header("Cache-Control"));
        assertNotNull(r.stream());
        assertEquals("retry: 3000\n\n", r.stream().next(1000));
        List<JsonObject> first = read(r.stream(), 2);
        assertEquals("event", first.get(0).get("kind").getAsString());
        assertTrue(first.get(0).get("snapshot").getAsBoolean());
        assertEquals(1, first.get(0).getAsJsonObject("data").get("level").getAsInt());
        assertEquals("ready", first.get(1).get("kind").getAsString());

        for (int level = 2; level <= 10; level++) assertTrue(f.live.publish(alice, "skills", "character", character("Alice", level)));
        f.live.publish(alice, "skills", "points", new JsonPrimitive(3));
        List<JsonObject> pushed = read(r.stream(), 2);
        assertEquals(2, pushed.size(), "nine character updates coalesced into one");
        assertEquals(10, pushed.get(0).getAsJsonObject("data").get("level").getAsInt());
        assertFalse(pushed.get(0).get("snapshot").getAsBoolean());
        assertEquals("points", pushed.get(1).get("type").getAsString());
        assertNull(r.stream().next(200), "nothing else");
        r.stream().close();
        assertEquals(0, f.live.connections());
    }

    @Test
    void givenOtherPlayersAndForeignModules_whenPushed_thenNotDelivered() throws Exception {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = connect(login, "?modules=skills");
        read(r.stream(), 3);
        assertFalse(f.live.publish(UUID.randomUUID(), "skills", "character", character("Bob", 5)), "Bob has no connection");
        f.live.publish(alice, "secret", "x", new JsonPrimitive(1));
        f.live.publish(alice, "core", "x", new JsonPrimitive(1));
        assertTrue(read(r.stream(), 1).isEmpty());
        r.stream().close();
    }

    @Test
    void givenModulePermissionMissing_whenPushed_thenFilteredAtDelivery() throws Exception {
        f.players.deny(alice, "secret.view");
        Login login = f.login(alice, "Alice");
        HttpResponseData r = connect(login, "");
        read(r.stream(), 3);
        f.live.publish(alice, "secret", "x", new JsonPrimitive(1));
        f.live.publish(alice, "skills", "y", new JsonPrimitive(2));
        List<JsonObject> got = read(r.stream(), 2);
        assertEquals(1, got.size());
        assertEquals("skills", got.getFirst().get("module").getAsString());
        r.stream().close();
    }

    @Test
    void givenLogout_whenLiveOpen_thenByeAndStreamEnds() throws Exception {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = connect(login, "");
        read(r.stream(), 3);
        assertEquals(204, f.dispatcher.handle(post("/core/logout", null, login.cookie(), login.csrf())).status());
        List<JsonObject> got = read(r.stream(), 1);
        assertEquals("bye", got.getFirst().get("kind").getAsString());
        assertEquals("logout", got.getFirst().get("reason").getAsString());
        assertEquals(ResponseStream.END, r.stream().next(1000));
    }

    @Test
    void givenLiveRequests_whenUnauthenticatedCrossSiteOrOverLimit_thenRejected() throws Exception {
        assertEquals(401, f.dispatcher.handle(get("/core/live", null)).status());
        Login login = f.login(alice, "Alice");
        var cross = with(headers(login.cookie(), null, null), "sec-fetch-site", "cross-site");
        assertEquals(403, f.dispatcher.handle(request("GET", "/core/live", cross, null)).status());
        var evil = with(headers(login.cookie(), null, "https://evil.example"), "sec-fetch-site", "same-origin");
        assertEquals(403, f.dispatcher.handle(request("GET", "/core/live", evil, null)).status());
        assertEquals(400, connect(login, "?modules=../x").status());
        HttpResponseData a = connect(login, ""), b = connect(login, "");
        assertEquals(429, connect(login, "").status(), "max-per-player 2");
        a.stream().close();
        b.stream().close();
        assertEquals(405, f.dispatcher.handle(post("/core/live", "{}", login.cookie(), login.csrf())).status());
    }

    @Test
    void givenTooLargeOrInvalidEvent_whenPublished_thenRejected() throws Exception {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = connect(login, "");
        assertThrows(IllegalArgumentException.class, () -> f.live.publish(alice, "skills", "big", new JsonPrimitive("x".repeat(2000))));
        assertThrows(IllegalArgumentException.class, () -> f.live.publish(alice, "skills", "Bad Type", new JsonPrimitive(1)));
        r.stream().close();
    }

    @Test
    void givenPublishedData_whenFetched_thenVersionedImmutableAndLatestRevalidated() throws Exception {
        JsonObject tree = new JsonObject();
        tree.addProperty("nodes", 307);
        String url = f.modules.publishData(f.owner, "skills", "tree", tree).url();
        assertTrue(url.matches("/data/skills/tree\\.[A-Za-z0-9_-]{12}\\.json"), url);
        HttpResponseData versioned = f.dispatcher.handle(get(url, null));
        assertEquals(200, versioned.status());
        assertEquals("public, max-age=31536000, immutable", versioned.header("Cache-Control"));
        assertEquals("{\"nodes\":307}", versioned.bodyText());
        String etag = versioned.header("ETag");
        HttpResponseData latest = f.dispatcher.handle(get("/data/skills/tree.json", null));
        assertEquals("public, no-cache", latest.header("Cache-Control"));
        assertEquals(304, f.dispatcher.handle(request("GET", "/data/skills/tree.json", with(headers(null, null, null), "if-none-match", etag), null)).status());
        assertEquals(404, f.dispatcher.handle(get("/data/skills/tree.AAAAAAAAAAAA.json", null)).status(), "old version gone");
        assertEquals(404, f.dispatcher.handle(get("/data/skills/nope.json", null)).status());

        Login login = f.login(alice, "Alice");
        String session = f.dispatcher.handle(get("/core/session", login.cookie())).bodyText();
        assertTrue(session.contains(url), session);
        assertTrue(session.contains("\"live\":true"));
    }

    @Test
    void givenDataOfPermissionModule_whenFetchedWithoutSession_thenUnauthorizedAndPrivate() throws Exception {
        f.modules.publishData(f.owner, "secret", "layout", new JsonPrimitive(1));
        assertEquals(401, f.dispatcher.handle(get("/data/secret/layout.json", null)).status());
        Login login = f.login(alice, "Alice");
        HttpResponseData ok = f.dispatcher.handle(get("/data/secret/layout.json", login.cookie()));
        assertEquals(200, ok.status());
        assertTrue(ok.header("Cache-Control").startsWith("private"));
        f.players.deny(alice, "secret.view");
        assertEquals(403, f.dispatcher.handle(get("/data/secret/layout.json", login.cookie())).status());
    }

    @Test
    void givenDataOfForeignModule_whenPublished_thenRejected() {
        assertThrows(IllegalStateException.class, () -> f.modules.publishData(plugin("Other"), "skills", "tree", new JsonPrimitive(1)));
        assertThrows(IllegalArgumentException.class, () -> f.modules.publishData(f.owner, "skills", "Bad Name", new JsonPrimitive(1)));
    }

    @Test
    void givenNewDataVersion_whenLiveOpen_thenDataMessagePushed() throws Exception {
        Login login = f.login(alice, "Alice");
        HttpResponseData r = connect(login, "?modules=skills");
        read(r.stream(), 3);
        String url = f.modules.publishData(f.owner, "skills", "tree", new JsonPrimitive(2)).url();
        List<JsonObject> got = read(r.stream(), 1);
        assertEquals("data", got.getFirst().get("kind").getAsString());
        assertEquals(url, got.getFirst().get("url").getAsString());
        f.modules.publishData(f.owner, "skills", "tree", new JsonPrimitive(2));
        assertTrue(read(r.stream(), 1).isEmpty(), "same content, no message");
        r.stream().close();
    }

    @Test
    void givenBuiltUi_whenPresent_thenSpaRoutesAndImmutableAssets() throws Exception {
        Map<String, StaticAssets.Asset> ui = Map.of(
                "index.html", StaticAssets.asset("<!doctype html><div id=app></div>".getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"),
                "assets/index-Ab12.js", StaticAssets.asset("app()".getBytes(StandardCharsets.UTF_8), "text/javascript; charset=utf-8"),
                "favicon.svg", StaticAssets.asset("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml"));
        try (WebFixture withUi = new WebFixture(dir.resolve("ui"), "", ui)) {
            for (String path : List.of("/", "/login", "/app/skills", "/app/profile/x")) {
                HttpResponseData r = withUi.dispatcher.handle(get(path, null));
                assertEquals(200, r.status(), path);
                assertTrue(r.bodyText().contains("id=app"), path);
                assertEquals("no-cache", r.header("Cache-Control"));
            }
            HttpResponseData js = withUi.dispatcher.handle(get("/assets/index-Ab12.js", null));
            assertEquals("public, max-age=31536000, immutable", js.header("Cache-Control"));
            assertEquals(200, withUi.dispatcher.handle(get("/favicon.svg", null)).status());
            assertEquals(404, withUi.dispatcher.handle(get("/assets/missing.js", null)).status());
            assertEquals(401, withUi.dispatcher.handle(get("/core/session", null)).status(), "core routes unaffected");
            withUi.register(WebModule.builder("skills", "Talente").build());
            String token = withUi.sessions.issueLoginToken(alice, "Alice", "skills");
            HttpResponseData login = withUi.dispatcher.handle(post("/core/login", "{\"token\":\"" + token + "\"}", null, null));
            assertEquals("/app/skills", JsonParser.parseString(login.bodyText()).getAsJsonObject().get("redirect").getAsString());
        }
    }
}
