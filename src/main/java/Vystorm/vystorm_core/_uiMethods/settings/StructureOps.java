package Vystorm.vystorm_core._uiMethods.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Edits lists of mappings in config files without touching the input; every result is a deep copy. @since 0.54.0 */
final class StructureOps {
    private StructureOps() {}

    static boolean isStructure(Object value) {
        return value instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(v -> v instanceof Map<?, ?> || v instanceof ConfigurationSection);
    }
    static Object copy(Object value) {
        if (value instanceof ConfigurationSection s) value = s.getValues(false);
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(copy(v)));
            return out;
        }
        return value;
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> entries(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : raw) { Object c = copy(v); if (c instanceof Map<?, ?>) out.add((Map<String, Object>) c); }
        return out;
    }
    /** Appends a copy of {@code template}. */
    static List<Map<String, Object>> add(List<Map<String, Object>> list, Map<String, Object> template) {
        List<Map<String, Object>> out = entries(list);
        out.addAll(entries(List.of(template)));
        return out;
    }
    static List<Map<String, Object>> remove(List<Map<String, Object>> list, int index) {
        List<Map<String, Object>> out = entries(list);
        out.remove(index);
        return out;
    }
    /** Sets one field; {@code field} uses dots for nested mappings and must already exist. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> set(List<Map<String, Object>> list, int index, String field, Object value) {
        List<Map<String, Object>> out = entries(list);
        Map<String, Object> at = out.get(index);
        String[] parts = field.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(at.get(parts[i]) instanceof Map<?, ?> next)) throw new IllegalArgumentException("No field " + field);
            at = (Map<String, Object>) next;
        }
        if (!at.containsKey(parts[parts.length - 1])) throw new IllegalArgumentException("No field " + field);
        at.put(parts[parts.length - 1], value);
        return out;
    }
    /** Editable fields in order: plain values and lists of plain values, nested mappings flattened with dots. */
    static LinkedHashMap<String, Object> fields(Map<String, Object> entry) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        collect("", entry, out);
        return out;
    }
    private static void collect(String prefix, Map<?, ?> map, Map<String, Object> out) {
        map.forEach((k, v) -> {
            String key = prefix + k;
            if (String.valueOf(k).contains(".")) return;
            if (v instanceof Map<?, ?> nested) collect(key + ".", nested, out);
            else if (v instanceof List<?> list) { if (list.stream().noneMatch(x -> x instanceof Map<?, ?> || x instanceof List<?>)) out.put(key, v); }
            else if (v != null) out.put(key, v);
        });
    }
    /** A typed field value parsed like its current value; {@code null} if the text does not fit. */
    static Object parse(Object current, String text) {
        String t = text.trim();
        try {
            if (current instanceof Boolean) return t.equalsIgnoreCase("true") ? Boolean.TRUE : t.equalsIgnoreCase("false") ? Boolean.FALSE : null;
            if (current instanceof Integer) return Integer.parseInt(t);
            if (current instanceof Long) return Long.parseLong(t);
            if (current instanceof Number) { double d = Double.parseDouble(t.replace(',', '.')); return Double.isFinite(d) ? d : null; }
        } catch (NumberFormatException e) { return null; }
        if (current instanceof List<?> list) {
            Object sample = list.isEmpty() ? "" : list.getFirst();
            List<Object> out = new ArrayList<>();
            for (String part : t.split(",")) {
                if (part.isBlank()) continue;
                Object parsed = parse(sample, part);
                if (parsed == null) return null;
                out.add(parsed);
            }
            return out;
        }
        return text;
    }
}
