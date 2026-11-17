package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localized HTML pages: tokens in HTML assets are filled per request language.
 * <ul>
 *   <li>{@code {{web.ui.key}}} – text of Core's language bundle, {@code {{plugin:web.key}}} – text of a plugin's bundle
 *       (HTML-escaped, raw text without MiniMessage);</li>
 *   <li>{@code {{@lang}}} – the language code, {@code {{@texts}}} – JSON of Core's {@code web.ui.*} texts for scripts,
 *       {@code {{@languages}}} – JSON {@code [{code, name}]} for a language switch (both attribute-escaped).</li>
 * </ul>
 * Pages without {@code {{} are served unchanged. Core-internal.
 *
 * @since 0.23.0
 */
public final class WebPages {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(@[a-z]+|(?:[a-z0-9_.-]{1,64}:)?[A-Za-z0-9_.-]{1,128})}}");
    private static final Gson GSON = new Gson();

    private WebPages() {}

    static boolean templated(byte[] html) {
        for (int i = 0; i + 1 < html.length; i++) if (html[i] == '{' && html[i + 1] == '{') return true;
        return false;
    }

    /** ETag of the rendered page: the asset's tag plus the language. */
    static String etag(String etag, String language) {
        if (etag == null) return "\"" + language + "\"";
        int end = etag.lastIndexOf('"');
        return end > 0 ? etag.substring(0, end) + "-" + language + "\"" : etag + "-" + language;
    }

    static byte[] render(byte[] html, String language) {
        Lang core = Localization.get().core();
        Matcher m = TOKEN.matcher(new String(html, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(html.length + 256);
        while (m.find()) {
            String token = m.group(1);
            String value = switch (token) {
                case "@lang" -> htmlLanguage(language);
                case "@texts" -> GSON.toJson(core.texts(language, "web.ui."));
                case "@languages" -> GSON.toJson(languages());
                default -> token.startsWith("@") ? m.group() : text(core, token, language);
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value.equals(m.group()) ? value : WebText.html(value)));
        }
        m.appendTail(out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Lang core, String token, String language) {
        int colon = token.indexOf(':');
        if (colon < 0) return core.raw(core.resolveLanguage(language), token);
        Lang bundle = Localization.get().bundle(token.substring(0, colon)).orElse(null);
        String key = token.substring(colon + 1);
        return bundle == null ? key : bundle.raw(bundle.resolveLanguage(language), key);
    }

    /** BCP 47 tag for the HTML {@code lang} attribute ({@code zh_cn} → {@code zh-CN}), so browsers pick CJK fonts right. */
    static String htmlLanguage(String code) {
        String[] parts = Localization.normalize(code).split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) out.append('-').append(parts[i].length() == 2 ? parts[i].toUpperCase(java.util.Locale.ROOT) : parts[i]);
        return out.toString();
    }

    /** Languages for a switch: Core's languages with their own names. */
    static JsonArray languages() {
        Lang core = Localization.get().core();
        JsonArray out = new JsonArray();
        for (String code : core.languages()) {
            JsonObject o = new JsonObject();
            o.addProperty("code", code);
            o.addProperty("name", core.raw(code, "language.name"));
            out.add(o);
        }
        return out;
    }

    /** Body of {@code /core/i18n}. */
    static JsonObject i18n(Lang bundle, String language) {
        JsonObject out = new JsonObject();
        String code = bundle.resolveLanguage(language);
        out.addProperty("language", code);
        out.add("languages", languages());
        JsonObject texts = new JsonObject();
        for (Map.Entry<String, String> e : bundle.texts(code, "web.ui.").entrySet()) texts.addProperty(e.getKey(), e.getValue());
        out.add("texts", texts);
        return out;
    }
}
