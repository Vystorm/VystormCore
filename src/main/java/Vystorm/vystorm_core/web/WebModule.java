package Vystorm.vystorm_core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ein Web-Modul eines Plugins: Seiten unter {@code /m/<id>/}, API unter {@code /api/<id>/}. Unveränderlich.
 *
 * <pre>{@code
 * WebModule module = WebModule.builder("skills", "Talentbaum")
 *         .permission("vystorm.skills.web")
 *         .assets("web/skills/")                 // Ordner im eigenen Plugin-Jar, index.html ist die Startseite
 *         .route(WebRoute.get("/tree", this::tree).build())
 *         .route(WebRoute.post("/allocate", this::allocate).requiresOnline().rateLimit(30).build())
 *         .build();
 * WebServices.register(this, module);
 * }</pre>
 *
 * <p>Statische Dateien sind öffentlich (auch ohne Anmeldung abrufbar und im Gateway gecacht): keine Geheimnisse und
 * keine Spielerdaten hineinlegen, Daten kommen über die API. Erlaubt sind nur bekannte Dateiendungen
 * (html, js, mjs, css, json, svg, png, jpg, jpeg, gif, webp, ico, woff, woff2, txt). Inline-Skripte und -Styles
 * blockiert die Content-Security-Policy; Skripte und Styles gehören in eigene Dateien.
 *
 * @since 0.17.0
 */
public final class WebModule {
    /** Erlaubte Modul-IDs. */
    public static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");
    /** Gültige Pfade innerhalb der Modul-Dateien (relativ, ohne führenden Slash). */
    static final Pattern ASSET_PATH = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}(/[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}){0,7}");

    private final String id;
    private final String title;
    private final String description;
    private final String permission;
    private final String assetPrefix;
    private final Map<String, byte[]> inlineAssets;
    private final List<WebRoute> routes;
    private final String entry;
    private final boolean listed;
    private final WebSnapshot snapshot;

    private WebModule(Builder b) {
        this.id = b.id;
        this.title = b.title;
        this.description = b.description;
        this.permission = b.permission;
        this.assetPrefix = b.assetPrefix;
        Map<String, byte[]> copy = new LinkedHashMap<>();
        b.inlineAssets.forEach((path, bytes) -> copy.put(path, bytes.clone()));
        this.inlineAssets = Collections.unmodifiableMap(copy);
        this.routes = List.copyOf(b.routes);
        this.entry = b.entry;
        this.listed = b.listed;
        this.snapshot = b.snapshot;
    }

    /** @param id {@code [a-z0-9-]{1,32}}; {@code core} ist Core vorbehalten */
    public static Builder builder(String id, String title) { return new Builder(id, title); }

    public String id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    /** Berechtigung für das ganze Modul (Seiten-Liste und alle Routen); leer = nur {@code vystorm.web.use}. */
    public Optional<String> permission() { return Optional.ofNullable(permission); }
    /** Ordner im Jar des registrierenden Plugins, z. B. {@code web/skills/}; leer = keine Jar-Dateien. */
    public Optional<String> assetPrefix() { return Optional.ofNullable(assetPrefix); }
    /** Direkt übergebene Dateien (Pfad → Inhalt); Kopien. */
    public Map<String, byte[]> inlineAssets() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        inlineAssets.forEach((path, bytes) -> copy.put(path, bytes.clone()));
        return copy;
    }
    public List<WebRoute> routes() { return routes; }
    /** Startseite relativ zum Modul, Standard {@code index.html}. */
    public String entry() { return entry; }
    /** Ob das Modul auf der Übersichtsseite erscheint. */
    public boolean listed() { return listed; }

    /** Quelle für den Live-Zustand beim Verbinden; leer = nur gepushte Ereignisse. @since 0.18.0 */
    public Optional<WebSnapshot> snapshot() { return Optional.ofNullable(snapshot); }

    /** Pfad der Modul-Startseite, z. B. {@code /m/skills/}. */
    public String pagePath() { return "/m/" + id + "/"; }

    @Override public String toString() { return "WebModule[" + id + "]"; }

    public static final class Builder {
        private final String id;
        private final String title;
        private String description = "";
        private String permission;
        private String assetPrefix;
        private final Map<String, byte[]> inlineAssets = new LinkedHashMap<>();
        private final List<WebRoute> routes = new ArrayList<>();
        private String entry = "index.html";
        private boolean listed = true;
        private WebSnapshot snapshot;

        private Builder(String id, String title) {
            if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("module id must match [a-z0-9-]{1,32}: " + id);
            if (title == null || title.isBlank() || title.length() > 64) throw new IllegalArgumentException("module title must be 1..64 chars");
            this.id = id;
            this.title = title.strip();
        }

        public Builder description(String text) {
            String value = text == null ? "" : text.strip();
            if (value.length() > 200) throw new IllegalArgumentException("description too long (max 200)");
            this.description = value;
            return this;
        }

        /** Modulberechtigung; null/leer = keine zusätzliche. */
        public Builder permission(String node) {
            this.permission = node == null || node.isBlank() ? null : WebRoute.checkNode(node);
            return this;
        }

        /**
         * Lädt beim Registrieren alle erlaubten Dateien unter {@code classpathPrefix} aus dem Jar des Plugins
         * (z. B. {@code web/skills/} → {@code /m/<id>/...}).
         */
        public Builder assets(String classpathPrefix) {
            if (classpathPrefix == null || classpathPrefix.isBlank()) { this.assetPrefix = null; return this; }
            String prefix = classpathPrefix.strip();
            while (prefix.startsWith("/")) prefix = prefix.substring(1);
            if (!prefix.endsWith("/")) prefix = prefix + "/";
            if (prefix.contains("..") || prefix.contains("\\") || prefix.equals("/") || !prefix.matches("[A-Za-z0-9._/-]{1,128}"))
                throw new IllegalArgumentException("invalid asset prefix: " + classpathPrefix);
            this.assetPrefix = prefix;
            return this;
        }

        /** Eine Datei direkt übergeben (überschreibt eine gleichnamige Jar-Datei). */
        public Builder asset(String path, byte[] content) {
            Objects.requireNonNull(content, "content");
            if (path == null || !ASSET_PATH.matcher(path).matches() || path.contains(".."))
                throw new IllegalArgumentException("invalid asset path: " + path);
            inlineAssets.put(path, content.clone());
            return this;
        }

        public Builder route(WebRoute route) {
            routes.add(Objects.requireNonNull(route, "route"));
            return this;
        }

        public Builder routes(Iterable<WebRoute> list) {
            for (WebRoute route : list) route(route);
            return this;
        }

        /** Startseite (Standard {@code index.html}). */
        public Builder entry(String path) {
            if (path == null || !ASSET_PATH.matcher(path).matches() || path.contains(".."))
                throw new IllegalArgumentException("invalid entry: " + path);
            this.entry = path;
            return this;
        }

        /**
         * Live-Zustand beim Verbinden des Live-Kanals (siehe {@link WebSnapshot}); danach nur noch
         * {@link WebServices#push}. @since 0.18.0
         */
        public Builder snapshot(WebSnapshot source) { this.snapshot = source; return this; }

        /** Auf der Übersichtsseite anzeigen (Standard {@code true}). */
        public Builder listed(boolean value) { this.listed = value; return this; }

        /** @throws IllegalArgumentException bei doppelten Routen (gleiche Methode und gleiches Muster) */
        public WebModule build() {
            Set<String> keys = new HashSet<>();
            for (WebRoute route : routes) {
                String shape = route.method() + " " + route.segments().stream()
                        .map(s -> WebRoute.parameterName(s) == null ? s : "{}").reduce("", (a, s) -> a + "/" + s);
                if (!keys.add(shape)) throw new IllegalArgumentException("duplicate route " + route + " in module " + id);
            }
            return new WebModule(this);
        }
    }
}
