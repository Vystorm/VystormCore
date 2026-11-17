package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Startet den eingebetteten Server auf einem freien Port und treibt ihn mit HttpClient und rohen Sockets. */
class EmbeddedHttpServerTest {
    @TempDir Path dir;
    private WebFixture f;
    private EmbeddedHttpServer server;
    private int port;
    private String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        f = new WebFixture(dir, "embedded:\n  port: " + port + "\n  max-connections-per-ip: 4\n  trusted-proxies: [10.0.0.0/8, \"::1\"]\n"
                + "limits:\n  header-timeout-seconds: 1\n  idle-timeout-seconds: 1\n  write-timeout-seconds: 1\n  max-body-bytes: 4096\n  max-header-bytes: 4096\n  max-headers: 20\n");
        f.register(CoreWebModule.create());
        server = new EmbeddedHttpServer(f.settings, f.dispatcher::handle, Logger.getLogger("test"));
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void tearDown() {
        server.close();
        f.close();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void givenRunningServer_whenFullLoginFlowDriven_thenSessionApiAndCsrfWork() throws Exception {
        UUID player = UUID.randomUUID();
        HttpResponse<String> shell = send(HttpRequest.newBuilder(URI.create(base + "/")));
        assertEquals(200, shell.statusCode());
        assertEquals("nosniff", shell.headers().firstValue("x-content-type-options").orElseThrow());
        assertTrue(shell.headers().firstValue("content-security-policy").orElseThrow().contains("script-src 'self'"));

        HttpResponse<String> page = send(HttpRequest.newBuilder(URI.create(base + "/m/core/")));
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<script src=\"app.js\"></script>"), "profile page from resources");
        assertFalse(page.body().contains("<script>"), "no inline scripts");

        assertEquals(401, send(HttpRequest.newBuilder(URI.create(base + "/core/session"))).statusCode());

        String token = f.sessions.issueLoginToken(player, "Steve", "core");
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(base + "/core/login"))
                .header("Origin", base).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")));
        assertEquals(200, login.statusCode(), login.body());
        String setCookie = login.headers().firstValue("set-cookie").orElseThrow();
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        String csrf = JsonParser.parseString(login.body()).getAsJsonObject().get("csrf").getAsString();
        assertEquals("/m/core/", JsonParser.parseString(login.body()).getAsJsonObject().get("redirect").getAsString());

        HttpResponse<String> session = send(HttpRequest.newBuilder(URI.create(base + "/core/session")).header("Cookie", cookie));
        assertEquals(200, session.statusCode());
        assertTrue(session.body().contains("\"player\":\"Steve\""));
        assertTrue(session.body().contains("\"id\":\"core\""));

        HttpResponse<String> profile = send(HttpRequest.newBuilder(URI.create(base + "/api/core/profile")).header("Cookie", cookie));
        assertEquals(200, profile.statusCode(), profile.body());
        assertTrue(profile.body().contains("\"online\":false"));

        HttpResponse<String> noCsrf = send(HttpRequest.newBuilder(URI.create(base + "/api/core/echo")).header("Cookie", cookie)
                .header("Origin", base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}")));
        assertEquals(403, noCsrf.statusCode());
        HttpResponse<String> echo = send(HttpRequest.newBuilder(URI.create(base + "/api/core/echo")).header("Cookie", cookie)
                .header("Origin", base).header("X-Vystorm-CSRF", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}")));
        assertEquals(200, echo.statusCode(), echo.body());
        assertTrue(echo.body().contains("\"echo\":\"hi\""));
    }

    @Test
    void givenLiveStream_whenEventPushed_thenBrowserReceivesItWithoutPolling() throws Exception {
        UUID player = UUID.randomUUID();
        String token = f.sessions.issueLoginToken(player, "Steve", null);
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(base + "/core/login"))
                .header("Origin", base).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")));
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        HttpResponse<java.util.stream.Stream<String>> live = http.send(HttpRequest.newBuilder(URI.create(base + "/core/live?modules=core"))
                .header("Cookie", cookie).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, live.statusCode());
        assertEquals("text/event-stream; charset=utf-8", live.headers().firstValue("content-type").orElseThrow());
        var lines = live.body().iterator();
        java.util.List<String> seen = new java.util.ArrayList<>();
        while (lines.hasNext()) {
            String line = lines.next();
            seen.add(line);
            if (line.contains("\"kind\":\"ready\"")) break;
        }
        assertTrue(seen.stream().anyMatch(l -> l.contains("\"type\":\"status\"") && l.contains("\"snapshot\":true")), seen.toString());
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("level", 42);
        assertTrue(f.live.publish(player, "core", "status", data));
        String pushed = null;
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.startsWith("data: ")) { pushed = line; break; }
        }
        assertNotNull(pushed);
        assertTrue(pushed.contains("\"level\":42") && pushed.contains("\"snapshot\":false"), pushed);
        live.body().close();
    }

    // ------------------------------------------------------------ Rohe Sockets

    private static String raw(int port, String request, int readTimeoutMs) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private String host() { return "Host: 127.0.0.1:" + port + "\r\n"; }

    @Test
    void givenOversizedOrTooManyHeaders_whenSent_then431() throws Exception {
        String big = raw(port, "GET / HTTP/1.1\r\n" + host() + "X-Big: " + "a".repeat(5000) + "\r\n\r\n", 5000);
        assertTrue(big.startsWith("HTTP/1.1 431"), big);
        StringBuilder many = new StringBuilder("GET / HTTP/1.1\r\n" + host());
        for (int i = 0; i < 25; i++) many.append("X-H").append(i).append(": v\r\n");
        assertTrue(raw(port, many + "\r\n", 5000).startsWith("HTTP/1.1 431"));
        assertTrue(raw(port, "GET /" + "a".repeat(9000) + " HTTP/1.1\r\n" + host() + "\r\n", 5000).startsWith("HTTP/1.1 414"));
    }

    @Test
    void givenLargeBodyOrChunkedOrAmbiguousLength_whenSent_thenRejected() throws Exception {
        assertTrue(raw(port, "POST /core/login HTTP/1.1\r\n" + host() + "Content-Length: 999999\r\n\r\n", 5000).startsWith("HTTP/1.1 413"));
        assertTrue(raw(port, "POST /core/login HTTP/1.1\r\n" + host() + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 5000).startsWith("HTTP/1.1 501"));
        assertTrue(raw(port, "POST /core/login HTTP/1.1\r\n" + host() + "Content-Length: 2\r\nContent-Length: 3\r\n\r\nabc", 5000).startsWith("HTTP/1.1 400"));
        assertTrue(raw(port, "POST /core/login HTTP/1.1\r\n" + host() + "Content-Length: -1\r\n\r\n", 5000).startsWith("HTTP/1.1 400"));
    }

    @Test
    void givenMalformedFraming_whenSent_then400() throws Exception {
        assertTrue(raw(port, "GET / HTTP/1.1\n" + host() + "\r\n", 5000).startsWith("HTTP/1.1 400"), "bare LF");
        assertTrue(raw(port, "GET / HTTP/1.1\r\n" + host() + "X-A: 1\r\n folded\r\n\r\n", 5000).startsWith("HTTP/1.1 400"), "obs-fold");
        assertTrue(raw(port, "GET / HTTP/1.1\r\n" + host() + "Bad Name: 1\r\n\r\n", 5000).startsWith("HTTP/1.1 400"));
        assertTrue(raw(port, "GET / HTTP/1.1\r\n\r\n", 5000).startsWith("HTTP/1.1 400"), "missing Host");
        assertTrue(raw(port, "GET http://evil/ HTTP/1.1\r\n" + host() + "\r\n", 5000).startsWith("HTTP/1.1 400"), "absolute form");
        assertTrue(raw(port, "GET / HTTP/2.0\r\n" + host() + "\r\n", 5000).startsWith("HTTP/1.1 505"));
        assertTrue(raw(port, "GET / HTTP/1.1\r\nHost: evil.example\r\n\r\n", 5000).startsWith("HTTP/1.1 421"));
    }

    @Test
    void givenSlowlorisClient_whenHeadersNeverFinish_thenTimeoutAndClose() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\n" + host()).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            long start = System.nanoTime();
            // Tröpfeln: ein Header-Byte alle 300 ms verlängert die Frist nicht.
            boolean closed = false;
            try {
                for (int i = 0; i < 20; i++) { out.write('X'); out.flush(); Thread.sleep(300); }
            } catch (java.io.IOException reset) {
                closed = true;
            }
            String answer = closed ? "" : new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(closed || answer.startsWith("HTTP/1.1 408"), answer);
            assertTrue(ms < 5500, "closed after " + ms + " ms");
        }
    }

    @Test
    void givenIdleConnection_whenNothingSent_thenClosed() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            long start = System.nanoTime();
            assertEquals(-1, socket.getInputStream().read());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 4000);
        }
    }

    @Test
    void givenKeepAlive_whenTwoRequestsOnOneConnection_thenBothAnswered() throws Exception {
        String req = "GET /core/web.js HTTP/1.1\r\n" + host() + "\r\n";
        String answer = raw(port, req + "GET /core/web.js HTTP/1.1\r\n" + host() + "Connection: close\r\n\r\n", 5000);
        assertEquals(2, answer.split("HTTP/1.1 200", -1).length - 1, answer);
        assertTrue(raw(port, "GET /core/web.js HTTP/1.0\r\n\r\n", 5000).contains("Connection: close"));
    }

    @Test
    void givenManyConnectionsFromOneIp_whenOverLimit_then503() throws Exception {
        List<Socket> held = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Socket s = new Socket("127.0.0.1", port);
                s.getOutputStream().write('G');
                held.add(s);
            }
            Thread.sleep(200);
            String answer = raw(port, "GET / HTTP/1.1\r\n" + host() + "\r\n", 5000);
            assertTrue(answer.startsWith("HTTP/1.1 503"), answer);
        } finally {
            for (Socket s : held) s.close();
        }
    }

    @Test
    void givenForwardedFor_whenFromTrustedProxy_thenRightmostUntrustedIpUsed() {
        assertEquals("203.0.113.9", server.forwardedFor(List.of("198.51.100.1, 203.0.113.9, 10.1.2.3"), "10.0.0.1"));
        assertEquals("203.0.113.9", server.forwardedFor(List.of("203.0.113.9"), "10.0.0.1"));
        assertEquals("10.0.0.1", server.forwardedFor(List.of("not-an-ip"), "10.0.0.1"), "garbage ignored");
        assertEquals("10.0.0.1", server.forwardedFor(List.of("evil.example.com"), "10.0.0.1"), "no DNS lookups");
        assertEquals("10.0.0.1", server.forwardedFor(null, "10.0.0.1"));
        assertNull(EmbeddedHttpServer.literal("localhost"));
        assertTrue(EmbeddedHttpServer.Cidr.parse("10.0.0.0/8").contains(EmbeddedHttpServer.literal("10.200.1.1")));
        assertFalse(EmbeddedHttpServer.Cidr.parse("10.0.0.0/8").contains(EmbeddedHttpServer.literal("11.0.0.1")));
        assertTrue(EmbeddedHttpServer.Cidr.parse("fd7a:115c::/32").contains(EmbeddedHttpServer.literal("fd7a:115c:a1e0::1")));
        assertNull(EmbeddedHttpServer.Cidr.parse("10.0.0.0/33"));
    }

    @Test
    void givenClientFromUntrustedAddress_whenSendingForwardedFor_thenIgnored() throws Exception {
        // 127.0.0.1 ist nicht in trusted-proxies: X-Forwarded-For darf die IP nicht ändern (sichtbar im Audit-Log).
        String token = f.sessions.issueLoginToken(UUID.randomUUID(), "Alex", null);
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(base + "/core/login"))
                .header("Origin", base).header("Content-Type", "application/json").header("X-Forwarded-For", "6.6.6.6")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")));
        assertEquals(200, login.statusCode());
        f.audit.flush(2000);
        String log = java.nio.file.Files.readString(dir.resolve("web/audit.log"));
        assertTrue(log.contains("ip=127.0.0.1"), log);
        assertFalse(log.contains("6.6.6.6"));
    }

    @Test
    void givenSlowReader_whenResponsesNeverConsumed_thenConnectionClosedAfterWriteTimeout() throws Exception {
        // Slow-Read: pipelined Anfragen auf eine große Datei, der Client liest nie. Ohne Schreib-Timeout blockiert der
        // Verbindungs-Thread für immer im write() und hält den Verbindungsplatz (Limit je IP/gesamt → DoS).
        f.register(Vystorm.vystorm_core.web.WebModule.builder("big", "Big").asset("big.js", new byte[1024 * 1024]).build());
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(4096);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            OutputStream out = socket.getOutputStream();
            String request = "GET /m/big/big.js HTTP/1.1\r\n" + host() + "\r\n";
            for (int i = 0; i < 20; i++) out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long ready = System.nanoTime() + 3_000_000_000L;
            while (server.openConnections() == 0 && System.nanoTime() < ready) Thread.sleep(20);
            assertEquals(1, server.openConnections(), "connection is being served");
            long deadline = System.nanoTime() + 8_000_000_000L;
            while (server.openConnections() > 0 && System.nanoTime() < deadline) Thread.sleep(100);
            assertEquals(0, server.openConnections(), "stalled writer must be closed by the write timeout");
        }
    }
}
