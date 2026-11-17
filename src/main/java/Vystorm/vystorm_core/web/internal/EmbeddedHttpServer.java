package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.web.WebResponse;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kleiner, strikter HTTP/1.1-Server für den eingebetteten Modus (eigene Implementierung statt
 * {@code com.sun.net.httpserver}, weil dessen Grenzen und Timeouts nur über JVM-weite System-Properties einstellbar sind
 * und dann auch andere Plugins treffen). Durchgesetzt werden:
 * <ul>
 *   <li>Anfragezeile ≤ 8 KiB, Header gesamt ≤ {@code max-header-bytes}, höchstens {@code max-headers} Header;</li>
 *   <li>Kopf muss innerhalb {@code header-timeout} vollständig sein, Körper ebenso (Slowloris), Leerlauf-Timeout
 *       zwischen Keep-Alive-Anfragen;</li>
 *   <li>nur {@code Content-Length}-Körper bis {@code max-body-bytes}; {@code Transfer-Encoding}, doppelte
 *       {@code Content-Length}, Header-Folding und nackte CR/LF werden abgelehnt (keine Request-Smuggling-Mehrdeutigkeit
 *       hinter einem Proxy);</li>
 *   <li>Verbindungslimit gesamt und je IP (vertrauenswürdige Proxys sind vom IP-Limit ausgenommen);</li>
 *   <li>{@code X-Forwarded-For}/{@code -Proto} zählen nur von {@code trusted-proxies}.</li>
 *   <li>Schreib-Timeout (0.18.1): ein Schreibvorgang, der länger als {@code write-timeout} hängt (Client liest nicht,
 *       „Slow Read“), schließt die Verbindung; ein Wächter-Thread prüft das, weil blockierende Socket-Writes in Java keinen
 *       eigenen Timeout haben.</li>
 * </ul>
 * Jede Verbindung läuft auf einem virtuellen Thread. Core-intern.
 *
 * @since 0.17.0
 */
public final class EmbeddedHttpServer implements AutoCloseable {
    static final int MAX_REQUEST_LINE = 8192;
    static final int MAX_REQUESTS_PER_CONNECTION = 200;

    private final WebSettings settings;
    private final Function<HttpRequestData, HttpResponseData> handler;
    private final Logger logger;
    private final List<Cidr> trusted;
    private final AtomicInteger connections = new AtomicInteger();
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Map<Socket, DeadlineOutputStream> writers = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread acceptor;
    private java.util.concurrent.ScheduledExecutorService watchdog;

    public EmbeddedHttpServer(WebSettings settings, Function<HttpRequestData, HttpResponseData> handler, Logger logger) {
        this.settings = settings;
        this.handler = handler;
        this.logger = logger;
        List<Cidr> list = new ArrayList<>();
        for (String entry : settings.trustedProxies()) {
            Cidr cidr = Cidr.parse(entry);
            if (cidr == null) { if (logger != null) logger.warning("[Web] Ignoring invalid trusted proxy '" + entry + "'"); }
            else list.add(cidr);
        }
        this.trusted = List.copyOf(list);
    }

    /** Bindet den Port ({@code 0} = beliebig, für Tests) und startet den Accept-Thread. */
    public synchronized void start() throws IOException { start(settings.port()); }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(settings.bind()), port), 128);
        server = socket;
        running = true;
        acceptor = Thread.ofPlatform().daemon().name("Vystorm-Web-Accept").start(this::acceptLoop);
        watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("Vystorm-Web-Watchdog").unstarted(r));
        long period = Math.max(50, Math.min(1000, settings.writeTimeoutMs() / 4));
        watchdog.scheduleWithFixedDelay(this::closeStalledWriters, period, period, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Schließt Verbindungen, deren aktueller Schreibvorgang die Frist überschritten hat (löst das blockierte write()). */
    void closeStalledWriters() {
        long now = System.nanoTime();
        for (Map.Entry<Socket, DeadlineOutputStream> e : writers.entrySet()) {
            if (e.getValue().stalled(now)) {
                if (logger != null) logger.log(Level.FINE, "[Web] closing stalled connection (write timeout)");
                try { e.getKey().close(); } catch (IOException ignored) { }
            }
        }
    }

    public int port() { ServerSocket s = server; return s == null ? -1 : s.getLocalPort(); }

    public int openConnections() { return connections.get(); }

    @Override public synchronized void close() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        for (Socket socket : open) try { socket.close(); } catch (IOException ignored) { }
        open.clear();
        if (watchdog != null) watchdog.shutdownNow();
        if (acceptor != null) try { acceptor.join(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                ServerSocket s = server;
                if (s == null) return;
                socket = s.accept();
            } catch (IOException closed) {
                if (running && logger != null) logger.log(Level.FINE, "[Web] accept failed", closed);
                continue;
            }
            Thread.ofVirtual().name("Vystorm-Web-Conn").start(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        String remote = socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
        boolean proxy = isTrusted(remote);
        String ipKey = RateLimiter.clientKey(remote); // IPv6: je /64
        AtomicInteger ipCount = proxy ? null : perIp.computeIfAbsent(ipKey, k -> new AtomicInteger());
        int total = connections.incrementAndGet();
        int mine = ipCount == null ? 0 : ipCount.incrementAndGet();
        open.add(socket);
        try (socket) {
            if (total > settings.maxConnections() || mine > settings.maxConnectionsPerIp()) {
                socket.setSoTimeout(1000);
                writeError(new BufferedOutputStream(socket.getOutputStream()), 503, "Zu viele Verbindungen.");
                return;
            }
            socket.setTcpNoDelay(true);
            InputStream raw = new BufferedInputStream(socket.getInputStream(), 8192);
            DeadlineOutputStream guarded = new DeadlineOutputStream(socket.getOutputStream(), settings.writeTimeoutMs() * 1_000_000L);
            writers.put(socket, guarded);
            OutputStream out = new BufferedOutputStream(guarded, 16384);
            for (int n = 0; n < MAX_REQUESTS_PER_CONNECTION && running; n++) {
                Reader reader = new Reader(socket, raw);
                // Auf das erste Byte höchstens Leerlauf-Timeout warten, danach muss der Kopf im Header-Timeout fertig sein.
                reader.deadline(System.nanoTime() + (n == 0 ? settings.headerTimeoutMs() : settings.idleTimeoutMs()) * 1_000_000L);
                int first;
                try { first = reader.read(); } catch (SocketTimeoutException idle) { return; }
                if (first < 0) return;
                reader.deadline(System.nanoTime() + settings.headerTimeoutMs() * 1_000_000L);
                Parsed parsed;
                try {
                    parsed = parse(reader, first, out);
                } catch (HttpError error) {
                    writeError(out, error.status, error.getMessage());
                    return;
                } catch (SocketTimeoutException slow) {
                    writeError(out, 408, Vystorm.vystorm_core.web.internal.WebText.error("read-timeout"));
                    return;
                }
                String clientIp = remote;
                boolean secure = false;
                if (proxy) {
                    clientIp = forwardedFor(parsed.headers.get("x-forwarded-for"), remote);
                    String proto = first(parsed.headers.get("x-forwarded-proto"));
                    secure = proto != null && proto.trim().equalsIgnoreCase("https");
                }
                HttpRequestData request = new HttpRequestData(parsed.method, parsed.target, parsed.headers, parsed.body, clientIp, secure);
                HttpResponseData response = handler.apply(request);
                if (response.stream() != null) { stream(socket, out, response); return; }
                boolean keepAlive = parsed.keepAlive && !response.close() && running && n + 1 < MAX_REQUESTS_PER_CONNECTION;
                write(out, response, keepAlive);
                if (!keepAlive) return;
            }
        } catch (IOException | RuntimeException failure) {
            if (logger != null && running) logger.log(Level.FINE, "[Web] connection " + remote + " ended: " + failure);
        } finally {
            open.remove(socket);
            writers.remove(socket);
            connections.decrementAndGet();
            if (ipCount != null && ipCount.decrementAndGet() <= 0) perIp.remove(ipKey, ipCount);
        }
    }

    /** Merkt sich, seit wann ein Schreibvorgang läuft; der Wächter schließt die Verbindung nach der Frist. */
    static final class DeadlineOutputStream extends java.io.FilterOutputStream {
        private final long timeoutNanos;
        private volatile long since;

        DeadlineOutputStream(OutputStream out, long timeoutNanos) { super(out); this.timeoutNanos = timeoutNanos; }

        boolean stalled(long now) { long s = since; return s != 0 && now - s > timeoutNanos; }

        @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            since = Math.max(1, System.nanoTime());
            try { out.write(b, off, len); } finally { since = 0; }
        }

        @Override public void flush() throws IOException {
            since = Math.max(1, System.nanoTime());
            try { out.flush(); } finally { since = 0; }
        }
    }

    // ---------------------------------------------------------------- Parser

    private record Parsed(String method, String target, Map<String, List<String>> headers, byte[] body, boolean keepAlive) {}

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status, String message) { super(message, null); this.status = status; }
    }

    /** Liest mit Gesamtfrist; jede einzelne Leseoperation bekommt die Restzeit als Socket-Timeout. */
    static final class Reader {
        private final Socket socket;
        private final InputStream in;
        private long deadline;

        Reader(Socket socket, InputStream in) { this.socket = socket; this.in = in; }

        void deadline(long nanos) { this.deadline = nanos; }

        int read() throws IOException {
            if (in.available() == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("deadline");
                socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining / 1_000_000L)));
            }
            return in.read();
        }

        int read(byte[] buffer, int offset, int length) throws IOException {
            if (in.available() == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("deadline");
                socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining / 1_000_000L)));
            }
            return in.read(buffer, offset, length);
        }
    }

    private Parsed parse(Reader reader, int first, OutputStream out) throws IOException {
        int[] budget = {settings.maxHeaderBytes()};
        String line = readLine(reader, first, MAX_REQUEST_LINE, null);
        // Ein einzelnes führendes CRLF ist erlaubt (RFC 9112 §2.2).
        if (line.isEmpty()) line = readLine(reader, -2, MAX_REQUEST_LINE, null);
        String[] parts = line.split(" ", -1);
        if (parts.length != 3) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-request-line"));
        String method = parts[0], target = parts[1], version = parts[2];
        if (!method.matches("[A-Z]{1,16}")) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-method"));
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) throw new HttpError(505, Vystorm.vystorm_core.web.internal.WebText.error("http11-only"));
        if (target.isEmpty() || target.charAt(0) != '/') throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-target"));
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7f) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-target"));
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String header = readLine(reader, -2, budget[0], budget);
            if (header.isEmpty()) break;
            if (++count > settings.maxHeaders()) throw new HttpError(431, Vystorm.vystorm_core.web.internal.WebText.error("too-many-headers"));
            char c0 = header.charAt(0);
            if (c0 == ' ' || c0 == '\t') throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("header-folding"));
            int colon = header.indexOf(':');
            if (colon <= 0) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-header"));
            String name = header.substring(0, colon);
            for (int i = 0; i < name.length(); i++) if (!tokenChar(name.charAt(i))) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-header-name"));
            String value = header.substring(colon + 1).strip();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c < 0x20 && c != '\t') || c == 0x7f) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-header-value"));
            }
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
        }
        if (version.equals("HTTP/1.1") && !headers.containsKey("host")) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("host-missing"));
        if (headers.containsKey("transfer-encoding")) throw new HttpError(501, Vystorm.vystorm_core.web.internal.WebText.error("no-transfer-encoding"));
        long length = 0;
        List<String> lengths = headers.get("content-length");
        if (lengths != null) {
            if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,10}")) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-content-length"));
            length = Long.parseLong(lengths.getFirst());
        }
        if (length > settings.maxBodyBytes()) throw new HttpError(413, Vystorm.vystorm_core.web.internal.WebText.error("too-large"));
        String expect = first(headers.get("expect"));
        if (expect != null) {
            if (!expect.equalsIgnoreCase("100-continue")) throw new HttpError(417, Vystorm.vystorm_core.web.internal.WebText.error("no-expect"));
            if (length > 0) { out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); }
        }
        byte[] body = new byte[(int) length];
        int read = 0;
        while (read < length) {
            int n = reader.read(body, read, body.length - read);
            if (n < 0) throw new EOFException("body truncated");
            read += n;
        }
        String connection = first(headers.get("connection"));
        boolean close = connection != null && List.of(connection.toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("close");
        boolean keepAlive = version.equals("HTTP/1.1") && !close;
        return new Parsed(method, target, headers, body, keepAlive);
    }

    /**
     * Liest eine Zeile bis CRLF (ohne CRLF). Nackte CR oder LF sind Fehler. {@code first} ist ein bereits gelesenes Byte
     * oder -2. {@code budget} (optional) wird um die gelesenen Bytes verringert.
     */
    private static String readLine(Reader reader, int first, int max, int[] budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b = first == -2 ? reader.read() : first;
        int used = 0;
        while (true) {
            if (b < 0) throw new EOFException("connection closed");
            if (++used > max) throw new HttpError(budget == null ? 414 : 431, budget == null ? WebText.error("line-too-long") : WebText.error("header-too-large"));
            if (b == '\r') {
                int next = reader.read();
                if (next != '\n') throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-line-end"));
                if (budget != null) budget[0] -= used + 1;
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            if (b == '\n' || b == 0) throw new HttpError(400, Vystorm.vystorm_core.web.internal.WebText.error("bad-line-end"));
            line.write(b);
            b = reader.read();
        }
    }

    private static boolean tokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private static String first(List<String> values) { return values == null || values.isEmpty() ? null : values.getFirst(); }

    // ---------------------------------------------------------------- Proxy

    private boolean isTrusted(String ip) {
        InetAddress address = literal(ip);
        if (address == null) return false;
        for (Cidr cidr : trusted) if (cidr.contains(address)) return true;
        return false;
    }

    /** Rechtester Eintrag, der kein vertrauenswürdiger Proxy ist; ungültige Einträge beenden die Suche. */
    String forwardedFor(List<String> headers, String fallback) {
        if (headers == null || headers.isEmpty()) return fallback;
        List<String> hops = new ArrayList<>();
        for (String header : headers) for (String hop : header.split(",")) hops.add(hop.trim());
        String candidate = fallback;
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress address = literal(hops.get(i));
            if (address == null) return candidate;
            candidate = address.getHostAddress();
            if (!isTrusted(candidate)) return candidate;
        }
        return candidate;
    }

    /** IP-Literal ohne DNS-Auflösung; null wenn keines. */
    static InetAddress literal(String text) {
        if (text == null || text.isEmpty() || text.length() > 45) return null;
        String t = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':')) return null;
        }
        try { return com.google.common.net.InetAddresses.forString(t); } catch (IllegalArgumentException invalid) { return null; }
    }

    /** IP oder CIDR-Bereich ({@code 10.0.0.0/8}, {@code fd7a::/48}). */
    record Cidr(byte[] network, int bits) {
        static Cidr parse(String text) {
            String t = text.trim();
            int slash = t.indexOf('/');
            InetAddress address = literal(slash < 0 ? t : t.substring(0, slash));
            if (address == null) return null;
            int max = address.getAddress().length * 8;
            int bits = max;
            if (slash >= 0) {
                try { bits = Integer.parseInt(t.substring(slash + 1)); } catch (NumberFormatException invalid) { return null; }
                if (bits < 0 || bits > max) return null;
            }
            return new Cidr(address.getAddress(), bits);
        }

        boolean contains(InetAddress address) {
            byte[] a = address.getAddress();
            if (a.length != network.length) return false;
            int full = bits / 8, rest = bits % 8;
            for (int i = 0; i < full; i++) if (a[i] != network[i]) return false;
            if (rest == 0) return true;
            int mask = (0xff << (8 - rest)) & 0xff;
            return (a[full] & mask) == (network[full] & mask);
        }
    }

    // ---------------------------------------------------------------- Antwort

    private static void write(OutputStream out, HttpResponseData response, boolean keepAlive) throws IOException {
        StringBuilder head = new StringBuilder(512);
        head.append("HTTP/1.1 ").append(response.status()).append(' ').append(reason(response.status())).append("\r\n");
        for (Map.Entry<String, String> h : response.headers()) {
            if (h.getKey().equalsIgnoreCase("content-length") || h.getKey().equalsIgnoreCase("connection")) continue;
            head.append(safe(h.getKey())).append(": ").append(safe(h.getValue())).append("\r\n");
        }
        boolean noBody = response.status() == 204 || response.status() == 304;
        if (!noBody) head.append("Content-Length: ").append(response.body().length).append("\r\n");
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!noBody) out.write(response.body());
        out.flush();
    }

    /** Schreibt Kopf und dann fortlaufend den Stream (SSE); Heartbeat-Kommentar alle 20 s; danach Verbindung zu. */
    private void stream(Socket socket, OutputStream out, HttpResponseData response) throws IOException {
        ResponseStream stream = response.stream();
        stream.onClose(() -> { try { socket.close(); } catch (IOException ignored) { } });
        try {
            StringBuilder head = new StringBuilder(512);
            head.append("HTTP/1.1 ").append(response.status()).append(' ').append(reason(response.status())).append("\r\n");
            for (Map.Entry<String, String> h : response.headers()) {
                if (h.getKey().equalsIgnoreCase("content-length") || h.getKey().equalsIgnoreCase("connection")) continue;
                head.append(safe(h.getKey())).append(": ").append(safe(h.getValue())).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            while (running) {
                String chunk = stream.next(20_000);
                if (ResponseStream.END.equals(chunk)) break;
                out.write((chunk == null ? ": ping\n\n" : chunk).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            stream.close();
        }
    }

    private static void writeError(OutputStream out, int status, String message) {
        try {
            // Before the request is parsed nothing about the client is known: fixed language.default, else English.
            byte[] body = WebResponse.error(status, WebText.localize(message, WebText.fallbackLanguage())).body();
            String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            // Verbindung ist ohnehin kaputt.
        }
    }

    private static String safe(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c == '\r' || c == '\n' || c > 0xff ? ' ' : c);
        }
        return out.toString();
    }

    static String reason(int status) {
        return switch (status) {
            case 100 -> "Continue"; case 200 -> "OK"; case 204 -> "No Content"; case 304 -> "Not Modified"; case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request"; case 401 -> "Unauthorized"; case 403 -> "Forbidden"; case 404 -> "Not Found";
            case 405 -> "Method Not Allowed"; case 408 -> "Request Timeout"; case 409 -> "Conflict"; case 413 -> "Content Too Large";
            case 414 -> "URI Too Long"; case 415 -> "Unsupported Media Type"; case 417 -> "Expectation Failed";
            case 421 -> "Misdirected Request"; case 429 -> "Too Many Requests"; case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error"; case 501 -> "Not Implemented"; case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout"; case 505 -> "HTTP Version Not Supported";
            default -> "Status";
        };
    }
}
