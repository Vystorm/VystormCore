package Vystorm.vystorm_core.web.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lädt statische Dateien eines Moduls beim Registrieren vollständig in den Speicher. Ausgeliefert wird nur, was hier
 * geladen wurde (exakter Schlüsselvergleich): Pfad-Traversal über {@code ..}, Kodierungstricks oder Symlinks erreicht
 * nichts außerhalb dieser Tabelle. Nur Dateien mit erlaubter Endung werden übernommen. Core-intern.
 *
 * @since 0.17.0
 */
public final class StaticAssets {
    /** Eine ausgelieferte Datei mit starkem ETag. */
    public record Asset(byte[] data, String contentType, String etag) {}

    static final int MAX_FILES = 512;
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}");

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private StaticAssets() {}

    /** Content-Type zu einem Pfad, leer bei nicht erlaubter Endung. */
    public static Optional<String> contentType(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot <= slash + 1 || dot == path.length() - 1) return Optional.empty();
        return Optional.ofNullable(TYPES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    /** Ob {@code path} ein gültiger relativer Dateipfad ist (Segmente ohne führenden Punkt, höchstens 8 Ebenen). */
    public static boolean validPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) return false;
        String[] parts = path.split("/", -1);
        if (parts.length > 8) return false;
        for (String part : parts) if (!SEGMENT.matcher(part).matches() || part.contains("..")) return false;
        return true;
    }

    public static Asset asset(byte[] data, String contentType) { return new Asset(data, contentType, etag(data)); }

    /**
     * Lädt alle erlaubten Dateien unter {@code prefix}: zuerst aus dem Jar, aus dem {@code anchor} stammt, sonst über
     * {@code loader.getResource(prefix)} (Jar oder Verzeichnis, z. B. in Tests).
     *
     * @throws IOException bei Lesefehlern oder überschrittenen Grenzen
     */
    public static Map<String, Asset> load(Class<?> anchor, ClassLoader loader, String prefix) throws IOException {
        Map<String, Asset> out = new LinkedHashMap<>();
        Path jar = codeSourceJar(anchor);
        if (jar != null && loadFromJar(jar, prefix, out)) return out;
        URL url = loader == null ? null : loader.getResource(prefix);
        if (url == null) return out;
        if ("jar".equals(url.getProtocol())) {
            var connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            loadFromJar(Path.of(uri(connection.getJarFileURL())), prefix, out);
        } else if ("file".equals(url.getProtocol())) {
            loadFromDirectory(Path.of(uri(url)), out);
        }
        return out;
    }

    private static Path codeSourceJar(Class<?> anchor) {
        try {
            CodeSource source = anchor == null ? null : anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            Path path = Path.of(uri(source.getLocation()));
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    /** @return ob unter dem Präfix mindestens ein Eintrag existiert */
    private static boolean loadFromJar(Path jar, String prefix, Map<String, Asset> out) throws IOException {
        boolean found = false;
        long total = 0;
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix)) continue;
                found = true;
                if (entry.isDirectory()) continue;
                String relative = name.substring(prefix.length());
                Optional<String> type = contentType(relative);
                if (!validPath(relative) || type.isEmpty()) continue;
                if (out.size() >= MAX_FILES) throw new IOException("too many web assets under " + prefix);
                if (entry.getSize() > MAX_FILE_BYTES) throw new IOException("web asset too large: " + name);
                byte[] data;
                try (InputStream in = file.getInputStream(entry)) { data = in.readNBytes((int) MAX_FILE_BYTES + 1); }
                if (data.length > MAX_FILE_BYTES) throw new IOException("web asset too large: " + name);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("web assets under " + prefix + " exceed " + MAX_TOTAL_BYTES + " bytes");
                out.put(relative, asset(data, type.get()));
            }
        }
        return found;
    }

    /** Lädt alle erlaubten Dateien eines Verzeichnisses (rekursiv, ohne Symlinks); leer, wenn es fehlt. */
    public static Map<String, Asset> loadDirectory(Path root) throws IOException {
        Map<String, Asset> out = new LinkedHashMap<>();
        if (Files.isDirectory(root)) loadFromDirectory(root, out);
        return out;
    }

    private static void loadFromDirectory(Path root, Map<String, Asset> out) throws IOException {
        long total = 0;
        try (Stream<Path> files = Files.walk(root, 8)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                Optional<String> type = contentType(relative);
                if (!validPath(relative) || type.isEmpty()) continue;
                if (out.size() >= MAX_FILES) throw new IOException("too many web assets in " + root);
                if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("web asset too large: " + path);
                byte[] data = Files.readAllBytes(path);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("web assets in " + root + " exceed " + MAX_TOTAL_BYTES + " bytes");
                out.put(relative, asset(data, type.get()));
            }
        }
    }

    static String etag(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 18)) + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static URI uri(URL url) {
        try { return url.toURI(); } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
    }
}
