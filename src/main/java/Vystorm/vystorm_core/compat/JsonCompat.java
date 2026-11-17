package Vystorm.vystorm_core.compat;

import com.google.gson.stream.JsonReader;
import com.google.gson.JsonSyntaxException;
import java.io.StringReader;

/**
 * Strict JSON syntax across the Gson versions bundled with supported servers.
 * <p>Preferred access: {@code api.compat().json()}.
 */
public final class JsonCompat {
    private JsonCompat() {}

    /** Strict JSON reader, including the syntax that older Gson's non-lenient mode accepts. */
    public static JsonReader reader(String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { quoted = !quoted; continue; }
            if (quoted) {
                if (c < 0x20) throw new JsonSyntaxException("Unescaped control character");
                if (c == '\\') {
                    if (++i == text.length()) throw new JsonSyntaxException("Incomplete escape");
                    char escaped = text.charAt(i);
                    if (escaped == 'u') {
                        for (int n = 0; n < 4; n++) {
                            if (++i == text.length() || Character.digit(text.charAt(i), 16) < 0)
                                throw new JsonSyntaxException("Invalid unicode escape");
                        }
                    } else if ("\"\\/bfnrt".indexOf(escaped) < 0) throw new JsonSyntaxException("Invalid escape");
                }
            } else if ("{}[]:, \t\r\n0123456789.-+eEtruefalsn".indexOf(c) < 0) {
                throw new JsonSyntaxException("Invalid JSON token");
            }
        }
        if (quoted) throw new JsonSyntaxException("Unterminated string");
        JsonReader reader = new JsonReader(new StringReader(text));
        strict(reader);
        return reader;
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    public static void strict(JsonReader reader) {
        try {
            Class type = Class.forName("com.google.gson.Strictness");
            JsonReader.class.getMethod("setStrictness", type).invoke(reader, Enum.valueOf(type, "STRICT"));
        } catch (ClassNotFoundException | NoSuchMethodException oldGson) {
            reader.setLenient(false);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot configure JSON reader", failure);
        }
    }
}
