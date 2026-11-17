package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Texts of the web platform. Error messages are stored as keys ({@code web.error.<id>}, optionally with
 * {@code {name}} arguments) and turned into text in the request's language when the response is written; a
 * {@code Lang.ref} message from a plugin's {@code WebException} is resolved the same way; anything else is sent as is.
 * Core-internal.
 *
 * @since 0.23.0
 */
public final class WebText {
    public static final String ERROR = "web.error.";
    /** Cookie of the manual language switch in the web pages ({@code en}, {@code de}, …). */
    public static final String LANGUAGE_COOKIE = "vystorm_lang";
    private static final char SEP = '\u0000';

    private WebText() {}

    /** An error message key with optional {@code name, value} pairs, resolved later per request. */
    public static String error(String id, Object... namesAndValues) {
        StringBuilder out = new StringBuilder(ERROR).append(id);
        if (namesAndValues != null) for (Object part : namesAndValues) out.append(SEP).append(part == null ? "" : part);
        return out.toString();
    }

    /** Text of a message for {@code language}: {@link #error} keys and {@code Lang.ref} references are translated. */
    public static String localize(String message, String language) {
        if (message == null) return "";
        try {
            if (message.startsWith(ERROR)) {
                String[] parts = message.split(String.valueOf(SEP), -1);
                Lang core = Localization.get().core();
                if (!core.has(parts[0])) return message;
                List<Object> args = new ArrayList<>();
                for (int i = 1; i + 1 < parts.length; i += 2) { args.add(parts[i]); args.add(parts[i + 1]); }
                return core.plain(core.resolveLanguage(language), parts[0], args.toArray());
            }
            return Localization.get().resolveIn(language, message);
        } catch (RuntimeException | LinkageError failure) {
            return message;
        }
    }

    /** Language when nothing about the client is known: a fixed {@code language.default}, else English. */
    public static String fallbackLanguage() {
        try { return Localization.get().core().languageFor((UUID) null, List.of()); }
        catch (RuntimeException | LinkageError failure) { return Localization.FALLBACK; }
    }

    /**
     * Language of a web request: the manual switch (cookie {@value #LANGUAGE_COOKIE}), then for a known player the
     * player's language (own choice, fixed default, last client locale), then the browser's {@code Accept-Language},
     * then English.
     */
    public static String language(HttpRequestData request, UUID player) {
        try {
            Lang core = Localization.get().core();
            String manual = Localization.normalize(cookie(request, LANGUAGE_COOKIE));
            if (!Localization.get().forced() && !manual.isEmpty() && manual.length() <= 16 && core.languages().contains(manual)) return manual;
            List<String> browser = Localization.acceptLanguages(request == null ? null : request.header("accept-language"));
            return core.languageFor(player, browser);
        } catch (RuntimeException | LinkageError failure) {
            return Localization.FALLBACK;
        }
    }

    /** Value of a cookie, or null. */
    static String cookie(HttpRequestData request, String name) {
        if (request == null || request.headers() == null) return null;
        List<String> values = request.headers().get("cookie");
        if (values == null) return null;
        for (String header : values) {
            for (String part : header.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** Escapes text for HTML element content and attribute values. */
    static String html(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
