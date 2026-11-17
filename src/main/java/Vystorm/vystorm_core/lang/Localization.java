package Vystorm.vystorm_core.lang;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localization service of Vystorm Core (capability {@code localization}, since 0.22.0). Every plugin registers one
 * {@link Lang} bundle; Core picks the language per viewer:
 * <ol>
 *   <li>the player's personal choice (Core settings → "Language", or a legacy per-player {@code language} setting),</li>
 *   <li>the plugin's own override ({@link Lang#setLanguage(String)}), else Core's {@code config.yml}
 *       {@code language.default}: {@code auto} = the player's client locale ({@code de_de}, {@code de_at} → {@code de}),
 *       or a fixed code for everyone,</li>
 *   <li>{@code en} when nothing matches (console and non-player senders with {@code auto}).</li>
 * </ol>
 * Access: {@code api.lang()} or {@link #get()}. All methods are thread-safe; lookups never return {@code null} and
 * never throw.
 *
 * <pre>{@code
 * Lang lang = CoreApi.api().orElseThrow().lang().register(this);   // loads lang/*.yml from the jar
 * lang.send(player, "shop.bought", "item", itemName, "amount", 3);   // "{item}" and "{amount}" in the file
 * }</pre>
 *
 * @since 0.22.0
 */
/** Preferred access: {@code api.lang()}. */
public final class Localization {
    /** Value of {@code language.default} that follows each player's client locale. */
    public static final String AUTO = "auto";
    /** Final fallback language; every bundle should ship {@code lang/en.yml}. */
    public static final String FALLBACK = "en";
    /** Prefix of translation keys in {@link #translatable} components ({@code vlang:<namespace>:<key>}). */
    public static final String KEY_PREFIX = "vlang:";
    /** Default of {@code language.min-coverage} (percent). @since 0.45.0 */
    public static final double DEFAULT_MIN_COVERAGE = 50.0;
    /**
     * Built-in fallback steps used when {@code language.fallback} does not override them: Traditional Chinese (Hong Kong,
     * Macau) → Taiwan → Simplified Chinese, bare {@code zh} and Singapore → Simplified Chinese, Portuguese → Brazilian,
     * Norwegian Nynorsk/Bokmål → {@code no}. Regional variants such as {@code de_at}, {@code de_ch} or {@code es_mx} need
     * no entry: the language part ({@code de}, {@code es}) is always tried after the code itself. @since 0.45.0
     */
    public static final Map<String, String> DEFAULT_FALLBACKS = Map.of(
            "zh_hk", "zh_tw", "zh_mo", "zh_tw", "zh_tw", "zh_cn", "zh_sg", "zh_cn", "zh", "zh_cn",
            "pt", "pt_br", "pt_pt", "pt_br", "nn", "no", "nb", "no");

    private static final Pattern REF = Pattern.compile("^\\{lang:([^:{}\\s]+):([A-Za-z0-9_.\\-]+)}$");
    private static final Localization INSTANCE = new Localization();

    private final Map<String, Lang> bundles = new ConcurrentHashMap<>();
    private final Map<UUID, String> personal = new ConcurrentHashMap<>();
    /** Last known client locale per player (kept after quit, used for web requests of offline players). */
    private final Map<UUID, String> clientLocales = new ConcurrentHashMap<>();
    private volatile String defaultLanguage = AUTO;
    /** {@code language.server}: console language and the language for players whose wanted languages all lack a file. */
    private volatile String serverLanguage = FALLBACK;
    /** {@code language.force}: everyone sees {@link #serverLanguage}; personal choices and client locales are ignored. */
    private volatile boolean force;
    /** {@code language.min-coverage}: percent of all English keys a language needs to be offered in /language. */
    private volatile double minCoverage = DEFAULT_MIN_COVERAGE;
    /** {@code language.fallback}: code → next code to try (per key and when choosing a file). */
    private volatile Map<String, String> fallbacks = DEFAULT_FALLBACKS;
    private volatile Plugin core;
    private final Translator translator = new VlangTranslator();
    private final Map<String, List<String>> chains = new ConcurrentHashMap<>();

    Localization() {}

    /** The service instance (also reachable as {@code api.lang()}). */
    public static Localization get() { return INSTANCE; }

    // ------------------------------------------------------------------ bundles

    /**
     * Registers (or returns the already registered) bundle of {@code plugin}: bundled {@code lang/<code>.yml} files
     * of the plugin jar are copied to {@code plugins/<Plugin>/lang/} if missing and then loaded. Call in
     * {@code onEnable}; call {@link Lang#reload()} from the plugin's reload command.
     */
    public Lang register(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String namespace = namespace(plugin.getName());
        Lang existing = bundles.get(namespace);
        if (existing != null && existing.plugin() == plugin) return existing;
        Lang created = new Lang(this, namespace, plugin, LangResources.of(plugin), new File(plugin.getDataFolder(), "lang"));
        bundles.put(namespace, created);
        DebugMessages.mid("[Localization] Bundle registered: " + namespace + "; languages=" + created.languages());
        return created;
    }

    /** The registered bundle of {@code plugin}, if any. */
    public Optional<Lang> bundle(Plugin plugin) {
        if (plugin == null) return Optional.empty();
        Lang lang = bundles.get(namespace(plugin.getName()));
        return lang != null && lang.plugin() == plugin ? Optional.of(lang) : Optional.empty();
    }

    /** The registered bundle for a namespace (lower-case plugin name). */
    public Optional<Lang> bundle(String namespace) {
        return namespace == null ? Optional.empty() : Optional.ofNullable(bundles.get(namespace(namespace)));
    }

    /** Core's own bundle (Core texts: menus, settings, dashboard). Never null. */
    public Lang core() {
        Lang lang = bundles.get("vystorm_core");
        if (lang != null) return lang;
        return bundles.computeIfAbsent("vystorm_core", ns -> new Lang(this, ns, null,
                LangResources.classpath(Localization.class.getClassLoader()), null));
    }

    /** Removes the bundle of a disabled plugin (called by Core's lifecycle cleanup). */
    public void unregister(Plugin plugin) {
        if (plugin == null) return;
        bundles.computeIfPresent(namespace(plugin.getName()), (k, v) -> v.plugin() == plugin ? null : v);
    }

    /** All registered bundles. */
    public Collection<Lang> bundles() { return List.copyOf(bundles.values()); }

    /** Reloads Core's language setting and every registered bundle from disk. */
    public void reloadAll() {
        Plugin plugin = core;
        if (plugin != null) configure(plugin);
        bundles.values().forEach(Lang::reload);
    }

    /** Codes offered anywhere (union of all bundles), sorted. */
    public Set<String> languages() {
        Set<String> all = new TreeSet<>();
        bundles.values().forEach(b -> all.addAll(b.languages()));
        if (all.isEmpty()) all.add(FALLBACK);
        return all;
    }

    /**
     * Languages offered for selection ({@code /language}, {@code /vsettings} → Language, the web language switch): every
     * language Core itself ships or has a file for, plus every other language whose {@linkplain #coverage(String)
     * coverage} reaches {@code language.min-coverage}. Always contains {@code en}. Sorted. @since 0.45.0
     */
    public Set<String> selectableLanguages() {
        Set<String> core = core().languages();
        Set<String> out = new TreeSet<>();
        for (String code : languages()) if (core.contains(code) || coverage(code) >= minCoverage) out.add(code);
        out.add(FALLBACK);
        return out;
    }

    /**
     * Translation coverage of a language over all registered bundles, in percent (0–100): keys with a text of their own
     * in that language (disk or jar, not a fallback) per English key, weighted by bundle size. {@code en} is 100.
     * @since 0.45.0
     */
    public double coverage(String language) {
        String code = normalize(language);
        if (code.isEmpty()) return 0;
        long total = 0, own = 0;
        for (Lang bundle : bundles.values()) {
            Lang.Counts counts = bundle.counts(code);
            total += counts.total();
            own += counts.translated();
        }
        if (total == 0) return code.equals(FALLBACK) ? 100.0 : 0.0;
        return Math.min(100.0, own * 100.0 / total);
    }

    // ------------------------------------------------------------------ fallback chain

    /**
     * The codes tried for {@code language}, best first, ending with {@code en}: the code itself, its configured
     * {@code language.fallback} step (recursively), then its language part ({@code de_at} → {@code de}) with that part's
     * steps. Examples with the defaults: {@code zh_tw → zh_cn → zh → en}, {@code de_ch → de → en}, {@code en_us → en}.
     * Cycles in the configuration are cut. Missing keys fall back along this chain one key at a time. @since 0.45.0
     */
    public List<String> fallbackChain(String language) {
        String code = normalize(language);
        List<String> cached = chains.get(code);
        if (cached != null) return cached;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        chain(code, fallbacks, out, 0);
        out.add(FALLBACK);
        List<String> result = List.copyOf(out);
        if (chains.size() < 512) chains.put(code, result);
        return result;
    }

    private static void chain(String code, Map<String, String> steps, java.util.Set<String> out, int depth) {
        if (code == null || code.isEmpty() || depth > 16 || !out.add(code)) return;
        String next = steps.get(code);
        if (next != null) chain(next, steps, out, depth + 1);
        String part = languagePart(code);
        if (!part.equals(code)) chain(part, steps, out, depth + 1);
    }

    /** Effective fallback steps ({@code language.fallback} over {@link #DEFAULT_FALLBACKS}). @since 0.45.0 */
    public Map<String, String> fallbacks() { return fallbacks; }

    /**
     * Replaces the configured fallback steps (merged over {@link #DEFAULT_FALLBACKS}; a value {@code none} or blank
     * removes a default step). Managed by Core ({@code config.yml} {@code language.fallback}). @since 0.45.0
     */
    public void setFallbacks(Map<String, String> steps) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(DEFAULT_FALLBACKS);
        if (steps != null) steps.forEach((from, to) -> {
            String f = normalize(from), t = normalize(to);
            if (f.isEmpty()) return;
            if (t.isEmpty() || t.equals("none") || t.equals(f)) merged.remove(f); else merged.put(f, t);
        });
        fallbacks = Map.copyOf(merged);
        chains.clear();
        bundles.values().forEach(Lang::clearCache);
    }

    // ------------------------------------------------------------------ language choice

    /**
     * Core's {@code language.server} (default {@code en}): the language of the console and of players whose personal
     * choice and client language have no file in a bundle; with {@link #forced()} the language of everyone. @since 0.45.0
     */
    public String serverLanguage() { return serverLanguage; }

    /** Sets {@code language.server}; blank means {@code en}. Managed by Core. @since 0.45.0 */
    public void setServerLanguage(String code) {
        String normalized = normalize(code);
        serverLanguage = normalized.isEmpty() || normalized.equals(AUTO) ? FALLBACK : normalized;
        bundles.values().forEach(Lang::clearCache);
    }

    /** {@code language.force}: whether everyone sees {@link #serverLanguage()} (no personal choice). @since 0.45.0 */
    public boolean forced() { return force; }

    /** Sets {@code language.force}. Managed by Core. @since 0.45.0 */
    public void setForced(boolean forced) {
        force = forced;
        bundles.values().forEach(Lang::clearCache);
    }

    /** {@code language.min-coverage} in percent. @since 0.45.0 */
    public double minCoverage() { return minCoverage; }

    /** Sets {@code language.min-coverage} (clamped to 0–100). Managed by Core. @since 0.45.0 */
    public void setMinCoverage(double percent) {
        minCoverage = Double.isFinite(percent) ? Math.max(0, Math.min(100, percent)) : DEFAULT_MIN_COVERAGE;
    }

    /** Core's {@code language.default}: {@code auto} or a language code. */
    public String defaultLanguage() { return defaultLanguage; }

    /** Sets the server-wide default ({@code auto}, {@code en}, {@code de}, …); blank means {@code auto}. */
    public void setDefaultLanguage(String code) {
        String normalized = normalize(code);
        defaultLanguage = normalized.isEmpty() ? AUTO : normalized;
        bundles.values().forEach(Lang::clearCache);
    }

    /** The player's own language choice, if they made one. */
    public Optional<String> personalLanguage(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(personal.get(player));
    }

    /**
     * Sets (code) or clears ({@code null}, blank or {@code auto}) the player's own language choice for this session.
     * Core persists the choice made in its settings dialog itself (player data); this method only changes the cache.
     */
    public void setPersonalLanguage(UUID player, String code) {
        if (player == null) return;
        String normalized = normalize(code);
        if (normalized.isEmpty() || normalized.equals(AUTO)) personal.remove(player);
        else personal.put(player, normalized);
    }

    /** Remembers the player's client locale (Core does this on join and locale change). @since 0.23.0 */
    public void rememberClientLocale(UUID player, String locale) {
        String normalized = normalize(locale);
        if (player == null) return;
        if (normalized.isEmpty()) clientLocales.remove(player); else clientLocales.put(player, normalized);
    }

    /** Last known client locale of the player (also while offline), if any. @since 0.23.0 */
    public Optional<String> clientLocale(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(clientLocales.get(player));
    }

    /**
     * Wanted codes for a player known only by UUID (e.g. a web session), optionally with the browser's languages:
     * personal choice, then the fixed default, or with {@code auto} the last client locale and then the browser.
     */
    private List<String> forcedWanted(List<String> wanted, String bundleOverride) {
        wanted.add(bundleOverride == null || bundleOverride.isEmpty() || AUTO.equals(bundleOverride) ? serverLanguage : bundleOverride);
        return wanted;
    }

    List<String> wanted(UUID player, List<String> browser, String bundleOverride) {
        List<String> wanted = new ArrayList<>(4);
        if (force) return forcedWanted(wanted, bundleOverride);
        if (player != null) {
            String own = personal.get(player);
            if (own != null) wanted.add(own);
        }
        String base = bundleOverride == null || bundleOverride.isEmpty() ? defaultLanguage : bundleOverride;
        if (AUTO.equals(base)) {
            if (player != null) { String client = clientLocales.get(player); if (client != null) wanted.add(client); }
            if (browser != null) wanted.addAll(browser);
        } else wanted.add(base);
        return wanted;
    }

    /**
     * Language codes from an HTTP {@code Accept-Language} header, best first ({@code de-AT,de;q=0.9,en;q=0.5} →
     * {@code [de_at, de, en]}); malformed parts are skipped, {@code *} is ignored. @since 0.23.0
     */
    public static List<String> acceptLanguages(String header) {
        if (header == null || header.isBlank() || header.length() > 512) return List.of();
        record Entry(String code, double q, int index) {}
        List<Entry> entries = new ArrayList<>();
        String[] parts = header.split(",");
        for (int i = 0; i < parts.length && i < 16; i++) {
            String[] fields = parts[i].trim().split(";");
            if (fields.length == 0) continue;
            String code = normalize(fields[0]);
            if (!code.matches("[a-z]{1,8}(_[a-z0-9]{1,8})*")) continue;
            double q = 1.0;
            for (int f = 1; f < fields.length; f++) {
                String field = fields[f].trim();
                if (field.startsWith("q=")) try { q = Double.parseDouble(field.substring(2)); } catch (NumberFormatException bad) { q = 0; }
            }
            if (q > 0) entries.add(new Entry(code, q, i));
        }
        entries.sort((a, b) -> a.q != b.q ? Double.compare(b.q, a.q) : Integer.compare(a.index, b.index));
        return entries.stream().map(Entry::code).distinct().toList();
    }

    /**
     * Resolves a {@link Lang#ref} reference in a given language code (plain text); other text is returned unchanged.
     * @since 0.23.0
     */
    public String resolveIn(String language, String text) {
        Matcher m = text == null ? null : REF.matcher(text);
        if (m == null || !m.matches()) return text == null ? "" : text;
        Lang lang = bundles.get(m.group(1));
        return lang == null ? m.group(2) : lang.plain(lang.resolveLanguage(language), m.group(2));
    }

    /** Wanted language codes for the viewer in priority order (without availability check). */
    List<String> wanted(CommandSender viewer, String bundleOverride) {
        List<String> wanted = new ArrayList<>(3);
        if (force) return forcedWanted(wanted, bundleOverride);
        if (viewer instanceof Player player) {
            String own = safeUuid(player) == null ? null : personal.get(safeUuid(player));
            if (own != null) wanted.add(own);
        }
        String base = bundleOverride == null || bundleOverride.isEmpty() ? defaultLanguage : bundleOverride;
        if (AUTO.equals(base)) {
            if (viewer instanceof Player player) {
                String client = clientLocale(player);
                if (!client.isEmpty()) wanted.add(client);
            }
        } else wanted.add(base);
        return wanted;
    }

    /**
     * The viewer's wanted language codes in priority order (personal choice, then {@code language.default} or the
     * client locale), not yet matched against available files; may be empty (console with {@code auto}).
     */
    public List<String> wantedLanguages(CommandSender viewer) {
        try { return List.copyOf(wanted(viewer, null)); } catch (RuntimeException failure) { return List.of(); }
    }

    /** The language Core's own texts use for the viewer. */
    public String language(CommandSender viewer) { return core().language(viewer); }

    /** The player's client locale in normalized form ({@code de_de}); empty if unknown. */
    public static String clientLocale(Player player) {
        try {
            Locale locale = Vystorm.vystorm_core.compat.PlatformCompat.locale(player);
            if (locale != null) return normalize(locale.toString());
        } catch (RuntimeException | LinkageError ignored) {
            // Mocked or very old player objects.
        }
        try {
            @SuppressWarnings("deprecation") String legacy = player.getLocale();
            return normalize(legacy);
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static UUID safeUuid(Player player) {
        try { return player.getUniqueId(); } catch (RuntimeException e) { return null; }
    }

    /** {@code de-DE} / {@code de_DE} → {@code de_de}; {@code null} → empty. */
    public static String normalize(String code) {
        if (code == null) return "";
        return code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /** Language part of a locale code: {@code de_at} → {@code de}. */
    public static String languagePart(String code) {
        String normalized = normalize(code);
        int cut = normalized.indexOf('_');
        return cut < 0 ? normalized : normalized.substring(0, cut);
    }

    static String namespace(String pluginName) {
        return pluginName == null ? "" : pluginName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    // ------------------------------------------------------------------ references for text registered once

    /** Whether {@code text} is a reference created by {@link Lang#ref(String)}. */
    public static boolean isRef(String text) { return text != null && REF.matcher(text).matches(); }

    /**
     * Resolves a {@link Lang#ref(String)} reference for the viewer (legacy {@code §} string); any other text is
     * returned unchanged. Used by Core for menu titles, settings labels and admin modules that are registered once
     * and shown to many players.
     */
    public String resolve(CommandSender viewer, String text) {
        Matcher m = text == null ? null : REF.matcher(text);
        if (m == null || !m.matches()) return text == null ? "" : text;
        Lang lang = bundles.get(m.group(1));
        return lang == null ? m.group(2) : lang.text(viewer, m.group(2));
    }

    /**
     * A component for text that is shown later to unknown viewers: a {@link Lang#ref} becomes a translatable component
     * (rendered per viewer by Core's dialogs and Adventure's translator), any other text {@code Component.text(text)}.
     */
    public Component deferred(String text) {
        Matcher m = text == null ? null : REF.matcher(text);
        if (m == null || !m.matches()) return Component.text(text == null ? "" : text);
        Lang lang = bundles.get(m.group(1));
        return lang == null ? Component.text(m.group(2)) : lang.translatable(m.group(2));
    }

    /** Like {@link #resolve} but as a component; plain text becomes {@code Component.text(text)} (unchanged behavior). */
    public Component component(CommandSender viewer, String text) {
        Matcher m = text == null ? null : REF.matcher(text);
        if (m == null || !m.matches()) return Component.text(text == null ? "" : text);
        Lang lang = bundles.get(m.group(1));
        return lang == null ? Component.text(m.group(2)) : lang.component(viewer, m.group(2));
    }

    /**
     * Replaces every {@link Lang#translatable} part of {@code component} with the viewer's text. Core does this for all
     * dialogs it opens; call it yourself before sending such components through other channels.
     */
    public Component render(CommandSender viewer, Component component) {
        if (component == null) return null;
        try { return renderTree(viewer, component); }
        catch (RuntimeException failure) {
            DebugMessages.high("[Localization] Rendering failed: " + failure);
            return component;
        }
    }

    private Component renderTree(CommandSender viewer, Component component) {
        List<Component> children = component.children();
        List<Component> rendered = new ArrayList<>(children.size());
        boolean changed = false;
        for (Component child : children) {
            Component r = renderTree(viewer, child);
            changed |= r != child;
            rendered.add(r);
        }
        if (component instanceof TranslatableComponent t && t.key().startsWith(KEY_PREFIX)) {
            Component text = resolveKey(viewer, null, t.key(), t.arguments());
            if (text == null) return changed ? component.children(rendered) : component;
            return Component.text().style(t.style()).append(text).append(rendered).build();
        }
        return changed ? component.children(rendered) : component;
    }

    /** Resolves a {@code vlang:} key for a viewer or a locale; null if the key is not a vlang key. */
    private Component resolveKey(CommandSender viewer, Locale locale, String key, List<TranslationArgument> arguments) {
        String rest = key.substring(KEY_PREFIX.length());
        int cut = rest.indexOf(':');
        if (cut <= 0) return null;
        Lang lang = bundles.get(rest.substring(0, cut));
        String entry = rest.substring(cut + 1);
        if (lang == null) return Component.text(entry);
        Object[] positional = new Object[arguments == null ? 0 : arguments.size() * 2];
        for (int i = 0; arguments != null && i < arguments.size(); i++) {
            positional[i * 2] = Integer.toString(i);
            Object value = arguments.get(i).value();
            positional[i * 2 + 1] = value;
        }
        String language = viewer != null ? lang.language(viewer) : lang.languageFor(locale);
        return lang.component(language, entry, positional);
    }

    // ------------------------------------------------------------------ Core lifecycle

    /** Reads {@code language.default} from Core's config.yml (jar default {@code auto}). Called by Core. */
    public void configure(Plugin corePlugin) {
        this.core = corePlugin;
        YamlConfiguration config = new YamlConfiguration();
        try {
            File file = new File(corePlugin.getDataFolder(), "config.yml");
            if (file.isFile()) config = YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException failure) {
            DebugMessages.high("[Localization] config.yml unreadable, using the language defaults: " + failure);
        }
        configure(config);
    }

    /**
     * Applies the {@code language} section of a Core config: {@code default}, {@code server}, {@code force},
     * {@code min-coverage}, {@code fallback} (map code → code). Missing values use the defaults. @since 0.45.0
     */
    public void configure(org.bukkit.configuration.ConfigurationSection config) {
        String value = AUTO, server = FALLBACK;
        boolean forced = false;
        double coverage = DEFAULT_MIN_COVERAGE;
        Map<String, String> steps = new java.util.LinkedHashMap<>();
        try {
            if (config != null) {
                value = config.getString("language.default", AUTO);
                server = config.getString("language.server", FALLBACK);
                forced = config.getBoolean("language.force", false);
                coverage = config.getDouble("language.min-coverage", DEFAULT_MIN_COVERAGE);
                var section = config.getConfigurationSection("language.fallback");
                // YAML 1.1 reads an unquoted "no" (Norwegian) as false.
                if (section != null) for (String from : section.getKeys(false)) {
                    Object to = section.get(from);
                    steps.put(from.equals("false") ? "no" : from, Boolean.FALSE.equals(to) ? "no" : String.valueOf(to));
                }
            }
        } catch (RuntimeException failure) {
            DebugMessages.high("[Localization] language settings unreadable, using the defaults: " + failure);
        }
        setFallbacks(steps);
        setServerLanguage(server);
        setMinCoverage(coverage);
        setForced(forced);
        setDefaultLanguage(value);
    }

    /** Registers the Adventure translator so {@code vlang:} components sent directly still render (client locale). */
    public void installTranslator() {
        try { GlobalTranslator.translator().addSource(translator); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[Localization] Translator not installed: " + failure); }
    }

    public void uninstallTranslator() {
        try { GlobalTranslator.translator().removeSource(translator); }
        catch (RuntimeException | LinkageError ignored) { /* server shutting down */ }
    }

    /** Clears per-player state (Core disable). */
    public void clearPlayers() { personal.clear(); }

    private final class VlangTranslator implements Translator {
        private final Key name = Key.key("vystorm_core", "lang");
        @Override public Key name() { return name; }
        @Override public MessageFormat translate(String key, Locale locale) { return null; }
        public boolean canTranslate(String key, Locale locale) { return key != null && key.startsWith(KEY_PREFIX); }
        @Override public Component translate(TranslatableComponent component, Locale locale) {
            if (!component.key().startsWith(KEY_PREFIX)) return null;
            try {
                Component text = resolveKey(null, locale, component.key(), component.arguments());
                if (text == null) return null;
                List<Component> children = component.children();
                return Component.text().style(component.style()).append(text).append(children).build();
            } catch (RuntimeException failure) {
                return null;
            }
        }
    }

    /** Core's own language settings: a player's choice, native names, reload ({@link CoreLanguage}). @since 0.64.0 */
    public CoreLanguageInterface coreLanguage() { return CoreLanguageInterface.DEFAULT; }
}
