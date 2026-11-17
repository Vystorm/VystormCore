package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAssetsTest {
    @TempDir Path dir;

    @Test
    void givenJarWithMixedFiles_whenLoaded_thenOnlyAllowedTypesUnderPrefix() throws Exception {
        Path jar = dir.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "web/skills/", null);
            put(out, "web/skills/index.html", "<p>ok");
            put(out, "web/skills/js/app.js", "x()");
            put(out, "web/skills/secret.yml", "password: 1");
            put(out, "web/skills/.env", "X=1");
            put(out, "web/skills/Main.class", "cafe");
            put(out, "web/other/leak.html", "no");
            put(out, "config.yml", "db: pw");
        }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            var assets = StaticAssets.load(null, loader, "web/skills/");
            assertEquals(java.util.Set.of("index.html", "js/app.js"), assets.keySet());
            assertEquals("text/html; charset=utf-8", assets.get("index.html").contentType());
            assertTrue(assets.get("index.html").etag().startsWith("\""));
        }
    }

    @Test
    void givenDirectory_whenLoaded_thenSymlinksAndHiddenFilesIgnored() throws Exception {
        Path root = Files.createDirectories(dir.resolve("web/mod"));
        Files.writeString(root.resolve("index.html"), "ok");
        Files.writeString(root.resolve(".hidden.js"), "no");
        Path outside = Files.writeString(dir.resolve("outside.js"), "secret");
        try { Files.createSymbolicLink(root.resolve("link.js"), outside); } catch (UnsupportedOperationException | java.io.IOException ignored) { }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, null)) {
            var assets = StaticAssets.load(null, loader, "web/mod/");
            assertEquals(java.util.Set.of("index.html"), assets.keySet());
        }
    }

    @Test
    void givenPaths_whenValidated_thenOnlySafeRelativePaths() {
        assertTrue(StaticAssets.validPath("index.html"));
        assertTrue(StaticAssets.validPath("img/a-b_c.png"));
        assertFalse(StaticAssets.validPath("../x.js"));
        assertFalse(StaticAssets.validPath("/x.js"));
        assertFalse(StaticAssets.validPath("a//b.js"));
        assertFalse(StaticAssets.validPath(".git/config"));
        assertFalse(StaticAssets.validPath("a\\b.js"));
        assertFalse(StaticAssets.validPath("a..b/x.js"));
        assertTrue(StaticAssets.contentType("x.exe").isEmpty());
        assertTrue(StaticAssets.contentType("x.jsp").isEmpty());
        assertEquals("image/svg+xml", StaticAssets.contentType("a.SVG").orElseThrow());
    }

    private static void put(JarOutputStream out, String name, String content) throws Exception {
        out.putNextEntry(new JarEntry(name));
        if (content != null) out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
