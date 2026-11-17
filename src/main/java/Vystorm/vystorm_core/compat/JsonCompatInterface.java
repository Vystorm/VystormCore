package Vystorm.vystorm_core.compat;

import com.google.gson.stream.JsonReader;
import com.google.gson.JsonSyntaxException;
import java.io.StringReader;

/**
 * Manager view of {@link JsonCompat}, reached through {@code api.compat().json()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link JsonCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface JsonCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().json()}. */
    JsonCompatInterface DEFAULT = new JsonCompatInterface() {};

    /** Strict JSON reader, including the syntax that older Gson's non-lenient mode accepts. */
    default JsonReader reader(String text) { return JsonCompat.reader(text); }

    /** See {@link JsonCompat#strict}. */
    default void strict(JsonReader reader) { JsonCompat.strict(reader); }
}
