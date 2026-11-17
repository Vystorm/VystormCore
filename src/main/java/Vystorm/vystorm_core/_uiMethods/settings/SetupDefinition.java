package Vystorm.vystorm_core._uiMethods.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The guided first-run setup a plugin ships as {@code setup.yml} in its jar. Parsed with plain SnakeYAML because targets
 * such as {@code config.yml:cold.damage} contain dots, which Bukkit would turn into nested keys. Every target is checked
 * against the jar defaults, so a broken setup is rejected as a whole. @since 0.54.0
 */
public record SetupDefinition(Map<String, String> intro, List<Question> questions) {
    public static final String FILE = "setup.yml";
    public enum Type { CHOICE, NUMBER, TEXT }
    public record Target(String file, String path) {
        @Override public String toString() { return file + ":" + path; }
    }
    public record Option(String id, Map<String, String> label, Map<Target, Object> set) {}
    public record Question(String id, Map<String, String> text, Type type, String recommended, List<Option> options,
                           Target target, boolean whole, double min, double max) {
        public Option option(String id) { return options.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null); }
        /** The value of a typed number answer, {@code null} if it is not valid for this question. */
        public Number number(String raw) {
            double value;
            try { value = Double.parseDouble(raw.trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
            if (!Double.isFinite(value) || value < min || value > max) return null;
            if (!whole) return value;
            if (value != Math.rint(value)) return null;
            return Math.abs(value) <= Integer.MAX_VALUE ? (Number) (int) value : (Number) (long) value;
        }
    }
    public static final class Invalid extends Exception {
        Invalid(String message) { super(message); }
    }

    public static SetupDefinition parse(String text, Function<Target, Object> defaults, Predicate<String> secret) throws Invalid {
        Object root;
        try { root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text); }
        catch (RuntimeException e) { throw new Invalid("not valid YAML: " + e.getMessage()); }
        if (!(root instanceof Map<?, ?> map)) throw new Invalid("no settings found");
        if (!Integer.valueOf(1).equals(map.get("version"))) throw new Invalid("unsupported version " + map.get("version"));
        if (!(map.get("questions") instanceof List<?> raw) || raw.isEmpty()) throw new Invalid("no questions");
        Map<String, String> intro = map.get("intro") == null ? Map.of() : texts(map.get("intro"), "intro");
        List<Question> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> q)) throw new Invalid("a question is not a mapping");
            Question question = question(q, defaults, secret);
            if (!ids.add(question.id())) throw new Invalid("duplicate question " + question.id());
            questions.add(question);
        }
        return new SetupDefinition(intro, List.copyOf(questions));
    }

    private static Question question(Map<?, ?> q, Function<Target, Object> defaults, Predicate<String> secret) throws Invalid {
        String id = id(q.get("id"), "question");
        Map<String, String> text = texts(q.get("text"), id);
        Type type;
        try { type = Type.valueOf(String.valueOf(q.get("type")).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new Invalid(id + ": unknown type " + q.get("type")); }
        if (type != Type.CHOICE) {
            Target target = target(q.get("target"), id);
            Object def = known(target, defaults, secret, id);
            boolean number = type == Type.NUMBER;
            if (number ? !(def instanceof Number) : !(def instanceof String)) throw new Invalid(id + ": wrong type for " + target);
            boolean whole = number && !(def instanceof Double || def instanceof Float);
            double min = q.get("min") instanceof Number n ? n.doubleValue() : whole ? Integer.MIN_VALUE : -Double.MAX_VALUE;
            double max = q.get("max") instanceof Number n ? n.doubleValue() : whole ? Integer.MAX_VALUE : Double.MAX_VALUE;
            return new Question(id, text, type, null, List.of(), target, whole, min, max);
        }
        if (!(q.get("options") instanceof List<?> raw) || raw.size() < 2) throw new Invalid(id + ": needs at least two options");
        List<Option> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> o)) throw new Invalid(id + ": an option is not a mapping");
            String optionId = id(o.get("id"), id + " option");
            if (!seen.add(optionId)) throw new Invalid(id + ": duplicate option " + optionId);
            if (!(o.get("set") instanceof Map<?, ?> set) || set.isEmpty()) throw new Invalid(id + "/" + optionId + ": nothing to set");
            Map<Target, Object> values = new LinkedHashMap<>();
            for (var e : set.entrySet()) {
                Target target = target(e.getKey(), id);
                Object def = known(target, defaults, secret, id);
                if (!compatible(def, e.getValue())) throw new Invalid(id + "/" + optionId + ": wrong type for " + target);
                values.put(target, e.getValue());
            }
            options.add(new Option(optionId, texts(o.get("label"), id + "/" + optionId), Map.copyOf(values)));
        }
        String recommended = q.get("recommended") == null ? null : String.valueOf(q.get("recommended"));
        if (recommended != null && !seen.contains(recommended)) throw new Invalid(id + ": recommended option " + recommended + " does not exist");
        return new Question(id, text, type, recommended, List.copyOf(options), null, false, 0, 0);
    }

    private static Object known(Target target, Function<Target, Object> defaults, Predicate<String> secret, String id) throws Invalid {
        if (secret.test(target.path())) throw new Invalid(id + ": secret target " + target);
        Object def = defaults.apply(target);
        if (def == null) throw new Invalid(id + ": unknown target " + target);
        return def;
    }
    private static boolean compatible(Object def, Object value) {
        if (def instanceof Boolean) return value instanceof Boolean;
        if (def instanceof Double || def instanceof Float) return value instanceof Number;
        if (def instanceof Number) return value instanceof Integer || value instanceof Long;
        if (def instanceof String) return value instanceof String;
        if (def instanceof List<?>) return value instanceof List<?>;
        return false;
    }
    private static Target target(Object raw, String id) throws Invalid {
        String text = raw == null ? "" : String.valueOf(raw);
        int colon = text.indexOf(':');
        if (colon < 1 || colon == text.length() - 1) throw new Invalid(id + ": target must be file:path, was " + text);
        return new Target(text.substring(0, colon), text.substring(colon + 1));
    }
    private static String id(Object raw, String what) throws Invalid {
        if (raw == null || !String.valueOf(raw).matches("[a-z0-9_-]{1,40}")) throw new Invalid(what + ": invalid id " + raw);
        return String.valueOf(raw);
    }
    private static Map<String, String> texts(Object raw, String what) throws Invalid {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) throw new Invalid(what + ": text is missing");
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) out.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), String.valueOf(e.getValue()));
        return out;
    }

    /** Text for a language: exact, then the base language, then English, then the first entry. */
    public static String text(Map<String, String> texts, String language) {
        String code = language == null ? "en" : language.toLowerCase(Locale.ROOT);
        String found = texts.get(code);
        if (found == null && code.contains("_")) found = texts.get(code.substring(0, code.indexOf('_')));
        if (found == null) found = texts.get("en");
        return found != null ? found : texts.isEmpty() ? "" : texts.values().iterator().next();
    }

    /** The changes of all recommended options; questions without a recommendation change nothing. */
    public Map<Target, Object> recommended() {
        Map<Target, Object> out = new LinkedHashMap<>();
        for (Question q : questions) if (q.recommended() != null) out.putAll(q.option(q.recommended()).set());
        return out;
    }

    /** The changes of the given answers (question id → option id or typed value); invalid or empty answers are skipped. */
    public Map<Target, Object> changes(Map<String, String> answers) {
        Map<Target, Object> out = new LinkedHashMap<>();
        for (Question q : questions) {
            String answer = answers.get(q.id());
            if (answer == null || answer.isBlank()) continue;
            switch (q.type()) {
                case CHOICE -> { Option o = q.option(answer); if (o != null) out.putAll(o.set()); }
                case NUMBER -> { Number n = q.number(answer); if (n != null) out.put(q.target(), n); }
                case TEXT -> out.put(q.target(), answer);
            }
        }
        return out;
    }
}
