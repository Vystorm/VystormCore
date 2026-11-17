package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import Vystorm.vystorm_core.web.WebModule;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ende-zu-Ende gegen das echte Node.js-Gateway (Repo vystorm-web-gateway): Javas {@link GatewayTunnel} verbindet sich
 * mit dem Gateway, HttpClient spricht als Browser mit dem Gateway. Läuft nur, wenn {@code VYSTORM_GATEWAY_DIR} auf ein
 * Gateway-Checkout mit {@code node_modules} zeigt und {@code node} verfügbar ist; sonst übersprungen.
 */
class GatewayInteropTest {
    private static final String SECRET = "interop-secret-" + "z".repeat(40);
    @TempDir Path dir;
    private Process node;
    private WebFixture f;
    private GatewayTunnel tunnel;
    private String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() throws Exception {
        String gatewayDir = System.getenv("VYSTORM_GATEWAY_DIR");
        assumeTrue(gatewayDir != null && Files.isDirectory(Path.of(gatewayDir, "node_modules", "ws")), "VYSTORM_GATEWAY_DIR not set");
        ProcessBuilder builder = new ProcessBuilder("node", "bin/vystorm-gateway.js").directory(Path.of(gatewayDir).toFile()).redirectErrorStream(true);
        builder.environment().put("VYSTORM_GATEWAY_SECRET", SECRET);
        builder.environment().put("VYSTORM_GATEWAY_PORT", "0");
        builder.environment().put("VYSTORM_GATEWAY_HOST", "127.0.0.1");
        builder.environment().put("VYSTORM_GATEWAY_TUNNEL_PORT", "0");
        node = builder.start();
        BufferedReader out = new BufferedReader(new InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8));
        int[] ports = CompletableFuture.supplyAsync(() -> {
            int[] found = {-1, -1};
            try {
                for (String line; (found[0] < 0 || found[1] < 0) && (line = out.readLine()) != null; ) {
                    var m = java.util.regex.Pattern.compile("(listening|tunnel) on [0-9.]+:(\\d+)").matcher(line);
                    if (m.find()) found[m.group(1).equals("listening") ? 0 : 1] = Integer.parseInt(m.group(2));
                }
            } catch (java.io.IOException ignored) { }
            return found;
        }).get(15, TimeUnit.SECONDS);
        int port = ports[0];
        assertTrue(port > 0 && ports[1] > 0, "gateway did not start");
        Thread.ofVirtual().start(() -> { try { while (out.readLine() != null) { } } catch (java.io.IOException ignored) { } });
        base = "http://127.0.0.1:" + port;
        f = new WebFixture(dir, "mode: gateway\npublic-url: " + base + "\ngateway:\n  url: ws://127.0.0.1:" + ports[1] + "/_vystorm/tunnel\n  secret: " + SECRET + "\n");
        assertTrue(f.settings.problems().isEmpty(), f.settings.problems().toString());
        f.register(CoreWebModule.create());
        tunnel = new GatewayTunnel(f.settings, f.dispatcher::handle, f.modules::revision, Logger.getLogger("test"), f.audit);
        f.modules.onChange(tunnel::assetsChanged);
        tunnel.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!tunnel.connected() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(tunnel.connected(), "tunnel state " + tunnel.state());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tunnel != null) tunnel.close();
        if (f != null) f.close();
        if (node != null) { node.destroy(); node.waitFor(5, TimeUnit.SECONDS); node.destroyForcibly(); }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void givenRealGateway_whenBrowserLogsInThroughTunnel_thenSessionCsrfAndApiWork() throws Exception {
        HttpResponse<String> shell = send(HttpRequest.newBuilder(URI.create(base + "/")));
        assertEquals(200, shell.statusCode());
        assertTrue(shell.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors 'none'"));

        String token = f.sessions.issueLoginToken(UUID.randomUUID(), "Tunnelmann", "core");
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(base + "/core/login")).header("Origin", base)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")));
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        String csrf = JsonParser.parseString(login.body()).getAsJsonObject().get("csrf").getAsString();

        HttpResponse<String> replay = send(HttpRequest.newBuilder(URI.create(base + "/core/login")).header("Origin", base)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")));
        assertEquals(401, replay.statusCode(), "single use through the tunnel");

        HttpResponse<String> profile = send(HttpRequest.newBuilder(URI.create(base + "/api/core/profile")).header("Cookie", cookie));
        assertEquals(200, profile.statusCode(), profile.body());
        assertTrue(profile.body().contains("Tunnelmann"));

        HttpResponse<String> forged = send(HttpRequest.newBuilder(URI.create(base + "/api/core/echo")).header("Cookie", cookie).header("Origin", base)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"x\"}")));
        assertEquals(403, forged.statusCode());
        HttpResponse<String> echo = send(HttpRequest.newBuilder(URI.create(base + "/api/core/echo")).header("Cookie", cookie).header("Origin", base)
                .header("X-Vystorm-CSRF", csrf).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"über den Tunnel\"}")));
        assertEquals(200, echo.statusCode(), echo.body());
        assertTrue(echo.body().contains("über den Tunnel"));

        f.audit.flush(2000);
        String log = Files.readString(dir.resolve("web/audit.log"));
        assertTrue(log.contains("event=tunnel") && log.contains("connected"), log);
        assertTrue(log.contains("ip=127.0.0.1"));
    }

    @Test
    void givenLiveChannelThroughGateway_whenCorePushes_thenBrowserReceivesAndDisconnectReleasesSubscription() throws Exception {
        UUID player = UUID.randomUUID();
        String token = f.sessions.issueLoginToken(player, "Livemann", null);
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(base + "/core/login")).header("Origin", base)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")));
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        HttpResponse<java.util.stream.Stream<String>> live = http.send(HttpRequest.newBuilder(URI.create(base + "/core/live?modules=core"))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, live.statusCode());
        var lines = live.body().iterator();
        while (lines.hasNext() && !lines.next().contains("\"kind\":\"ready\"")) { }
        assertEquals(1, f.live.connections());
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("level", 7);
        assertTrue(f.live.publish(player, "core", "status", data));
        String pushed = null;
        while (lines.hasNext()) { String line = lines.next(); if (line.startsWith("data: ")) { pushed = line; break; } }
        assertNotNull(pushed);
        assertTrue(pushed.contains("\"level\":7"), pushed);
        live.body().close();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (f.live.connections() > 0 && System.nanoTime() < deadline) Thread.sleep(50);
        assertEquals(0, f.live.connections(), "gateway cancel closes the Core subscription");
    }

    @Test
    void givenModuleAssetsChange_whenCoreReportsRevision_thenGatewayCacheServesNewContent() throws Exception {
        f.register(WebModule.builder("demo", "Demo").asset("app.js", "v1()".getBytes(StandardCharsets.UTF_8)).build());
        assertEquals("v1()", send(HttpRequest.newBuilder(URI.create(base + "/m/demo/app.js"))).body());
        assertEquals("v1()", send(HttpRequest.newBuilder(URI.create(base + "/m/demo/app.js"))).body());
        f.register(WebModule.builder("demo", "Demo").asset("app.js", "v2()".getBytes(StandardCharsets.UTF_8)).build());
        Thread.sleep(200);
        assertEquals("v2()", send(HttpRequest.newBuilder(URI.create(base + "/m/demo/app.js"))).body());
    }

    @Test
    void givenWrongSecret_whenSecondCoreConnects_thenRejectedAndFirstTunnelStays() throws Exception {
        WebSettings wrong = WebFixture.settings("enabled: true\nmode: gateway\npublic-url: " + base + "\ngateway:\n  url: "
                + f.settings.gatewayUrl() + "\n  secret: " + "w".repeat(48) + "\n");
        GatewayTunnel intruder = new GatewayTunnel(wrong, f.dispatcher::handle, () -> 0, Logger.getLogger("test"), f.audit);
        try {
            intruder.start();
            Thread.sleep(2500);
            assertFalse(intruder.connected());
            assertTrue(tunnel.connected());
            assertEquals(200, send(HttpRequest.newBuilder(URI.create(base + "/"))).statusCode());
        } finally {
            intruder.close();
        }
    }

    @Test
    void givenTunnelClosed_whenBrowserRequests_thenGatewayAnswers503() throws Exception {
        tunnel.close();
        Thread.sleep(300);
        assertEquals(503, send(HttpRequest.newBuilder(URI.create(base + "/"))).statusCode());
    }
}
