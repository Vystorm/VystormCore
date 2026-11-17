package Vystorm.vystorm_core.web.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Einstellungen der Web-Plattform ({@code web} in Cores {@code config.yml}). Unveränderlich; {@link #toString()} enthält
 * das Gateway-Geheimnis nie. Core-intern.
 *
 * @since 0.17.0
 */
public record WebSettings(
        boolean enabled,
        Mode mode,
        String publicUrl,
        List<String> allowedHosts,
        // embedded
        String bind,
        int port,
        List<String> trustedProxies,
        int maxConnections,
        int maxConnectionsPerIp,
        // gateway
        String gatewayUrl,
        String gatewaySecret,
        boolean gatewayAllowInsecure,
        int gatewayMaxInFlight,
        long gatewayClockSkewMs,
        // session
        long loginTokenMs,
        long sessionIdleMs,
        long sessionMaxMs,
        int sessionsPerPlayer,
        // limits
        int maxBodyBytes,
        int maxHeaderBytes,
        int maxHeaders,
        long headerTimeoutMs,
        long idleTimeoutMs,
        long handlerTimeoutMs,
        long syncTimeoutMs,
        int maxConcurrentHandlers,
        // rate limits (pro Minute)
        int ipPerMinute,
        int loginPerMinute,
        int sessionPerMinute,
        // audit
        boolean auditEnabled,
        long auditMaxFileBytes,
        int auditFiles,
        // live (0.18.0)
        long liveCoalesceMs,
        int liveMaxEventBytes,
        int liveMaxPerPlayer,
        int liveMaxConnections,
        // Web-Oberfläche (gebautes vystorm-web-ui), relativ zum Core-Datenordner
        String uiDir,
        // 0.18.1: höchste Dauer eines blockierten Schreibvorgangs (Slow-Read-Schutz im eingebetteten Server)
        long writeTimeoutMs) {

    public enum Mode { EMBEDDED, GATEWAY }

    public WebSettings {
        publicUrl = normalizeUrl(publicUrl);
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
        bind = bind == null || bind.isBlank() ? "127.0.0.1" : bind.trim();
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        gatewayUrl = gatewayUrl == null ? "" : gatewayUrl.trim();
        gatewaySecret = gatewaySecret == null ? "" : gatewaySecret.trim();
        mode = mode == null ? Mode.EMBEDDED : mode;
        port = clamp(port, 1, 65535);
        maxConnections = clamp(maxConnections, 4, 10_000);
        maxConnectionsPerIp = clamp(maxConnectionsPerIp, 1, maxConnections);
        gatewayMaxInFlight = clamp(gatewayMaxInFlight, 1, 1024);
        gatewayClockSkewMs = clampL(gatewayClockSkewMs, 5_000, 300_000);
        loginTokenMs = clampL(loginTokenMs, 10_000, 600_000);
        sessionIdleMs = clampL(sessionIdleMs, 60_000, 24 * 3_600_000L);
        sessionMaxMs = clampL(sessionMaxMs, sessionIdleMs, 7 * 24 * 3_600_000L);
        sessionsPerPlayer = clamp(sessionsPerPlayer, 1, 20);
        maxBodyBytes = clamp(maxBodyBytes, 1024, 1024 * 1024);
        maxHeaderBytes = clamp(maxHeaderBytes, 2048, 64 * 1024);
        maxHeaders = clamp(maxHeaders, 16, 200);
        headerTimeoutMs = clampL(headerTimeoutMs, 1_000, 60_000);
        idleTimeoutMs = clampL(idleTimeoutMs, 1_000, 300_000);
        handlerTimeoutMs = clampL(handlerTimeoutMs, 500, 120_000);
        syncTimeoutMs = clampL(syncTimeoutMs, 100, handlerTimeoutMs);
        maxConcurrentHandlers = clamp(maxConcurrentHandlers, 1, 1024);
        ipPerMinute = clamp(ipPerMinute, 10, 100_000);
        loginPerMinute = clamp(loginPerMinute, 1, 1_000);
        sessionPerMinute = clamp(sessionPerMinute, 10, 100_000);
        auditMaxFileBytes = clampL(auditMaxFileBytes, 64 * 1024, 1024L * 1024 * 1024);
        auditFiles = clamp(auditFiles, 1, 50);
        liveCoalesceMs = clampL(liveCoalesceMs, 0, 5_000);
        liveMaxEventBytes = clamp(liveMaxEventBytes, 512, 256 * 1024);
        liveMaxPerPlayer = clamp(liveMaxPerPlayer, 1, 32);
        liveMaxConnections = clamp(liveMaxConnections, 1, 100_000);
        uiDir = uiDir == null ? "" : uiDir.trim();
        writeTimeoutMs = clampL(writeTimeoutMs, 1_000, 300_000);
    }

    /** Signatur von 0.18.0 (ohne {@code writeTimeoutMs}, Standard 30 s). */
    public WebSettings(boolean enabled, Mode mode, String publicUrl, List<String> allowedHosts, String bind, int port,
                       List<String> trustedProxies, int maxConnections, int maxConnectionsPerIp, String gatewayUrl,
                       String gatewaySecret, boolean gatewayAllowInsecure, int gatewayMaxInFlight, long gatewayClockSkewMs,
                       long loginTokenMs, long sessionIdleMs, long sessionMaxMs, int sessionsPerPlayer, int maxBodyBytes,
                       int maxHeaderBytes, int maxHeaders, long headerTimeoutMs, long idleTimeoutMs, long handlerTimeoutMs,
                       long syncTimeoutMs, int maxConcurrentHandlers, int ipPerMinute, int loginPerMinute, int sessionPerMinute,
                       boolean auditEnabled, long auditMaxFileBytes, int auditFiles, long liveCoalesceMs, int liveMaxEventBytes,
                       int liveMaxPerPlayer, int liveMaxConnections, String uiDir) {
        this(enabled, mode, publicUrl, allowedHosts, bind, port, trustedProxies, maxConnections, maxConnectionsPerIp, gatewayUrl,
                gatewaySecret, gatewayAllowInsecure, gatewayMaxInFlight, gatewayClockSkewMs, loginTokenMs, sessionIdleMs, sessionMaxMs,
                sessionsPerPlayer, maxBodyBytes, maxHeaderBytes, maxHeaders, headerTimeoutMs, idleTimeoutMs, handlerTimeoutMs,
                syncTimeoutMs, maxConcurrentHandlers, ipPerMinute, loginPerMinute, sessionPerMinute, auditEnabled, auditMaxFileBytes,
                auditFiles, liveCoalesceMs, liveMaxEventBytes, liveMaxPerPlayer, liveMaxConnections, uiDir, 30_000);
    }

    /** Standardwerte (Plattform aus). */
    public static WebSettings defaults() { return from(null); }

    /** Liest den Abschnitt {@code web}; fehlt er, ist die Plattform aus. */
    public static WebSettings from(ConfigurationSection s) {
        ConfigurationSection e = s == null ? null : s.getConfigurationSection("embedded");
        ConfigurationSection g = s == null ? null : s.getConfigurationSection("gateway");
        ConfigurationSection se = s == null ? null : s.getConfigurationSection("session");
        ConfigurationSection l = s == null ? null : s.getConfigurationSection("limits");
        ConfigurationSection r = s == null ? null : s.getConfigurationSection("rate-limits");
        ConfigurationSection a = s == null ? null : s.getConfigurationSection("audit");
        ConfigurationSection lv = s == null ? null : s.getConfigurationSection("live");
        String mode = str(s, "mode", "embedded").toLowerCase(Locale.ROOT);
        return new WebSettings(
                s != null && s.getBoolean("enabled", false),
                mode.equals("gateway") ? Mode.GATEWAY : Mode.EMBEDDED,
                str(s, "public-url", ""),
                list(s, "allowed-hosts"),
                str(e, "bind", "127.0.0.1"),
                integer(e, "port", 8190),
                list(e, "trusted-proxies"),
                integer(e, "max-connections", 256),
                integer(e, "max-connections-per-ip", 32),
                str(g, "url", ""),
                str(g, "secret", ""),
                g != null && g.getBoolean("allow-insecure", false),
                integer(g, "max-in-flight", 64),
                seconds(g, "clock-skew-seconds", 30),
                seconds(se, "login-token-seconds", 60),
                seconds(se, "idle-minutes", 30) * 60,
                seconds(se, "max-hours", 12) * 3600,
                integer(se, "max-per-player", 5),
                integer(l, "max-body-bytes", 65536),
                integer(l, "max-header-bytes", 16384),
                integer(l, "max-headers", 64),
                seconds(l, "header-timeout-seconds", 10),
                seconds(l, "idle-timeout-seconds", 15),
                seconds(l, "handler-timeout-seconds", 10),
                seconds(l, "sync-timeout-seconds", 5),
                integer(l, "max-concurrent-handlers", 32),
                integer(r, "ip-per-minute", 600),
                integer(r, "login-per-minute", 10),
                integer(r, "session-per-minute", 300),
                a == null || a.getBoolean("enabled", true),
                (long) integer(a, "max-file-mb", 5) * 1024 * 1024,
                integer(a, "files", 5),
                lv == null ? 100 : lv.getLong("coalesce-ms", 100),
                integer(lv, "max-event-bytes", 16384),
                integer(lv, "max-per-player", 5),
                integer(lv, "max-connections", 2000),
                str(s, "ui-dir", "web/ui"),
                seconds(l, "write-timeout-seconds", 30));
    }

    /** Öffentliche Basis-URL ohne Slash am Ende; leer = aus Bind/Port abgeleitet (nur lokal erreichbar). */
    public String effectivePublicUrl() {
        if (!publicUrl.isEmpty()) return publicUrl;
        String host = bind.equals("0.0.0.0") || bind.equals("::") ? "127.0.0.1" : bind;
        if (host.contains(":")) host = "[" + host + "]";
        return "http://" + host + ":" + port;
    }

    /** Ob Links und Cookies über HTTPS laufen (entscheidet {@code Secure}, {@code __Host-} und HSTS). */
    public boolean https() { return effectivePublicUrl().startsWith("https://"); }

    /** {@code scheme://host[:port]} der öffentlichen URL (Vergleichswert für den {@code Origin}-Header). */
    public String publicOrigin() {
        URI uri = URI.create(effectivePublicUrl());
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (port == 443 && "https".equals(uri.getScheme())) || (port == 80 && "http".equals(uri.getScheme()));
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return uri.getScheme() + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /** Erlaubte {@code Host}-Header (klein geschrieben, mit Port wenn nicht Standard). */
    public List<String> hostAllowlist() {
        List<String> out = new ArrayList<>(allowedHosts);
        String origin = publicOrigin();
        out.add(origin.substring(origin.indexOf("://") + 3));
        if (publicUrl.isEmpty()) {
            out.add("127.0.0.1:" + port);
            out.add("localhost:" + port);
            out.add("[::1]:" + port);
        }
        return List.copyOf(out);
    }

    /** Konfigurationsfehler, die einen Start verhindern; leer = gültig. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!publicUrl.isEmpty()) {
            try {
                URI uri = URI.create(publicUrl);
                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) problems.add("web.public-url must be http(s)");
                if (uri.getHost() == null) problems.add("web.public-url has no host");
                if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    problems.add("web.public-url must not contain a path (serve the web platform at the root of a host)");
                if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null)
                    problems.add("web.public-url must not contain user info, query or fragment");
            } catch (IllegalArgumentException invalid) {
                problems.add("web.public-url is not a valid URL");
            }
        }
        if (mode == Mode.GATEWAY) {
            if (publicUrl.isEmpty()) problems.add("web.public-url is required in gateway mode (the gateway's public address)");
            if (gatewayUrl.isEmpty()) problems.add("web.gateway.url is required in gateway mode");
            else {
                try {
                    URI uri = URI.create(gatewayUrl);
                    String scheme = uri.getScheme();
                    if (!"wss".equals(scheme) && !"ws".equals(scheme)) problems.add("web.gateway.url must be wss:// (or ws:// with allow-insecure)");
                    if ("ws".equals(scheme) && !gatewayAllowInsecure && !loopback(uri.getHost()))
                        problems.add("web.gateway.url uses ws:// to a non-loopback host; use wss:// or set web.gateway.allow-insecure: true (only inside a private network such as Tailscale)");
                    if (uri.getHost() == null) problems.add("web.gateway.url has no host");
                } catch (IllegalArgumentException invalid) {
                    problems.add("web.gateway.url is not a valid URL");
                }
            }
            if (gatewaySecret.length() < 32) problems.add("web.gateway.secret must be at least 32 characters");
        }
        return problems;
    }

    static boolean loopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    @Override public String toString() {
        return "WebSettings[enabled=" + enabled + ", mode=" + mode + ", publicUrl=" + publicUrl + ", bind=" + bind + ":" + port
                + ", gatewayUrl=" + gatewayUrl + ", secret=" + (gatewaySecret.isEmpty() ? "<empty>" : "<set>") + "]";
    }

    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String v = url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String str(ConfigurationSection s, String key, String def) { return s == null ? def : String.valueOf(s.getString(key, def)); }
    private static int integer(ConfigurationSection s, String key, int def) { return s == null ? def : s.getInt(key, def); }
    private static long seconds(ConfigurationSection s, String key, long def) { return (s == null ? def : s.getLong(key, def)) * 1000L; }
    private static List<String> list(ConfigurationSection s, String key) { return s == null ? List.of() : s.getStringList(key); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static long clampL(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }
}
