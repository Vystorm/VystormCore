package Vystorm.vystorm_core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Eine API-Route eines {@link WebModule}s, erreichbar unter {@code /api/<modulId><pfad>}. Unveränderlich.
 *
 * <pre>{@code
 * WebRoute.get("/tree", request -> WebResponse.json(tree.view(request.playerId())))
 * WebRoute.post("/nodes/{node}/allocate", this::allocate)
 *         .permission("vystorm.skills.web.allocate")
 *         .requiresOnline()
 *         .rateLimit(30)
 *         .maxBody(2048)
 *         .build();
 * }</pre>
 *
 * <p>Pfade bestehen aus festen Segmenten ({@code [A-Za-z0-9._~-]}) und Platzhaltern {@code {name}}; {@code "/"} ist die
 * Wurzel des Moduls. Nicht-GET-Routen sind standardmäßig {@link #mutating()} und werden im Audit-Log protokolliert;
 * CSRF- und Origin-Prüfung gelten für jede Nicht-GET-Route, unabhängig von diesem Schalter.
 *
 * @since 0.17.0
 */
public final class WebRoute {
    /** Standard-Ratenlimit pro Sitzung und Route (Anfragen pro Minute). */
    public static final int DEFAULT_RATE_PER_MINUTE = 120;
    /** Standard-Körpergrenze für Nicht-GET-Routen in Bytes. */
    public static final int DEFAULT_MAX_BODY = 16 * 1024;
    /** Höchstwert für {@link Builder#maxBody(int)}; die Core-Konfiguration kann die Grenze weiter senken. */
    public static final int MAX_BODY_LIMIT = 1024 * 1024;

    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9._~-]{1,64}");
    private static final Pattern PARAM = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]{0,31})}");
    private static final int MAX_SEGMENTS = 16;

    private final WebMethod method;
    private final String path;
    private final List<String> segments;
    private final String permission;
    private final boolean mutating;
    private final boolean requiresOnline;
    private final int ratePerMinute;
    private final int maxBodyBytes;
    private final WebHandler handler;

    private WebRoute(Builder b) {
        this.method = b.method;
        this.path = b.path;
        this.segments = b.segments;
        this.permission = b.permission;
        this.mutating = b.mutating == null ? method.unsafe() : b.mutating;
        this.requiresOnline = b.requiresOnline;
        this.ratePerMinute = b.ratePerMinute;
        this.maxBodyBytes = method == WebMethod.GET ? 0 : b.maxBody;
        this.handler = b.handler;
    }

    public static Builder get(String path, WebHandler handler) { return builder(WebMethod.GET, path, handler); }
    public static Builder post(String path, WebHandler handler) { return builder(WebMethod.POST, path, handler); }
    public static Builder put(String path, WebHandler handler) { return builder(WebMethod.PUT, path, handler); }
    public static Builder patch(String path, WebHandler handler) { return builder(WebMethod.PATCH, path, handler); }
    public static Builder delete(String path, WebHandler handler) { return builder(WebMethod.DELETE, path, handler); }

    /** @throws IllegalArgumentException bei ungültigem Pfad */
    public static Builder builder(WebMethod method, String path, WebHandler handler) { return new Builder(method, path, handler); }

    public WebMethod method() { return method; }
    /** Pfad relativ zu {@code /api/<modulId>}, z. B. {@code /nodes/{node}}. */
    public String path() { return path; }
    /** Zusätzliche Berechtigung (neben der des Moduls); leer = keine. */
    public Optional<String> permission() { return Optional.ofNullable(permission); }
    /** Ob Aufrufe im Audit-Log landen. Standard: {@code true} für alle Nicht-GET-Methoden. */
    public boolean mutating() { return mutating; }
    /** Ob der Spieler im Spiel online sein muss (sonst HTTP 409). */
    public boolean requiresOnline() { return requiresOnline; }
    /** Erlaubte Anfragen pro Minute und Sitzung für diese Route. */
    public int ratePerMinute() { return ratePerMinute; }
    /** Größter erlaubter Anfragekörper in Bytes; 0 bei GET. */
    public int maxBodyBytes() { return maxBodyBytes; }
    public WebHandler handler() { return handler; }

    /** Die Pfadsegmente; Platzhalter in der Form {@code {name}}. */
    public List<String> segments() { return segments; }

    /** Name des Platzhalters, oder null wenn {@code segment} fest ist. */
    public static String parameterName(String segment) {
        var m = PARAM.matcher(segment);
        return m.matches() ? m.group(1) : null;
    }

    @Override public String toString() { return method + " " + path; }

    public static final class Builder {
        private final WebMethod method;
        private final String path;
        private final List<String> segments;
        private final WebHandler handler;
        private String permission;
        private Boolean mutating;
        private boolean requiresOnline;
        private int ratePerMinute = DEFAULT_RATE_PER_MINUTE;
        private int maxBody = DEFAULT_MAX_BODY;

        private Builder(WebMethod method, String path, WebHandler handler) {
            this.method = Objects.requireNonNull(method, "method");
            this.handler = Objects.requireNonNull(handler, "handler");
            this.segments = parse(path);
            this.path = segments.isEmpty() ? "/" : "/" + String.join("/", segments);
        }

        /** Zusätzliche Bukkit-Berechtigung; null/leer = keine. Wird bei jeder Anfrage neu geprüft. */
        public Builder permission(String node) {
            this.permission = node == null || node.isBlank() ? null : checkNode(node);
            return this;
        }

        /** Ob Aufrufe im Audit-Log landen. Bei GET nur {@code false} erlaubt. */
        public Builder mutating(boolean value) {
            if (value && method == WebMethod.GET) throw new IllegalArgumentException("GET routes cannot be mutating");
            this.mutating = value;
            return this;
        }

        public Builder requiresOnline() { return requiresOnline(true); }
        public Builder requiresOnline(boolean value) { this.requiresOnline = value; return this; }

        /** Anfragen pro Minute und Sitzung (1–6000). */
        public Builder rateLimit(int perMinute) {
            if (perMinute < 1 || perMinute > 6000) throw new IllegalArgumentException("rateLimit must be 1..6000");
            this.ratePerMinute = perMinute;
            return this;
        }

        /** Größter Anfragekörper in Bytes (0–{@value #MAX_BODY_LIMIT}); ohne Wirkung bei GET. */
        public Builder maxBody(int bytes) {
            if (bytes < 0 || bytes > MAX_BODY_LIMIT) throw new IllegalArgumentException("maxBody must be 0.." + MAX_BODY_LIMIT);
            this.maxBody = bytes;
            return this;
        }

        public WebRoute build() { return new WebRoute(this); }

        private static List<String> parse(String path) {
            if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("route path must start with '/': " + path);
            List<String> out = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (!path.equals("/")) {
                String[] parts = path.substring(1).split("/", -1);
                if (parts.length > MAX_SEGMENTS) throw new IllegalArgumentException("route path has too many segments: " + path);
                for (String part : parts) {
                    String name = parameterName(part);
                    if (name != null) {
                        if (names.contains(name)) throw new IllegalArgumentException("duplicate path parameter " + name);
                        names.add(name);
                    } else if (!LITERAL.matcher(part).matches() || part.equals(".") || part.equals("..")) {
                        throw new IllegalArgumentException("invalid route path segment '" + part + "' in " + path);
                    }
                    out.add(part);
                }
            }
            return Collections.unmodifiableList(out);
        }
    }

    static String checkNode(String node) {
        String trimmed = node.trim();
        if (!trimmed.matches("[A-Za-z0-9_.*-]{1,128}")) throw new IllegalArgumentException("invalid permission node: " + node);
        return trimmed;
    }
}
