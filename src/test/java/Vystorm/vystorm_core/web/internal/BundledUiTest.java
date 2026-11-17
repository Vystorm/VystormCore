package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The web UI bundled in Core's jar (webui/vystorm-web-ui-dist.zip → web/bundled-ui/): present, CSP-safe, served. */
class BundledUiTest {
    @TempDir Path dir;

    private static Map<String, StaticAssets.Asset> bundled() {
        return WebPlatform.bundledUi(WebPlatform.class, WebPlatform.class.getClassLoader(), null);
    }

    @Test void givenCoreBuildThenBundledUiHasIndexAndVersion() {
        Map<String, StaticAssets.Asset> ui = bundled();
        assertTrue(ui.containsKey("index.html"), "run scripts/update-web-ui.sh: " + ui.keySet());
        assertTrue(ui.keySet().stream().anyMatch(k -> k.startsWith("assets/") && k.endsWith(".js")));
        assertNotEquals("?", WebPlatform.bundledUiVersion(WebPlatform.class.getClassLoader()));
        assertTrue(ui.size() < StaticAssets.MAX_FILES);
        assertTrue(ui.values().stream().mapToLong(a -> a.data().length).sum() < 4L * 1024 * 1024, "keep the jar small");
    }

    /** Same rules as vystorm-web-ui scripts/check-dist.mjs (Core's CSP: script-src 'self'; style-src 'self'). */
    @Test void givenBundledUiThenNoInlineCodeAndOnlyHashedAssets() {
        Map<String, StaticAssets.Asset> ui = bundled();
        String html = new String(ui.get("index.html").data(), StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        Matcher scripts = Pattern.compile("<script\\b([^>]*)>([\\s\\S]*?)</script>").matcher(html);
        while (scripts.find()) {
            if (!scripts.group(2).isBlank()) problems.add("inline script");
            if (!Pattern.compile("src=\"/assets/[^\"]+-[A-Za-z0-9_-]{6,}\\.js\"").matcher(scripts.group(1)).find()) problems.add("unhashed script " + scripts.group(1));
        }
        if (html.contains("<style")) problems.add("inline <style>");
        if (Pattern.compile("\\son[a-z]+=", Pattern.CASE_INSENSITIVE).matcher(html).find()) problems.add("inline handler");
        if (Pattern.compile("\\sstyle=", Pattern.CASE_INSENSITIVE).matcher(html).find()) problems.add("inline style attribute");
        for (String name : ui.keySet())
            if (name.startsWith("assets/") && !name.matches("assets/[^/]+-[A-Za-z0-9_-]{6,}\\.(js|css|svg|png|woff2?)")) problems.add("unhashed asset " + name);
        assertEquals(List.of(), problems);
    }

    @Test void givenBundledUiThenDispatcherServesAppRoutesWithCspAndImmutableAssets() throws Exception {
        Map<String, StaticAssets.Asset> ui = bundled();
        try (WebFixture web = new WebFixture(dir, "", ui)) {
            for (String path : List.of("/", "/login", "/app/settings", "/app/customtrees/x")) {
                HttpResponseData r = web.dispatcher.handle(WebFixture.get(path, null));
                assertEquals(200, r.status(), path);
                assertTrue(r.header("Content-Type").startsWith("text/html"), path);
                assertEquals(WebDispatcher.CSP, r.header("Content-Security-Policy"), path);
                assertEquals("no-cache", r.header("Cache-Control"), path);
            }
            String asset = ui.keySet().stream().filter(k -> k.startsWith("assets/") && k.endsWith(".js")).findFirst().orElseThrow();
            HttpResponseData r = web.dispatcher.handle(WebFixture.get("/" + asset, null));
            assertEquals(200, r.status());
            assertEquals("public, max-age=31536000, immutable", r.header("Cache-Control"));
            assertEquals(404, web.dispatcher.handle(WebFixture.get("/assets/missing-abcdefgh.js", null)).status());
            assertEquals(401, web.dispatcher.handle(WebFixture.get("/core/session", null)).status(), "Core paths keep precedence");
        }
    }

    @Test void givenBuildOnDiskThenItWinsOverTheBundledUiElseBundledIsUsed() throws Exception {
        ClassLoader loader = WebPlatform.class.getClassLoader();
        WebPlatform.UiChoice none = WebPlatform.loadUi(dir, "web/ui", WebPlatform.class, loader, null);
        assertTrue(none.source().startsWith("bundled vystorm-web-ui "), none.source());
        assertTrue(none.files().containsKey("index.html"));
        Path ui = dir.resolve("web/ui");
        Files.createDirectories(ui.resolve("assets"));
        Files.writeString(ui.resolve("assets/app-abcdef12.js"), "/* no index yet */");
        assertTrue(WebPlatform.loadUi(dir, "web/ui", WebPlatform.class, loader, null).source().startsWith("bundled"), "a build without index.html is ignored");
        Files.writeString(ui.resolve("index.html"), "<!doctype html><title>own build</title>");
        WebPlatform.UiChoice own = WebPlatform.loadUi(dir, "web/ui", WebPlatform.class, loader, null);
        assertEquals("web/ui", own.source());
        assertEquals("<!doctype html><title>own build</title>", new String(own.files().get("index.html").data(), StandardCharsets.UTF_8));
        assertTrue(WebPlatform.loadUi(dir, "", WebPlatform.class, loader, null).source().startsWith("bundled"), "empty ui-dir: bundled");
    }
}
