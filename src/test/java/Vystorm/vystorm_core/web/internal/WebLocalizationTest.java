package Vystorm.vystorm_core.web.internal;

import static Vystorm.vystorm_core.web.internal.WebFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebLocalizationTest {
    @TempDir Path dir;
    private WebFixture f;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        f = new WebFixture(dir, "");
        f.players.online.add(alice);
        var core = Localization.get().core();
        f.register(WebModule.builder("demo", core.ref("web.module.profile.title"))
                .description(core.ref("web.module.profile.description"))
                .asset("index.html", "<html lang=\"{{@lang}}\"><p>{{web.ui.profile.heading}}</p><p>{{vystorm_core:web.ui.profile.send}}</p><i>{{@nothing}}</i>"
                        .getBytes(StandardCharsets.UTF_8))
                .route(WebRoute.get("/ref", r -> { throw new WebException(409, core.ref("web.error.must-be-online")); }).build())
                .route(WebRoute.get("/plain", r -> { throw new WebException(409, "Nicht genug Punkte."); }).build())
                .route(WebRoute.get("/lang", r -> WebResponse.json(Map.of("language", r.language()))).build())
                .build());
    }

    @AfterEach
    void tearDown() {
        Localization.get().setPersonalLanguage(alice, null);
        Localization.get().rememberClientLocale(alice, null);
        Localization.get().setDefaultLanguage(Localization.AUTO);
        f.close();
    }

    private static JsonObject json(HttpResponseData r) { return JsonParser.parseString(r.bodyText()).getAsJsonObject(); }

    private static HttpRequestData anon(String target, String acceptLanguage, String cookie) {
        var h = headers(cookie, null, null);
        if (acceptLanguage != null) h = with(h, "accept-language", acceptLanguage);
        return request("GET", target, h, null);
    }

    @Test
    void givenAnonymousRequest_whenError_thenBrowserLanguageElseEnglish() {
        assertEquals("Nicht gefunden.", json(f.dispatcher.handle(anon("/nothing", "de-AT,de;q=0.9,en;q=0.5", null))).get("message").getAsString());
        assertEquals("Non trouvé.", json(f.dispatcher.handle(anon("/nothing", "fr-FR,fr;q=0.9", null))).get("message").getAsString());
        assertEquals("Not found.", json(f.dispatcher.handle(anon("/nothing", "es-ES", null))).get("message").getAsString());
        assertEquals("Not found.", json(f.dispatcher.handle(anon("/nothing", null, null))).get("message").getAsString());
        assertEquals("Not logged in. Get a login link in game with /web.", json(f.dispatcher.handle(anon("/core/session", "en", null))).get("message").getAsString());
    }

    @Test
    void givenChineseBrowser_whenI18nRequested_thenSimplifiedChineseServed() {
        for (String accept : List.of("zh-CN,zh;q=0.9", "zh-TW", "zh")) {
            JsonObject body = json(f.dispatcher.handle(anon("/core/i18n", accept, null)));
            assertEquals("zh_cn", body.get("language").getAsString(), accept);
            assertEquals("退出登录", body.getAsJsonObject("texts").get("header.logout").getAsString());
        }
        assertEquals("未找到。", json(f.dispatcher.handle(anon("/nothing", "zh-CN", null))).get("message").getAsString());
        assertTrue(json(f.dispatcher.handle(anon("/core/i18n", "en", null))).getAsJsonArray("languages").toString().contains("简体中文"));
        assertEquals("zh-CN", WebPages.htmlLanguage("zh_cn"));
        assertEquals("de", WebPages.htmlLanguage("de"));
    }

    @Test
    void givenLanguageCookie_whenRequested_thenManualChoiceWins() {
        assertEquals("Nicht gefunden.", json(f.dispatcher.handle(anon("/nothing", "en-US", "vystorm_lang=de"))).get("message").getAsString());
        assertEquals("Nicht gefunden.", json(f.dispatcher.handle(anon("/nothing", "de", "vystorm_lang=xx"))).get("message").getAsString());
    }

    @Test
    void givenSessionPlayer_whenPersonalChoiceOrClientLocale_thenPlayerLanguageBeatsBrowser() {
        Login login = f.login(alice, "Alice");
        String cookie = login.cookie();
        Localization.get().rememberClientLocale(alice, "de_de");
        JsonObject session = json(f.dispatcher.handle(anon("/core/session", "en-US", cookie)));
        assertEquals("de", session.get("language").getAsString());
        assertTrue(session.getAsJsonArray("languages").size() >= 2);
        JsonObject demo = session.getAsJsonArray("modules").asList().stream().map(e -> e.getAsJsonObject())
                .filter(m -> m.get("id").getAsString().equals("demo")).findFirst().orElseThrow();
        assertEquals("Profil", demo.get("title").getAsString());
        assertEquals("de", json(f.dispatcher.handle(anon("/api/demo/lang", "en", cookie))).get("language").getAsString());
        Localization.get().setPersonalLanguage(alice, "en");
        assertEquals("en", json(f.dispatcher.handle(anon("/core/session", "de", cookie))).get("language").getAsString());
        assertEquals("You need to be online in game for that.", json(f.dispatcher.handle(anon("/api/demo/ref", "de", cookie))).get("message").getAsString());
        assertEquals("Nicht genug Punkte.", json(f.dispatcher.handle(anon("/api/demo/plain", "en", cookie))).get("message").getAsString());
        Localization.get().setDefaultLanguage("de");
        Localization.get().setPersonalLanguage(alice, null);
        assertEquals("de", json(f.dispatcher.handle(anon("/core/session", "en", cookie))).get("language").getAsString());
    }

    @Test
    void givenHtmlWithTokens_whenServed_thenRenderedPerLanguageWithOwnEtag() {
        HttpResponseData de = f.dispatcher.handle(anon("/m/demo/", "de", null));
        HttpResponseData en = f.dispatcher.handle(anon("/m/demo/", "en", null));
        assertEquals("<html lang=\"de\"><p>Profil</p><p>Senden</p><i>{{@nothing}}</i>", de.bodyText());
        assertEquals("<html lang=\"en\"><p>Profile</p><p>Send</p><i>{{@nothing}}</i>", en.bodyText());
        assertNotEquals(de.header("ETag"), en.header("ETag"));
        assertEquals("Cookie, Accept-Language", de.header("Vary"));
        var again = with(with(headers(null, null, null), "accept-language", "de"), "if-none-match", de.header("ETag"));
        assertEquals(304, f.dispatcher.handle(request("GET", "/m/demo/", again, null)).status());
        var other = with(with(headers(null, null, null), "accept-language", "en"), "if-none-match", de.header("ETag"));
        assertEquals(200, f.dispatcher.handle(request("GET", "/m/demo/", other, null)).status());
    }

    private static String page(String resource, String language) throws Exception {
        try (var in = WebLocalizationTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(WebPages.render(in.readAllBytes(), language), StandardCharsets.UTF_8);
        }
    }

    @Test
    void givenBuiltInPages_whenRendered_thenNoTokensLeftAndLanguageTexts() throws Exception {
        for (String resource : List.of("web/core/index.html", "web/core/login.html", "web/profile/index.html")) {
            for (String language : List.of("de", "en")) {
                String html = page(resource, language);
                assertFalse(html.contains("{{"), resource + " " + language + ": " + html);
                assertTrue(html.contains("lang=\"" + language + "\"") && html.contains("data-vw-texts=\"{&quot;"), html);
            }
        }
        assertTrue(page("web/core/index.html", "de").contains("Lade …"));
        assertTrue(page("web/core/login.html", "en").contains("Vystorm – Log in"));
        assertTrue(page("web/profile/index.html", "en").contains(">Send</button>"));
        assertTrue(page("web/profile/index.html", "de").contains(">Senden</button>"));
    }

    @Test
    void givenI18nEndpoint_whenQueried_thenTextsOfLanguageAndLanguageList() {
        JsonObject de = json(f.dispatcher.handle(anon("/core/i18n", "de", null)));
        assertEquals("de", de.get("language").getAsString());
        assertEquals("Lade …", de.getAsJsonObject("texts").get("index.loading").getAsString());
        assertFalse(de.getAsJsonObject("texts").has("error.not-found"), "server errors are not part of the UI texts");
        JsonObject en = json(f.dispatcher.handle(anon("/core/i18n?ns=vystorm_core", "es", null)));
        assertEquals("Loading …", en.getAsJsonObject("texts").get("index.loading").getAsString());
        assertEquals(404, f.dispatcher.handle(anon("/core/i18n?ns=unknown_plugin", "de", null)).status());
        assertEquals(400, f.dispatcher.handle(anon("/core/i18n?ns=%3Cx%3E", "de", null)).status());
    }

    @Test
    void givenAcceptLanguageHeaders_whenParsed_thenOrderedByQuality() {
        assertEquals(List.of("de_at", "de", "en"), Localization.acceptLanguages("de-AT,de;q=0.9,en;q=0.5"));
        assertEquals(List.of("en", "de"), Localization.acceptLanguages("de;q=0.4, en"));
        assertEquals(List.of(), Localization.acceptLanguages("*;q=1, <script>, ;;"));
        assertEquals(List.of(), Localization.acceptLanguages(null));
        assertEquals(List.of("fr"), Localization.acceptLanguages("fr, de;q=0"));
    }

    @Test
    void givenErrorKeysWithArguments_whenLocalized_thenFilled() {
        String key = WebText.error("param-missing", "name", "id");
        assertEquals("Parameter fehlt: id", WebText.localize(key, "de"));
        assertEquals("Parameter missing: id", WebText.localize(key, "en"));
        assertEquals("free text", WebText.localize("free text", "de"));
        assertEquals("web.error.unknown-key", WebText.localize("web.error.unknown-key", "de"));
    }
}
