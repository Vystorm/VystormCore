package Vystorm.vystorm_core.lang.internal;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translator workflow of {@code /vadmin language export|import}: a file with every key, its English source and the
 * current translation ({@code plugins/vystorm_core/translations/<plugin>.<lang>.yml} or {@code .csv}), and the merge of
 * a reviewed file into the plugin's editable lang folder ({@code plugins/<Plugin>/lang/<lang>.yml}, never the jar),
 * with a backup of the previous file. Keys unknown to the English source and texts whose {@code {placeholders}} differ
 * from the source are skipped and reported. Core-internal.
 */
public final class TranslationFiles {
    public enum Format {
        YAML("yml"), CSV("csv");
        public final String extension;
        Format(String extension) { this.extension = extension; }

        public static Optional<Format> parse(String value) {
            String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (v) {
                case "yml", "yaml" -> Optional.of(YAML);
                case "csv" -> Optional.of(CSV);
                default -> Optional.empty();
            };
        }
    }

    /** Status of a key in the exported language. */
    public static final String TRANSLATED = "translated", MISSING = "missing", UNTRANSLATED = "untranslated", PLACEHOLDERS = "placeholders";

    /** One exported line. {@code source}/{@code translation}: String, List of Strings, or null (missing). */
    public record Entry(String key, String status, Object source, Object translation) {}

    public record Export(Path file, int count, int missing) {}

    /** Result of an import; {@code skipped} entries are key → reason ({@code unknown-key}, {@code placeholders}). */
    public record Import(String namespace, String language, int added, int changed, int unchanged,
                         Map<String, String> skipped, Path target, Path backup) {}

    /** A user-facing failure; {@code reason} is a message key suffix (admin.language-error-…). */
    public static final class TranslationException extends Exception {
        public final String reason;
        public final String detail;
        public TranslationException(String reason, String detail) { super(reason + ": " + detail); this.reason = reason; this.detail = detail == null ? "" : detail; }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,127}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private TranslationFiles() {}

    // ------------------------------------------------------------------ export

    /** All English keys of the bundle with source, current translation (exactly that language) and status. */
    public static List<Entry> entries(Lang bundle, String language) {
        String code = Localization.normalize(language);
        List<Entry> out = new ArrayList<>();
        for (String key : bundle.keys()) {
            Object source = bundle.translation(Localization.FALLBACK, key).orElse(null);
            Object translation = bundle.translation(code, key).orElse(null);
            String status;
            if (translation == null) status = MISSING;
            else if (!placeholders(translation).equals(placeholders(source))) status = PLACEHOLDERS;
            else if (!Localization.languagePart(code).equals(Localization.FALLBACK) && Objects.equals(text(translation), text(source))
                    && text(source).codePoints().anyMatch(Character::isLetter)) status = UNTRANSLATED;
            else status = TRANSLATED;
            out.add(new Entry(key, status, source, translation));
        }
        return out;
    }

    /** {@code <namespace>.<lang>.<ext>}. */
    public static String fileName(String namespace, String language, Format format) {
        return namespace + "." + Localization.normalize(language) + "." + format.extension;
    }

    /** Writes the export file into {@code directory} (created if needed), replacing an older export of the same name. */
    public static Export export(Lang bundle, String language, Format format, Path directory) throws IOException {
        String code = Localization.normalize(language);
        List<Entry> entries = entries(bundle, code);
        Files.createDirectories(directory);
        Path file = directory.resolve(fileName(bundle.namespace(), code, format));
        byte[] data = format == Format.CSV ? csv(entries) : yaml(bundle.namespace(), code, entries).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, data);
        int missing = (int) entries.stream().filter(e -> e.status().equals(MISSING)).count();
        return new Export(file, entries.size(), missing);
    }

    static String yaml(String namespace, String language, List<Entry> entries) {
        YamlConfiguration out = new YamlConfiguration();
        out.options().setHeader(List.of(
                "Vystorm translation file: " + namespace + " / " + language + " (source language: en).",
                "Translate the 'translation' values; keep every {placeholder}, MiniMessage tag (<red>, <bold>, ...) and & colour code.",
                "status: missing = no text yet, untranslated = same as English, placeholders = {…} differ from the source.",
                "Entries without a translation are ignored. Import: /vadmin language import " + fileName(namespace, language, Format.YAML)));
        out.set("plugin", namespace);
        out.set("language", language);
        out.set("source-language", Localization.FALLBACK);
        out.set("exported", LocalDateTime.now().withNano(0).toString());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.key());
            m.put("status", e.status());
            m.put("source", e.source() == null ? "" : e.source());
            m.put("translation", e.translation() == null ? "" : e.translation());
            list.add(m);
        }
        out.set("entries", list);
        return out.saveToString();
    }

    static byte[] csv(List<Entry> entries) {
        StringBuilder out = new StringBuilder("﻿");    // BOM: spreadsheet programs then read UTF-8 (CJK) correctly
        out.append("key,status,source,translation\r\n");
        for (Entry e : entries)
            out.append(csvField(e.key())).append(',').append(csvField(e.status())).append(',')
                    .append(csvField(text(e.source()))).append(',').append(csvField(e.translation() == null ? "" : text(e.translation()))).append("\r\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvField(String value) {
        String v = value == null ? "" : value;
        boolean quote = v.isEmpty() || v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || !v.equals(v.strip()) || v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@");
        return quote ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    // ------------------------------------------------------------------ import

    /** Parsed import file: target and translations (key → String or List). */
    public record Parsed(String namespace, String language, Map<String, Object> translations) {}

    /**
     * Reads a translation file. Plugin and language come from the YAML header, else from the file name
     * {@code <plugin>.<lang>.yml|csv}; {@code namespace}/{@code language} arguments override both.
     */
    public static Parsed read(Path file, String namespace, String language) throws IOException, TranslationException {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String ns = null, lang = null;
        Map<String, Object> translations = new LinkedHashMap<>();
        if (lower.endsWith(".csv")) {
            List<List<String>> rows = parseCsv(text);
            if (rows.isEmpty()) throw new TranslationException("empty", name);
            List<String> header = rows.getFirst().stream().map(h -> h.trim().toLowerCase(java.util.Locale.ROOT)).toList();
            int key = header.indexOf("key"), value = header.indexOf("translation");
            if (key < 0 || value < 0) throw new TranslationException("csv-header", name);
            for (List<String> row : rows.subList(1, rows.size()))
                if (row.size() > Math.max(key, value)) translations.put(row.get(key).trim(), row.get(value));
        } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            YamlConfiguration yaml = new YamlConfiguration();
            try { yaml.loadFromString(text); }
            catch (InvalidConfigurationException broken) { throw new TranslationException("invalid", broken.getMessage()); }
            ns = yaml.getString("plugin");
            lang = yaml.getString("language");
            for (Map<?, ?> entry : yaml.getMapList("entries")) {
                Object k = entry.get("key"), t = entry.get("translation");
                if (k != null) translations.put(String.valueOf(k).trim(), t instanceof List<?> l ? l.stream().map(String::valueOf).toList() : t == null ? "" : String.valueOf(t));
            }
        } else throw new TranslationException("format", name);
        String[] parts = name.split("\\.");
        if (parts.length >= 3) {
            if (ns == null || ns.isBlank()) ns = String.join(".", java.util.Arrays.copyOfRange(parts, 0, parts.length - 2));
            if (lang == null || lang.isBlank()) lang = parts[parts.length - 2];
        }
        if (namespace != null && !namespace.isBlank()) ns = namespace;
        if (language != null && !language.isBlank()) lang = language;
        if (ns == null || ns.isBlank() || lang == null || lang.isBlank()) throw new TranslationException("target", name);
        String code = Localization.normalize(lang);
        if (!code.matches("[a-z]{2,8}(_[a-z0-9]{1,8})*")) throw new TranslationException("language", lang);
        return new Parsed(ns.trim().toLowerCase(java.util.Locale.ROOT), code, translations);
    }

    /**
     * Merges a reviewed file into the bundle's editable lang folder: non-empty translations of known keys with the
     * source's placeholders are written into {@code <folder>/<lang>.yml} (created from the jar copy or empty), other
     * texts there stay as they are. The previous file is kept as {@code <lang>.yml.bak-<yyyyMMdd-HHmmss>}; nothing is
     * written when nothing changes. The bundle is reloaded afterwards.
     */
    public static Import importFile(Path file, Function<String, Optional<Lang>> bundles, String namespace, String language)
            throws IOException, TranslationException {
        Parsed parsed = read(file, namespace, language);
        Lang bundle = bundles.apply(parsed.namespace()).orElseThrow(() -> new TranslationException("plugin", parsed.namespace()));
        return merge(bundle, parsed);
    }

    /** {@link #importFile} for already parsed content. */
    public static Import merge(Lang bundle, Parsed parsed) throws IOException, TranslationException {
        File folder = bundle.folder().orElseThrow(() -> new TranslationException("no-folder", bundle.namespace()));
        if (parsed.language().equals(Localization.FALLBACK)) throw new TranslationException("source-language", Localization.FALLBACK);
        Path target = folder.toPath().resolve(parsed.language() + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.isRegularFile(target)) {
            try { yaml.loadFromString(Files.readString(target, StandardCharsets.UTF_8)); }
            catch (InvalidConfigurationException broken) { throw new TranslationException("target-invalid", target.toString()); }
        } else {
            yaml.options().setHeader(List.of(bundle.namespace() + " – " + parsed.language() + " (imported translation; keys missing here fall back to "
                    + String.join(" → ", Localization.get().fallbackChain(parsed.language()).subList(1, Localization.get().fallbackChain(parsed.language()).size())) + ")"));
        }
        Set<String> known = bundle.keys();
        Map<String, String> skipped = new LinkedHashMap<>();
        int added = 0, changed = 0, unchanged = 0;
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(parsed.translations()).entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (value == null || text(value).isBlank()) continue;
            if (!known.contains(key)) { skipped.put(key, "unknown-key"); continue; }
            Object source = bundle.translation(Localization.FALLBACK, key).orElse(null);
            if (!placeholders(value).equals(placeholders(source))) { skipped.put(key, "placeholders"); continue; }
            Object shaped = source instanceof List<?> && value instanceof String s ? List.of(s.split("\n", -1))
                    : !(source instanceof List<?>) && value instanceof List<?> l ? String.join("\n", l.stream().map(String::valueOf).toList()) : value;
            Object current = yaml.get(key);
            if (current instanceof org.bukkit.configuration.ConfigurationSection) { skipped.put(key, "unknown-key"); continue; }
            if (current != null && text(current).equals(text(shaped))) { unchanged++; continue; }
            if (current == null) added++; else changed++;
            yaml.set(key, shaped);
        }
        Path backup = null;
        if (added + changed > 0) {
            Files.createDirectories(folder.toPath());
            if (Files.isRegularFile(target)) {
                backup = target.resolveSibling(target.getFileName() + ".bak-" + LocalDateTime.now().format(STAMP));
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            AtomicFiles.write(target, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
            bundle.reload();
        }
        return new Import(bundle.namespace(), parsed.language(), added, changed, unchanged, skipped, target, backup);
    }

    /** Resolves a file name inside {@code directory}; rejects paths, hidden names and anything outside. */
    public static Path resolve(Path directory, String name) throws TranslationException {
        if (name == null || !FILE_NAME.matcher(name).matches() || name.contains("..")) throw new TranslationException("file-name", String.valueOf(name));
        Path dir = directory.toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.getParent().equals(dir)) throw new TranslationException("file-name", name);
        if (!Files.isRegularFile(file)) throw new TranslationException("file-missing", name);
        return file;
    }

    /** Translation files in {@code directory} (yml/yaml/csv), sorted. */
    public static List<String> files(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".csv"))
                    .filter(n -> FILE_NAME.matcher(n).matches()).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ helpers

    static String text(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> l) return String.join("\n", l.stream().map(String::valueOf).toList());
        return String.valueOf(value);
    }

    static Set<String> placeholders(Object value) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text(value));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** RFC 4180 CSV (quoted fields with commas, quotes and line breaks; CRLF or LF). */
    public static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
                continue;
            }
            if (c == '"') { quoted = true; any = true; }
            else if (c == ',') { row.add(field.toString()); field.setLength(0); any = true; }
            else if (c == '\r') { /* part of CRLF */ }
            else if (c == '\n') {
                row.add(field.toString()); field.setLength(0);
                if (any || row.size() > 1 || !row.getFirst().isEmpty()) rows.add(row);
                row = new ArrayList<>(); any = false;
            } else { field.append(c); any = true; }
        }
        if (any || !field.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
    }
}
