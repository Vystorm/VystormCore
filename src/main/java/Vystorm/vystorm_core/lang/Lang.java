package Vystorm.vystorm_core.lang;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Language bundle of one plugin. Files: {@code lang/<code>.yml} in the jar (bundled) and
 * {@code plugins/<Plugin>/lang/<code>.yml} on disk (copied on first start, editable; admins may add more codes).
 * Keys are YAML paths ({@code menu.title}); a value is a string or a list of lines.
 *
 * <p><b>Lookup</b> for language {@code L}: disk {@code L} → bundled {@code L} → disk {@code en} → bundled {@code en} →
 * the key itself. Keys added in a newer plugin version therefore work without touching the admin's files (they are
 * not written into existing files).
 *
 * <p><b>Format</b>: MiniMessage ({@code <red>}, {@code <bold>}, {@code <#ff8800>}, {@code <click:…>}); legacy
 * {@code &c}/{@code §c} codes are accepted too. Placeholders are named, {@code {player}}, and given as name/value
 * pairs: {@code lang.component(player, "key", "player", name, "amount", 5)}. Values are inserted literally (no tag
 * parsing); a {@link net.kyori.adventure.text.ComponentLike} value is inserted as component.
 *
 * <p>Thread-safe; lookups never return {@code null} and never throw.
 *
 * @since 0.22.0
 */
public final class Lang {
    private record Tables(Map<String, Map<String, Object>> bundled, Map<String, Map<String, Object>> disk, Set<String> languages) {}
    private record CacheKey(String language, String key) {}

    private final Localization service;
    private final String namespace;
    private final Plugin plugin;
    private final LangResources resources;
    private final File folder;
    private final Map<CacheKey, Component> cache = new ConcurrentHashMap<>();
    private volatile Tables tables;
    private volatile String override;

    Lang(Localization service, String namespace, Plugin plugin, LangResources resources, File folder) {
        this.service = service;
        this.namespace = namespace;
        this.plugin = plugin;
        this.resources = resources;
        this.folder = folder;
        reload();
    }

    /**
     * A standalone bundle from YAML texts (code → YAML) without files on disk, e.g. for unit tests of a plugin.
     * It is not registered with Core, so {@link #ref}/{@link #translatable} of it do not resolve.
     */
    public static Lang create(String namespace, Map<String, String> yamlByCode) {
        return new Lang(Localization.get(), Localization.namespace(namespace), null, LangResources.of(yamlByCode), null);
    }

    /** Like {@link #create(String, Map)} with an editable folder ({@code <folder>/<code>.yml}); files are copied there. */
    public static Lang create(String namespace, Map<String, String> yamlByCode, File folder) {
        return new Lang(Localization.get(), Localization.namespace(namespace), null, LangResources.of(yamlByCode), folder);
    }

    /** Lower-case plugin name. */
    public String namespace() { return namespace; }

    Plugin plugin() { return plugin; }

    // ------------------------------------------------------------------ loading

    /** Copies missing bundled files to disk and re-reads all files; clears the cache. Safe to call any time. */
    public void reload() {
        Map<String, String> bundledText;
        try { bundledText = resources.load(); }
        catch (RuntimeException failure) { bundledText = Map.of(); DebugMessages.high("[Localization] " + namespace + ": bundled files unreadable: " + failure); }
        Map<String, Map<String, Object>> bundled = new LinkedHashMap<>();
        bundledText.forEach((code, yaml) -> bundled.put(code, parse(yaml, namespace + " jar lang/" + code + ".yml")));
        Map<String, Map<String, Object>> disk = new LinkedHashMap<>();
        if (folder != null) {
            copyMissing(bundledText);
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files != null) for (File file : files) {
                String code = Localization.normalize(file.getName().substring(0, file.getName().length() - 4));
                try { disk.put(code, parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), file.getPath())); }
                catch (IOException | RuntimeException failure) {
                    DebugMessages.high("[Localization] " + file.getPath() + " unreadable, using the bundled texts: " + failure);
                }
            }
        }
        Set<String> languages = new TreeSet<>(bundled.keySet());
        languages.addAll(disk.keySet());
        tables = new Tables(Collections.unmodifiableMap(bundled), Collections.unmodifiableMap(disk), Collections.unmodifiableSet(languages));
        cache.clear();
    }

    private void copyMissing(Map<String, String> bundledText) {
        try {
            Files.createDirectories(folder.toPath());
            for (Map.Entry<String, String> e : bundledText.entrySet()) {
                Path target = folder.toPath().resolve(e.getKey() + ".yml");
                if (!Files.exists(target)) Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
                else {
                    updateUnchangedTexts(target, e.getValue());
                    addMissingKeys(target, e.getValue());
                }
                writeDefaults(target, e.getValue());
            }
        } catch (IOException | RuntimeException failure) {
            DebugMessages.high("[Localization] " + folder + ": copying language files failed: " + failure);
        }
    }

    /**
     * Adds texts that a plugin update introduced to an existing language file on disk, so admins see and can
     * translate them. Existing (possibly customised) texts are never changed; the previous file is kept as
     * {@code <code>.yml.before-update}. Returns how many keys were added.
     */
    static int addMissingKeys(Path target, String bundledYaml) throws IOException {
        YamlConfiguration bundled = new YamlConfiguration(), disk = new YamlConfiguration();
        try {
            bundled.loadFromString(bundledYaml == null ? "" : bundledYaml);
            disk.loadFromString(Files.readString(target, StandardCharsets.UTF_8));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            return 0; // a broken file on disk is reported by the loader; never overwrite it
        }
        int added = 0;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || disk.contains(key, true)) continue;
            disk.set(key, bundled.get(key));
            added++;
        }
        if (added == 0) return 0;
        Files.copy(target, target.resolveSibling(target.getFileName() + ".before-update"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(target, disk.saveToString().getBytes(StandardCharsets.UTF_8));
        DebugMessages.mid("[Localization] " + target + ": added " + added + " new text(s) from the plugin update");
        return added;
    }

    /** The jar texts Core last wrote next to an admin language file: {@code <code>.yml.defaults} (not loaded as a language). */
    static Path defaultsOf(Path target) { return target.resolveSibling(target.getFileName() + ".defaults"); }

    static void writeDefaults(Path target, String bundledYaml) throws IOException {
        Path defaults = defaultsOf(target);
        String text = bundledYaml == null ? "" : bundledYaml;
        if (Files.exists(defaults) && Files.readString(defaults, StandardCharsets.UTF_8).equals(text)) return;
        Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(defaults, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Brings changed default texts to existing servers (since 0.59.0): a text in the admin's file that still equals the
     * jar text Core wrote last time ({@link #defaultsOf}) was never edited, so it takes the plugin's new text. Edited
     * texts stay. Without a defaults file (servers from before 0.59.0) nothing is changed this time. Returns the count.
     */
    static int updateUnchangedTexts(Path target, String bundledYaml) throws IOException {
        Path defaultsFile = defaultsOf(target);
        if (!Files.exists(defaultsFile)) return 0;
        YamlConfiguration bundled = new YamlConfiguration(), disk = new YamlConfiguration(), previous = new YamlConfiguration();
        try {
            bundled.loadFromString(bundledYaml == null ? "" : bundledYaml);
            disk.loadFromString(Files.readString(target, StandardCharsets.UTF_8));
            previous.loadFromString(Files.readString(defaultsFile, StandardCharsets.UTF_8));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            return 0;
        }
        int updated = 0;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || !disk.contains(key, true) || !previous.contains(key, true)) continue;
            Object now = bundled.get(key), was = previous.get(key), admin = disk.get(key);
            if (java.util.Objects.equals(String.valueOf(admin), String.valueOf(was)) && !java.util.Objects.equals(String.valueOf(now), String.valueOf(was))) {
                disk.set(key, now);
                updated++;
            }
        }
        if (updated == 0) return 0;
        Files.copy(target, target.resolveSibling(target.getFileName() + ".before-update"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(target, disk.saveToString().getBytes(StandardCharsets.UTF_8));
        DebugMessages.mid("[Localization] " + target + ": updated " + updated + " unedited text(s) from the plugin update");
        return updated;
    }

    static Map<String, Object> parse(String yaml, String source) {
        YamlConfiguration config = new YamlConfiguration();
        try { config.loadFromString(yaml == null ? "" : yaml); }
        catch (InvalidConfigurationException | RuntimeException failure) {
            DebugMessages.high("[Localization] " + source + " is not valid YAML: " + failure.getMessage());
            return Map.of();
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (String key : config.getKeys(true)) {
            Object value = config.get(key);
            if (value instanceof ConfigurationSection || value == null) continue;
            if (value instanceof List<?> list) flat.put(key, list.stream().map(String::valueOf).toList());
            else flat.put(key, String.valueOf(value));
        }
        return Collections.unmodifiableMap(flat);
    }

    void clearCache() { cache.clear(); }

    // ------------------------------------------------------------------ language choice

    /** Language codes with a bundled or on-disk file. */
    public Set<String> languages() { return tables.languages(); }

    /**
     * Translation coverage without resolving away missing keys. Thread-safe snapshot. {@code untranslated} (0.45.0):
     * keys whose text in this language is identical to the English text (and contains letters), usually copied but
     * not translated yet. @since 0.26.5
     */
    public record Audit(String language, int total, List<String> missing, List<String> bundledFallback,
                        List<String> englishFallback, List<String> placeholderMismatch, List<String> unfilledTags,
                        List<String> untranslated) {
        /** Audit without the untranslated check (before 0.45.0). */
        public Audit(String language, int total, List<String> missing, List<String> bundledFallback,
                     List<String> englishFallback, List<String> placeholderMismatch, List<String> unfilledTags) {
            this(language, total, missing, bundledFallback, englishFallback, placeholderMismatch, unfilledTags, List.of());
        }

        /** Audit without the unfilled-tag check (before 0.40.0). */
        public Audit(String language, int total, List<String> missing, List<String> bundledFallback,
                     List<String> englishFallback, List<String> placeholderMismatch) {
            this(language, total, missing, bundledFallback, englishFallback, placeholderMismatch, List.of(), List.of());
        }

        /** Percent of the keys this language has a text of its own for (0–100). @since 0.45.0 */
        public double coverage() { return total == 0 ? 100.0 : (total - missing.size()) * 100.0 / total; }
    }

    /** Key counts of one language: English keys and how many of them the language has a text of its own for. @since 0.45.0 */
    public record Counts(int total, int translated) {}

    public List<Audit> audit() {
        Tables t = tables;
        Set<String> keys = allKeys(t);
        List<Audit> out = new ArrayList<>();
        for (String code : t.languages()) out.add(audit(t, keys, code));
        return List.copyOf(out);
    }

    /** The audit of one language (also for a code without a file: then every key is missing). @since 0.45.0 */
    public Audit audit(String language) {
        Tables t = tables;
        return audit(t, allKeys(t), Localization.normalize(language));
    }

    private static Set<String> allKeys(Tables t) {
        Set<String> keys = new TreeSet<>();
        t.bundled().values().forEach(m -> keys.addAll(m.keySet()));
        t.disk().values().forEach(m -> keys.addAll(m.keySet()));
        return keys;
    }

    private static Audit audit(Tables t, Set<String> keys, String code) {
        List<String> missing = new ArrayList<>(), bundled = new ArrayList<>(), english = new ArrayList<>(), mismatch = new ArrayList<>(), tags = new ArrayList<>(), same = new ArrayList<>();
        boolean englishVariant = Localization.languagePart(code).equals(Localization.FALLBACK);
        for (String key : keys) {
            Object baseline = get(t.disk(), "en", key);
            if (baseline == null) baseline = get(t.bundled(), "en", key);
            Object local = get(t.disk(), code, key);
            if (local == null) {
                local = get(t.bundled(), code, key);
                if (local != null && t.disk().containsKey(code)) bundled.add(key);
            }
            if (local == null) { missing.add(key); if (!code.equals("en") && baseline != null) english.add(key); }
            else if (baseline != null && !placeholders(local).equals(placeholders(baseline))) mismatch.add(key);
            if (local != null && !unfilledTags(local).isEmpty()) tags.add(key);
            if (local != null && !englishVariant && untranslated(local, baseline)) same.add(key);
        }
        return new Audit(code, keys.size(), List.copyOf(missing), List.copyOf(bundled), List.copyOf(english), List.copyOf(mismatch), List.copyOf(tags), List.copyOf(same));
    }

    /** Same text as English, with letters in it (so "OK" in a Latin language counts, "{count}" or an arrow does not). */
    static boolean untranslated(Object local, Object baseline) {
        if (baseline == null || !String.valueOf(local).equals(String.valueOf(baseline))) return false;
        String text = String.valueOf(baseline).replaceAll("<[^>]*>|\\{[^}]*}|[&§][0-9a-fk-or]", "");
        return text.codePoints().anyMatch(Character::isLetter);
    }

    /**
     * Key counts for {@code language}: the English keys (disk and jar) and how many of them the language has a text of
     * its own for (disk or jar file of exactly that code, no fallback). {@code en} counts all. @since 0.45.0
     */
    public Counts counts(String language) {
        Tables t = tables;
        String code = Localization.normalize(language);
        Set<String> english = englishKeys(t);
        if (code.equals(Localization.FALLBACK)) return new Counts(english.size(), english.size());
        int own = 0;
        for (String key : english) if (get(t.disk(), code, key) != null || get(t.bundled(), code, key) != null) own++;
        return new Counts(english.size(), own);
    }

    private static Set<String> englishKeys(Tables t) {
        Set<String> english = new TreeSet<>();
        Map<String, Object> d = t.disk().get(Localization.FALLBACK), b = t.bundled().get(Localization.FALLBACK);
        if (d != null) english.addAll(d.keySet());
        if (b != null) english.addAll(b.keySet());
        return english;
    }

    /**
     * Percent (0–100) of this bundle's English keys that {@code language} has a text of its own for; 100 for {@code en}
     * and for a bundle without English keys. Use {@link Localization#coverage(String)} for all plugins together.
     * @since 0.45.0
     */
    public double coverage(String language) {
        Counts c = counts(language);
        return c.total() == 0 ? 100.0 : c.translated() * 100.0 / c.total();
    }

    /** All keys of the English source texts (disk and jar), sorted. @since 0.45.0 */
    public Set<String> keys() { return Collections.unmodifiableSet(englishKeys(tables)); }

    /**
     * The text of {@code key} in exactly {@code language} (the editable file on disk first, then the jar), without any
     * fallback: a {@code String}, a {@code List<String>} for multi-line values, or empty. @since 0.45.0
     */
    public java.util.Optional<Object> translation(String language, String key) {
        Tables t = tables;
        String code = Localization.normalize(language);
        Object value = get(t.disk(), code, key);
        if (value == null) value = get(t.bundled(), code, key);
        return java.util.Optional.ofNullable(value);
    }

    /** The jar's text of {@code key} in exactly {@code language}, without the admin's file. @since 0.45.0 */
    public java.util.Optional<Object> bundledTranslation(String language, String key) {
        return java.util.Optional.ofNullable(get(tables.bundled(), Localization.normalize(language), key));
    }

    /**
     * The editable language folder ({@code plugins/<Plugin>/lang}), where admin overrides and imported translations
     * live; empty for a standalone bundle without folder. @since 0.45.0
     */
    public java.util.Optional<File> folder() { return java.util.Optional.ofNullable(folder); }

    /** MiniMessage tags without arguments; anything else written as {@code <name>} is shown literally. */
    private static final Set<String> MINI_TAGS = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf", "reset", "r",
            "newline", "br", "rainbow", "gradient", "transition", "pride", "shadow", "insertion", "click", "hover", "key",
            "lang", "tr", "translate", "font", "selector", "sel", "score", "nbt", "data", "color", "colour", "c", "sprite", "head");

    /**
     * Lower-case {@code <name>} tags that MiniMessage does not know: almost always a placeholder written in the wrong
     * style ({@code <date>} instead of {@code {date}}), which players would see literally. @since 0.40.0
     */
    static Set<String> unfilledTags(Object value) {
        Set<String> out = new TreeSet<>();
        // Command usage lines show argument hints such as <player> on purpose (also after CJK punctuation: "：/vadmin").
        if (java.util.regex.Pattern.compile("(^|[\\s>\"'(\\u3000-\\u303F\\uFF00-\\uFFEF])/[a-z]").matcher(String.valueOf(value)).find()) return out;
        var matcher = java.util.regex.Pattern.compile("(?<![.])</?([a-z][a-z0-9_-]*)>").matcher(String.valueOf(value));
        while (matcher.find()) if (!MINI_TAGS.contains(matcher.group(1))) out.add(matcher.group(1));
        return out;
    }

    private static Set<String> placeholders(Object value) {
        Set<String> out = new TreeSet<>();
        var matcher = java.util.regex.Pattern.compile("\\{([A-Za-z0-9_.-]+)}").matcher(String.valueOf(value));
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    /**
     * Plugin-level language override (e.g. from the plugin's own config key): {@code null}, blank or {@code auto}
     * follows Core's {@code language.default}; a code forces that language for this plugin (players' personal choice
     * still wins).
     */
    public void setLanguage(String codeOrAuto) {
        String normalized = Localization.normalize(codeOrAuto);
        override = normalized.isEmpty() ? null : normalized;
        cache.clear();
    }

    /** The code this bundle uses for the viewer (an available language, else {@code en}). */
    public String language(CommandSender viewer) {
        try { return pick(service.wanted(viewer, override)); }
        catch (RuntimeException failure) { return Localization.FALLBACK; }
    }

    /** The code this bundle uses for a client locale (without personal choice). */
    public String languageFor(Locale locale) {
        String base = override == null ? service.defaultLanguage() : override;
        if (service.forced()) return pick(List.of(override == null || Localization.AUTO.equals(override) ? service.serverLanguage() : override));
        if (!Localization.AUTO.equals(base)) return pick(List.of(base));
        return pick(locale == null ? List.of() : List.of(Localization.normalize(locale.toString())));
    }

    /**
     * The code this bundle uses for a player known by UUID (e.g. a web session, also while offline) and the browser's
     * languages ({@link Localization#acceptLanguages}); {@code null}/empty values are allowed. @since 0.23.0
     */
    public String languageFor(java.util.UUID player, List<String> browserLanguages) {
        try { return pick(service.wanted(player, browserLanguages, override)); }
        catch (RuntimeException failure) { return Localization.FALLBACK; }
    }

    /**
     * Raw texts of all keys below {@code prefix} (e.g. {@code web.}) in {@code language}, English filling gaps; the map
     * keys have the prefix removed, list values are joined with line breaks. @since 0.23.0
     */
    public Map<String, String> texts(String language, String prefix) {
        String p = prefix == null ? "" : prefix;
        Tables t = tables;
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (Map<String, Object> m : t.bundled().values()) for (String k : m.keySet()) if (k.startsWith(p)) keys.add(k);
        for (Map<String, Object> m : t.disk().values()) for (String k : m.keySet()) if (k.startsWith(p)) keys.add(k);
        Map<String, String> out = new LinkedHashMap<>();
        String code = resolveLanguage(language);
        for (String k : keys) {
            Object v = lookup(code, k);
            if (v != null) out.put(k.substring(p.length()), v instanceof List<?> l ? String.join("\n", l.stream().map(String::valueOf).toList()) : v.toString());
        }
        return out;
    }

    /** The available code this bundle uses for a wanted code ({@code de_at} → {@code de}; unknown → {@code en}). */
    public String resolveLanguage(String code) { return pick(List.of(Localization.normalize(code))); }

    String pick(List<String> wanted) {
        Set<String> available = tables.languages();
        for (String code : wanted) {
            String hit = firstAvailable(code, available);
            if (hit != null) return hit;
        }
        String server = firstAvailable(service.serverLanguage(), available);
        return server != null ? server : Localization.FALLBACK;
    }

    /** First code of the fallback chain with a file; English only for English codes (others try the next wish). */
    private String firstAvailable(String code, Set<String> available) {
        if (code == null || code.isEmpty()) return null;
        boolean english = Localization.languagePart(code).equals(Localization.FALLBACK);
        for (String step : service.fallbackChain(code)) {
            if (step.equals(Localization.FALLBACK) && !english) return null;
            if (available.contains(step)) return step;
        }
        return null;
    }

    // ------------------------------------------------------------------ raw lookup

    /** Whether any file (any language) defines the key. */
    public boolean has(String key) {
        Tables t = tables;
        return t.disk().values().stream().anyMatch(m -> m.containsKey(key)) || t.bundled().values().stream().anyMatch(m -> m.containsKey(key));
    }

    /** The unformatted template ({@code \n} joins list values); the key itself if no file has it. */
    public String raw(String language, String key) {
        Object value = lookup(language, key);
        if (value instanceof List<?> list) return String.join("\n", list.stream().map(String::valueOf).toList());
        return value == null ? (key == null ? "" : key) : value.toString();
    }

    private Object lookup(String language, String key) {
        if (key == null) return null;
        Tables t = tables;
        for (String code : service.fallbackChain(language)) {
            Object value = get(t.disk(), code, key);
            if (value == null) value = get(t.bundled(), code, key);
            if (value != null) return value;
        }
        return null;
    }

    private static Object get(Map<String, Map<String, Object>> table, String code, String key) {
        Map<String, Object> entries = table.get(code);
        return entries == null ? null : entries.get(key);
    }

    private List<String> rawLines(String language, String key) {
        Object value = lookup(language, key);
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        String text = value == null ? (key == null ? "" : key) : value.toString();
        return List.of(text.split("\n", -1));
    }

    // ------------------------------------------------------------------ formatted output

    /** Formatted text for the viewer. */
    public Component component(CommandSender viewer, String key, Object... placeholders) {
        String language = language(viewer);
        if (resolving || service.placeholdersInTexts()) {
            String template = raw(language, key);
            var registered = registered(viewer, template);
            if (registered != null) {
                try { return LangFormat.format(template, placeholders, registered); }
                catch (RuntimeException failure) { DebugMessages.high("[Localization] " + namespace + ":" + key + " failed: " + failure); }
            }
        }
        return component(language, key, placeholders);
    }

    // ------------------------------------------------------------------ registered placeholders

    /** Where the texts find the placeholder manager: the hub's {@code api.placeholders()}; none without a running Core. */
    static volatile java.util.function.Supplier<Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface> placeholderSource = Lang::hubPlaceholders;

    private volatile boolean resolving;

    private static Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface hubPlaceholders() {
        try { return Vystorm.vystorm_core.CoreApi.api().map(Vystorm.vystorm_core.API::placeholders).orElse(null); }
        catch (RuntimeException | LinkageError noHub) { return null; }
    }

    /**
     * Lets the texts of this bundle show the placeholders registered with {@code api.placeholders()}
     * ({@code %vystorm_<namespace>_<key>%}, <code>{namespace:key}</code>, and through PlaceholderAPI the
     * placeholders of other plugins). Off by default; Core's {@code placeholders.in-messages} switches it on for
     * every bundle.
     * <p>
     * It applies to the methods that take a viewer ({@code component}, {@code text}, {@code plain}, {@code lines},
     * {@code send}, {@code actionBar}, {@code name}, {@code lore}); a player is the viewer of the placeholders, the
     * console has none. The methods that take a language code have no viewer and resolve nothing. Values are
     * inserted literally, like the named placeholders, and what a named placeholder inserts is never read as a
     * placeholder. A text without {@code %} and <code>{</code> only costs the lookup of the manager and still comes
     * from the component cache; any other text is scanned once per call. Placeholders inside the arguments of a
     * MiniMessage tag ({@code <click:...>}) are not resolved.
     *
     * @return this bundle
     * @since 0.65.0
     */
    public Lang placeholders(boolean resolve) {
        resolving = resolve;
        return this;
    }

    /** Whether the texts of this bundle resolve registered placeholders (own switch or Core's setting). @since 0.65.0 */
    public boolean placeholders() { return resolving || service.placeholdersInTexts(); }

    /** The resolving step for a template, or null if the template cannot hold a registered placeholder. */
    private static java.util.function.Function<String, Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface.Prepared> registered(CommandSender viewer, String template) {
        Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface manager;
        try { manager = placeholderSource.get(); }
        catch (RuntimeException | LinkageError noHub) { return null; }
        if (manager == null) return null;
        // The shortcut knows Core's syntax only: a manager that replaced Core's may read other texts as well.
        if (manager instanceof Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderHandler
                && !Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderHandler.mayContain(template)) return null;
        org.bukkit.OfflinePlayer who = viewer instanceof org.bukkit.OfflinePlayer player ? player : null;
        return text -> manager.miniMessage(who, null, text);
    }

    /** Formatted text in a given language code. */
    public Component component(String language, String key, Object... placeholders) {
        try {
            if (placeholders == null || placeholders.length == 0) {
                String code = Localization.normalize(language);
                return cache.computeIfAbsent(new CacheKey(code, key == null ? "" : key), k -> LangFormat.format(raw(code, key), null));
            }
            return LangFormat.format(raw(language, key), placeholders);
        } catch (RuntimeException failure) {
            DebugMessages.high("[Localization] " + namespace + ":" + key + " failed: " + failure);
            return Component.text(key == null ? "" : key);
        }
    }

    /** Formatted text as legacy {@code §} string (for String-based APIs such as {@code sender.sendMessage(String)}). */
    public String text(CommandSender viewer, String key, Object... placeholders) {
        return LegacyComponentSerializer.legacySection().serialize(component(viewer, key, placeholders));
    }

    /** Legacy {@code §} string in a given language code. */
    public String text(String language, String key, Object... placeholders) {
        return LegacyComponentSerializer.legacySection().serialize(component(language, key, placeholders));
    }

    /** Plain text without formatting (logs, tab completion, file output). */
    public String plain(CommandSender viewer, String key, Object... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(viewer, key, placeholders));
    }

    /** Plain text in a given language code. */
    public String plain(String language, String key, Object... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(language, key, placeholders));
    }

    /** One component per line (a YAML list, or a string split at line breaks), e.g. for item lore or dialog bodies. */
    public List<Component> lines(CommandSender viewer, String key, Object... placeholders) {
        String language = language(viewer);
        if (resolving || service.placeholdersInTexts()) {
            List<Component> out = new ArrayList<>();
            try {
                for (String line : rawLines(language, key)) out.add(LangFormat.format(line, placeholders, registered(viewer, line)));
                return out;
            } catch (RuntimeException failure) {
                DebugMessages.high("[Localization] " + namespace + ":" + key + " failed: " + failure);
            }
        }
        return lines(language, key, placeholders);
    }

    public List<Component> lines(String language, String key, Object... placeholders) {
        List<Component> out = new ArrayList<>();
        try { for (String line : rawLines(language, key)) out.add(LangFormat.format(line, placeholders)); }
        catch (RuntimeException failure) { out.add(Component.text(key == null ? "" : key)); }
        return out;
    }

    /**
     * Like {@link #lines(CommandSender, String, Object...)}, with every line wrapped to {@code maxColumns} display
     * columns ({@link TextWidth#wrap}: CJK-aware, colours carried over to continued lines). For item lore, which the
     * client does not wrap. Click and hover events inside the text are dropped. @since 0.45.0
     */
    public List<Component> wrappedLines(CommandSender viewer, String key, int maxColumns, Object... placeholders) {
        List<Component> out = new ArrayList<>();
        try {
            for (Component line : lines(viewer, key, placeholders))
                for (String part : TextWidth.wrap(WRAP_SERIALIZER.serialize(line), maxColumns)) out.add(WRAP_SERIALIZER.deserialize(part));
        } catch (RuntimeException failure) {
            out.add(Component.text(key == null ? "" : key));
        }
        return out;
    }

    private static final LegacyComponentSerializer WRAP_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    /** Sends the text to the viewer (console included). */
    public void send(CommandSender viewer, String key, Object... placeholders) {
        if (viewer == null) return;
        try {
            Component text = component(viewer, key, placeholders);
            if (viewer instanceof org.bukkit.entity.Player player) {
                Vystorm.vystorm_core.trace.EventTrace.message(player, namespace, key, plain(text));
                text = withSource(viewer, text);
            }
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, text);
        }
        catch (RuntimeException failure) { DebugMessages.high("[Localization] send " + namespace + ":" + key + " failed: " + failure); }
    }

    /**
     * Shows which plugin sent a chat line when hovering it ({@code messages.show-source} in Core's config, default on).
     * Lines that already carry their own hover keep it.
     */
    private Component withSource(CommandSender viewer, Component text) {
        if (!Vystorm.vystorm_core.trace.MessageSource.enabled() || text.hoverEvent() != null) return text;
        Component hover = Localization.get().core().component(viewer, "trace.source-hover", "plugin", namespace);
        return text.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover));
    }

    private static String plain(Component text) {
        try { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text); }
        catch (RuntimeException | LinkageError unavailable) { return ""; }
    }

    /** Sends the text as action bar (players only; console gets a chat line). */
    public void actionBar(CommandSender viewer, String key, Object... placeholders) {
        if (viewer == null) return;
        try {
            if (viewer instanceof org.bukkit.entity.Player player) {
                Component text = component(viewer, key, placeholders);
                Vystorm.vystorm_core.trace.EventTrace.message(player, namespace, key, plain(text));
                Vystorm.vystorm_core.compat.PlatformCompat.sendActionBar(player, text);
            }
            else Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, component(viewer, key, placeholders));
        } catch (RuntimeException failure) { DebugMessages.high("[Localization] actionBar " + namespace + ":" + key + " failed: " + failure); }
    }

    // ------------------------------------------------------------------ items

    /** Sets the item's display name (not italic unless the text says so). Returns the same stack. */
    public ItemStack name(ItemStack item, CommandSender viewer, String key, Object... placeholders) {
        if (item == null) return null;
        Component name = component(viewer, key, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        try { Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> Vystorm.vystorm_core.compat.PlatformCompat.displayName(meta, name)); } catch (RuntimeException failure) { DebugMessages.high("[Localization] item name failed: " + failure); }
        return item;
    }

    /** Sets the item's lore from {@link #lines} (not italic unless the text says so). Returns the same stack. */
    public ItemStack lore(ItemStack item, CommandSender viewer, String key, Object... placeholders) {
        if (item == null) return null;
        List<Component> lore = lines(viewer, key, placeholders).stream()
                .map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList();
        try { Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> Vystorm.vystorm_core.compat.PlatformCompat.lore(meta, lore)); } catch (RuntimeException failure) { DebugMessages.high("[Localization] item lore failed: " + failure); }
        return item;
    }

    // ------------------------------------------------------------------ text registered once, shown to many

    /**
     * A reference string {@code {lang:<namespace>:<key>}} for String titles that Core renders per viewer: menu entries
     * ({@code gui().registerMenu} title/description), settings sections and settings labels/options, admin modules and
     * actions, {@code dialogs().prompt/confirm} titles. Requires Core ≥ 0.22.0 ({@code CoreApi.Capability.LOCALIZATION}).
     */
    public String ref(String key) { return "{lang:" + namespace + ":" + key + "}"; }

    /**
     * A component that Core renders per viewer when it shows it: in dialogs opened through {@code api.dialogs()} (e.g.
     * {@code DialogButton} labels built once) and in chat via Adventure's global translator (client locale).
     * Arguments are positional placeholders {@code {0}}, {@code {1}}, … in the text.
     */
    public Component translatable(String key, Object... arguments) {
        List<net.kyori.adventure.text.ComponentLike> args = new ArrayList<>();
        if (arguments != null) for (Object a : arguments) args.add(a instanceof net.kyori.adventure.text.ComponentLike c ? c : Component.text(String.valueOf(a)));
        String fallback = PlainTextComponentSerializer.plainText().serialize(component(Localization.FALLBACK, key));
        return Component.translatable(Localization.KEY_PREFIX + namespace + ":" + key, fallback, args);
    }

    @Override public String toString() { return "Lang[" + namespace + ", " + languages() + "]"; }
}
