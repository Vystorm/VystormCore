package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.CoreApi;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ausgehender, authentifizierter WebSocket-Tunnel zum externen Node.js-Gateway ({@code web-gateway/}). Das Gateway
 * reicht Browser-Anfragen als {@code req}-Nachrichten durch; Core bearbeitet sie mit demselben {@link WebDispatcher} wie
 * im eingebetteten Modus und antwortet mit {@code res}. Protokoll und Kryptografie: {@link TunnelProtocol}.
 *
 * <p>Neuverbindung mit exponentiellem Backoff (1 s bis 60 s, Jitter), Heartbeat alle 15 s, Abbruch nach 45 s Stille,
 * höchstens {@code max-in-flight} gleichzeitige Anfragen (sonst sofort 503), Nachrichten über der Größengrenze beenden
 * die Verbindung. Core-intern.
 *
 * @since 0.17.0
 */
public final class GatewayTunnel implements AutoCloseable {
    static final long HEARTBEAT_MS = 15_000;
    static final long SILENCE_MS = 45_000;
    static final long WELCOME_TIMEOUT_MS = 10_000;
    static final long MAX_BACKOFF_MS = 60_000;
    private static final Gson GSON = new Gson();

    private final WebSettings settings;
    private final Function<HttpRequestData, HttpResponseData> handler;
    private final LongSupplier revision;
    private final Logger logger;
    private final AuditLog audit;
    private final byte[] secret;
    private final int maxIncomingChars;
    private final Semaphore inFlight;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Vystorm-Web-Tunnel");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client;
    private volatile boolean running;
    private volatile Connection current;
    private volatile long backoffMs = 1000;
    private volatile String state = "stopped";
    private volatile int failures;
    private ScheduledFuture<?> heartbeat;

    public GatewayTunnel(WebSettings settings, Function<HttpRequestData, HttpResponseData> handler, LongSupplier revision, Logger logger, AuditLog audit) {
        this.settings = settings;
        this.handler = handler;
        this.revision = revision;
        this.logger = logger;
        this.audit = audit;
        this.secret = settings.gatewaySecret().getBytes(StandardCharsets.UTF_8);
        this.maxIncomingChars = settings.maxBodyBytes() * 2 + settings.maxHeaderBytes() * 2 + 64 * 1024;
        this.inFlight = new Semaphore(settings.gatewayMaxInFlight());
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        state = "connecting";
        heartbeat = timer.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        timer.execute(this::connect);
    }

    /** Ob der Tunnel authentifiziert und bereit ist. */
    public boolean connected() { Connection c = current; return c != null && c.ready; }

    public String state() { return state; }

    /** Teilt dem Gateway mit, dass sich Moduldateien geändert haben (Cache leeren). */
    public void assetsChanged() {
        Connection c = current;
        if (c == null || !c.ready) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "assets");
        o.addProperty("rev", revision.getAsLong());
        c.send(o);
    }

    @Override public synchronized void close() {
        running = false;
        state = "stopped";
        if (heartbeat != null) heartbeat.cancel(false);
        Connection c = current;
        current = null;
        if (c != null) c.close(WebSocket.NORMAL_CLOSURE, "core stopping");
        timer.shutdownNow();
        try { client.shutdownNow(); } catch (RuntimeException ignored) { }
    }

    // ---------------------------------------------------------------- Verbindung

    private void connect() {
        if (!running) return;
        long ts = System.currentTimeMillis();
        String nonce = SessionStore.randomToken();
        Connection connection = new Connection(nonce);
        state = "connecting";
        try {
            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("X-Vystorm-Tunnel", "1")
                    .header("X-Vystorm-Ts", Long.toString(ts))
                    .header("X-Vystorm-Nonce", nonce)
                    .header("X-Vystorm-Signature", TunnelProtocol.helloSignature(secret, ts, nonce))
                    .buildAsync(URI.create(settings.gatewayUrl()), connection)
                    .whenComplete((ws, failure) -> {
                        if (failure != null) {
                            state = "unreachable";
                            // Erste Fehlschläge und danach jeden zehnten als Warnung, sonst nur FINE (kein Log-Spam).
                            int n = ++failures;
                            log(n <= 3 || n % 10 == 0 ? Level.WARNING : Level.FINE, "[Web] Gateway not reachable (" + rootMessage(failure)
                                    + "); retrying in " + (backoffMs / 1000) + " s" + (n > 3 ? " (attempt " + n + ")" : ""));
                            scheduleReconnect();
                        }
                    });
        } catch (RuntimeException failure) {
            state = "failed";
            log(Level.WARNING, "[Web] Gateway connection failed: " + failure.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        long delay = backoffMs;
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        long jitter = (long) (delay * 0.2 * ThreadLocalRandom.current().nextDouble());
        try { timer.schedule(this::connect, delay + jitter, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { /* herunterfahren */ }
    }

    private void heartbeat() {
        Connection c = current;
        if (c == null || !c.ready) return;
        if (System.currentTimeMillis() - c.lastReceived > SILENCE_MS) {
            log(Level.WARNING, "[Web] Gateway silent for " + (SILENCE_MS / 1000) + " s; reconnecting");
            c.abort("silence");
            return;
        }
        JsonObject ping = new JsonObject();
        ping.addProperty("t", "ping");
        ping.addProperty("ts", System.currentTimeMillis());
        c.send(ping);
    }

    private void log(Level level, String message) { if (logger != null) logger.log(level, message); }

    private static String rootMessage(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** Ergebnis von {@link #parseRequest}: die Anfrage, oder ein HTTP-Status (413/414/431), mit dem Core selbst antwortet. */
    record ParsedRequest(HttpRequestData data, int status) {}

    /**
     * Liest eine {@code req}-Nachricht. Übergrößen (Ziel, Header-Anzahl, Körper) sind Browser-Eingaben und werden mit
     * einem Status beantwortet; nur ein kaputtes Format des (authentisierten) Gateways gilt als Protokollverletzung.
     *
     * @throws RuntimeException bei kaputtem Format (Tunnel wird beendet)
     */
    static ParsedRequest parseRequest(JsonObject o, WebSettings settings) {
        String method = o.get("method").getAsString();
        if (!method.matches("[A-Z]{1,16}")) throw new IllegalStateException("bad method");
        String path = o.get("path").getAsString();
        if (path.length() > WebDispatcher.MAX_TARGET * 2) return new ParsedRequest(null, 414);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        JsonObject h = o.getAsJsonObject("headers");
        if (h != null) {
            if (h.size() > settings.maxHeaders()) return new ParsedRequest(null, 431);
            for (Map.Entry<String, JsonElement> e : h.entrySet()) {
                List<String> values = new ArrayList<>();
                if (e.getValue().isJsonArray()) for (JsonElement v : e.getValue().getAsJsonArray()) values.add(v.getAsString());
                else values.add(e.getValue().getAsString());
                headers.put(e.getKey().toLowerCase(Locale.ROOT), List.copyOf(values));
            }
        }
        String ip = o.has("ip") ? o.get("ip").getAsString() : "?";
        var literal = EmbeddedHttpServer.literal(ip);
        ip = literal == null ? "?" : literal.getHostAddress();
        boolean secure = o.has("secure") && o.get("secure").getAsBoolean();
        byte[] body = new byte[0];
        if (o.has("body")) {
            String b64 = o.get("body").getAsString();
            if (b64.length() > (settings.maxBodyBytes() / 3 + 1) * 4 + 4) return new ParsedRequest(null, 413);
            body = Base64.getDecoder().decode(b64);
            if (body.length > settings.maxBodyBytes()) return new ParsedRequest(null, 413);
        }
        return new ParsedRequest(new HttpRequestData(method, path, headers, body, ip, secure), 0);
    }

    /** Eine WebSocket-Verbindung mit eigenem Schlüssel und eigenen Sequenzzählern. */
    private final class Connection implements WebSocket.Listener {
        private final String coreNonce;
        private final StringBuilder buffer = new StringBuilder();
        private final Object sendLock = new Object();
        private final Map<Long, ResponseStream> streams = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile WebSocket ws;
        private volatile boolean ready;
        private volatile boolean closed;
        private byte[] key;
        private long sendSeq;
        private long recvSeq;
        private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
        private volatile long lastReceived = System.currentTimeMillis();
        private ScheduledFuture<?> welcomeTimeout;

        Connection(String coreNonce) { this.coreNonce = coreNonce; }

        @Override public void onOpen(WebSocket webSocket) {
            this.ws = webSocket;
            state = "handshake";
            welcomeTimeout = timer.schedule(() -> { if (!ready) abort("no welcome"); }, WELCOME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            webSocket.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (closed) return null;
            if (buffer.length() + data.length() > maxIncomingChars) { abort("frame too large"); return null; }
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                lastReceived = System.currentTimeMillis();
                try { onFrame(frame); }
                catch (RuntimeException violation) { abort("protocol violation: " + violation.getMessage()); return null; }
            }
            if (!closed) webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            abort("binary frame");
            return null;
        }

        @Override public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
            lastReceived = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
            lastReceived = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ended("closed by gateway (" + statusCode + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")");
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) { ended("error: " + rootMessage(error)); }

        private void onFrame(String frame) {
            if (key == null) { welcome(frame); return; }
            JsonObject o = TunnelProtocol.openFrame(key, TunnelProtocol.G2C, frame, recvSeq + 1);
            recvSeq++;
            switch (o.get("t").getAsString()) {
                case "req" -> request(o);
                case "cancel" -> { ResponseStream stream = streams.remove(o.get("id").getAsLong()); if (stream != null) stream.close(); }
                case "ping" -> { JsonObject pong = new JsonObject(); pong.addProperty("t", "pong"); send(pong); }
                default -> { /* pong und künftige Typen: nur Lebenszeichen */ }
            }
        }

        private void welcome(String frame) {
            JsonObject o;
            try { o = JsonParser.parseString(frame).getAsJsonObject(); }
            catch (RuntimeException malformed) { throw new IllegalStateException("malformed welcome"); }
            try {
                key = TunnelProtocol.verifyWelcome(secret, coreNonce, o, System.currentTimeMillis(), settings.gatewayClockSkewMs());
            } catch (IllegalStateException denied) {
                audit.log("tunnel", null, null, null, null, "denied", denied.getMessage());
                throw denied;
            }
            if (welcomeTimeout != null) welcomeTimeout.cancel(false);
            JsonObject hello = new JsonObject();
            hello.addProperty("t", "hello");
            hello.addProperty("core", CoreApi.VERSION);
            hello.addProperty("rev", revision.getAsLong());
            hello.addProperty("maxBody", settings.maxBodyBytes());
            send(hello);
            Connection previous = current;
            current = this;
            if (previous != null && previous != this) previous.close(WebSocket.NORMAL_CLOSURE, "replaced");
            ready = true;
            backoffMs = 1000;
            failures = 0;
            state = "connected";
            log(Level.INFO, "[Web] Gateway tunnel connected: " + settings.gatewayUrl());
            audit.log("tunnel", null, null, null, null, "ok", "connected");
        }

        private void request(JsonObject o) {
            long id = o.get("id").getAsLong();
            if (!inFlight.tryAcquire()) {
                respond(id, new HttpResponseData(503, List.of(Map.entry("Content-Type", "application/json; charset=utf-8"),
                        Map.entry("Cache-Control", "no-store"), Map.entry("Retry-After", "2")),
                        Vystorm.vystorm_core.web.WebResponse.error(503, WebText.localize(WebText.error("busy"), WebText.fallbackLanguage())).body(), false));
                return;
            }
            ParsedRequest parsed;
            try {
                parsed = parseRequest(o, settings);
            } catch (RuntimeException invalid) {
                inFlight.release();
                throw invalid;
            }
            if (parsed.status() != 0) {
                // Zu groß ist kein Protokollfehler des Gateways, sondern eine Browser-Anfrage: beantworten, Tunnel bleibt.
                inFlight.release();
                String message = parsed.status() == 414 ? Vystorm.vystorm_core.web.internal.WebText.error("url-too-long") : parsed.status() == 431 ? Vystorm.vystorm_core.web.internal.WebText.error("too-many-headers") : Vystorm.vystorm_core.web.internal.WebText.error("too-large");
                respond(id, new HttpResponseData(parsed.status(), List.of(Map.entry("Content-Type", "application/json; charset=utf-8"),
                        Map.entry("Cache-Control", "no-store")), Vystorm.vystorm_core.web.WebResponse.error(parsed.status(), WebText.localize(message, WebText.fallbackLanguage())).body(), true));
                return;
            }
            HttpRequestData data = parsed.data();
            Thread.ofVirtual().name("Vystorm-Web-Tunnel-Req").start(() -> {
                boolean released = false;
                try {
                    HttpResponseData response = handler.apply(data);
                    if (response.stream() == null) { respond(id, response); return; }
                    inFlight.release();
                    released = true;
                    stream(id, response);
                } finally {
                    if (!released) inFlight.release();
                }
            });
        }

        /** Streamt eine SSE-Antwort: erst {@code res} mit {@code stream:true}, dann {@code chunk}s, zuletzt {@code end}. */
        private void stream(long id, HttpResponseData response) {
            ResponseStream stream = response.stream();
            streams.put(id, stream);
            try {
                JsonObject head = head(id, response);
                head.addProperty("stream", true);
                send(head);
                while (!closed) {
                    String chunk = stream.next(20_000);
                    if (ResponseStream.END.equals(chunk)) break;
                    JsonObject o = new JsonObject();
                    o.addProperty("t", "chunk");
                    o.addProperty("id", id);
                    o.addProperty("data", chunk == null ? ": ping\n\n" : chunk);
                    send(o);
                }
                JsonObject end = new JsonObject();
                end.addProperty("t", "end");
                end.addProperty("id", id);
                send(end);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                streams.remove(id);
                stream.close();
            }
        }

        private JsonObject head(long id, HttpResponseData response) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "res");
            o.addProperty("id", id);
            o.addProperty("status", response.status());
            JsonArray headers = new JsonArray();
            for (Map.Entry<String, String> h : response.headers()) {
                JsonArray pair = new JsonArray();
                pair.add(new JsonPrimitive(h.getKey()));
                pair.add(new JsonPrimitive(h.getValue()));
                headers.add(pair);
            }
            o.add("headers", headers);
            return o;
        }

        private void respond(long id, HttpResponseData response) {
            JsonObject o = head(id, response);
            o.addProperty("body", Base64.getEncoder().encodeToString(response.body()));
            send(o);
        }

        void send(JsonObject payload) {
            synchronized (sendLock) {
                if (closed || ws == null || key == null) return;
                payload.addProperty("seq", ++sendSeq);
                String frame = TunnelProtocol.seal(key, TunnelProtocol.C2G, GSON.toJson(payload));
                WebSocket socket = ws;
                sendChain = sendChain.handle((ok, failure) -> null)
                        .thenCompose(ignored -> closed ? CompletableFuture.completedFuture(null) : socket.sendText(frame, true))
                        .exceptionally(failure -> { abort("send failed: " + rootMessage(failure)); return null; });
            }
        }

        void abort(String reason) {
            if (closed) return;
            log(Level.WARNING, "[Web] Gateway tunnel aborted: " + reason);
            audit.log("tunnel", null, null, null, null, "aborted", reason);
            WebSocket socket = ws;
            if (socket != null) socket.abort();
            ended(reason);
        }

        void close(int code, String reason) {
            if (closed) return;
            WebSocket socket = ws;
            closed = true;
            for (ResponseStream stream : streams.values()) stream.close();
            streams.clear();
            ready = false;
            if (welcomeTimeout != null) welcomeTimeout.cancel(false);
            if (socket != null) {
                try { socket.sendClose(code, reason).orTimeout(2, TimeUnit.SECONDS).exceptionally(f -> { socket.abort(); return null; }); }
                catch (RuntimeException failure) { socket.abort(); }
            }
        }

        private void ended(String reason) {
            for (ResponseStream stream : streams.values()) stream.close();
            streams.clear();
            boolean wasReady = ready;
            if (closed && current != this) return;
            closed = true;
            ready = false;
            if (welcomeTimeout != null) welcomeTimeout.cancel(false);
            if (current == this) current = null;
            if (!running) return;
            state = "disconnected";
            if (wasReady) {
                log(Level.WARNING, "[Web] Gateway tunnel lost (" + reason + "); reconnecting");
                audit.log("tunnel", null, null, null, null, "lost", reason);
            }
            scheduleReconnect();
        }
    }
}
