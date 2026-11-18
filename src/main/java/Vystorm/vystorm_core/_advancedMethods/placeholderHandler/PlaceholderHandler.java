package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Core's placeholder registry: the default behind {@code api.placeholders()}. Contract and syntax are described on
 * {@link PlaceholderInterface}.
 * <p>
 * Implementation notes: namespaces, values and bridges are immutable snapshots replaced under a lock, so resolving
 * takes no lock. A text is scanned once, character by character, without regular expressions; the result builder is
 * only created when the first placeholder was replaced.
 *
 * @since 0.65.0
 */
public final class PlaceholderHandler implements PlaceholderInterface {
    /**
     * Names that cannot be namespaces because braces with them mean something else already: Core's language
     * references ({@code {lang:...}}) and client tooltips ({@code {data:...}}), PlaceholderAPI's relational prefix,
     * the identifier itself, and the argument placeholders of Vystorm Boards.
     */
    static final Set<String> RESERVED = Set.of("lang", "data", "rel", IDENTIFIER, "countdown", "votes", "vote", "guestbook");
    private static final String REGISTRY = "Placeholders";
    private static final int MAX_NAMESPACE = 32;
    private static final int MAX_TOKEN = 256;
    private static final int MAX_DEPTH = 8;
    private static final int CACHE_LIMIT = 4096;
    private static final String RELATIONAL = IDENTIFIER + "_";

    private static final PlaceholderHandler SHARED = new PlaceholderHandler(PlaceholderHandler::bukkitServerThread, PlaceholderHandler::bukkitTick);

    private record Resolved(String text, boolean formatted) {}
    private record Attached(Plugin owner, PlaceholderBridge bridge) {}
    private record CacheKey(PlaceholderValue value, UUID viewer, UUID other, String raw) {}
    private record CacheHit(long tick, String text) {}

    private interface Sink { String insert(Resolved value); }

    private final BooleanSupplier serverThread;
    private final LongSupplier tick;
    private final Object lock = new Object();
    private final Map<CacheKey, CacheHit> cache = new ConcurrentHashMap<>();
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);
    private volatile Map<String, Space> spaces = Map.of();
    private volatile List<Attached> bridges = List.of();
    private volatile Supplier<? extends PlaceholderInterface> route = () -> this;

    private final PlaceholderBridge.Source source = new PlaceholderBridge.Source() {
        @Override public String request(OfflinePlayer viewer, Player other, String params) {
            PlaceholderInterface manager = route.get();
            return (manager == null ? PlaceholderHandler.this : manager).request(viewer, other, params);
        }

        @Override public List<String> known() {
            PlaceholderInterface manager = route.get();
            List<String> out = new ArrayList<>();
            for (PlaceholderNamespace space : (manager == null ? PlaceholderHandler.this : manager).namespaces())
                for (PlaceholderValue value : space.values()) out.add(space.name() + "_" + value.key());
            return List.copyOf(out);
        }
    };

    PlaceholderHandler(BooleanSupplier serverThread, LongSupplier tick) {
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.tick = Objects.requireNonNull(tick, "tick");
    }

    /** The registry behind {@code api.placeholders()}. */
    public static PlaceholderHandler shared() { return SHARED; }

    private static boolean bukkitServerThread() {
        try { return Bukkit.isPrimaryThread(); }
        catch (RuntimeException | LinkageError noServer) { return true; }
    }

    private static long bukkitTick() {
        try { return Vystorm.vystorm_core.compat.PlatformCompat.getCurrentTick(); }
        catch (RuntimeException | LinkageError noServer) { return 0L; }
    }

    /**
     * Whether a text can contain a placeholder at all: it has a {@code %} or a <code>{</code>. A cheap check before
     * {@link #resolve}; the methods here make it themselves.
     */
    public static boolean mayContain(String text) {
        return text != null && (text.indexOf('%') >= 0 || text.indexOf('{') >= 0);
    }

    /** Parses a prepared MiniMessage text; a text MiniMessage rejects is shown as plain text. */
    public static Component parse(Prepared prepared) {
        Objects.requireNonNull(prepared, "prepared");
        try { return MiniMessage.miniMessage().deserialize(prepared.text(), prepared.resolver()); }
        catch (RuntimeException failure) { return Component.text(prepared.text()); }
    }

    // ------------------------------------------------------------------ registry

    @Override
    public PlaceholderNamespace register(Plugin owner, String namespace) {
        Objects.requireNonNull(owner, "owner");
        String name = checkNamespace(namespace);
        synchronized (lock) {
            Space existing = spaces.get(name);
            if (existing != null) {
                if (existing.owner == owner) return existing;
                if (enabled(existing.owner))
                    throw new IllegalStateException("Placeholder namespace '" + name + "' belongs to " + existing.ownerName);
                existing.registered = false;
            }
            Space space = new Space(name, owner);
            Map<String, Space> next = new HashMap<>(spaces);
            next.put(name, space);
            spaces = Map.copyOf(next);
            return space;
        }
    }

    static String checkNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        String name = namespace.toLowerCase(Locale.ROOT);
        boolean valid = !name.isEmpty() && name.length() <= MAX_NAMESPACE && name.charAt(0) >= 'a' && name.charAt(0) <= 'z';
        for (int i = 1; valid && i < name.length(); i++) valid = word(name.charAt(i));
        if (!valid) throw new IllegalArgumentException("Invalid placeholder namespace '" + namespace
                + "': a letter followed by letters and digits, at most " + MAX_NAMESPACE + " characters");
        if (RESERVED.contains(name)) throw new IllegalArgumentException("Reserved placeholder namespace: " + name);
        return name;
    }

    @Override
    public boolean unregister(Plugin owner, String namespace) {
        if (owner == null || namespace == null) return false;
        String name = namespace.toLowerCase(Locale.ROOT);
        synchronized (lock) {
            Space existing = spaces.get(name);
            if (existing == null || existing.owner != owner) return false;
            existing.registered = false;
            Map<String, Space> next = new HashMap<>(spaces);
            next.remove(name);
            spaces = Map.copyOf(next);
        }
        cache.clear();
        return true;
    }

    @Override
    public int unregisterAll(Plugin owner) {
        if (owner == null) return 0;
        int removed = 0;
        List<PlaceholderBridge> gone = new ArrayList<>();
        synchronized (lock) {
            Map<String, Space> next = new HashMap<>(spaces);
            for (Space space : spaces.values()) {
                if (space.owner != owner) continue;
                space.registered = false;
                next.remove(space.name);
                removed++;
            }
            if (removed > 0) spaces = Map.copyOf(next);
            List<Attached> kept = new ArrayList<>();
            for (Attached attached : bridges) {
                if (attached.owner == owner) gone.add(attached.bridge);
                else kept.add(attached);
            }
            if (!gone.isEmpty()) bridges = List.copyOf(kept);
        }
        for (PlaceholderBridge bridge : gone) withdraw(bridge);
        if (removed > 0) cache.clear();
        return removed + gone.size();
    }

    /** Core only: removes every namespace and disconnects every bridge when Core is disabled. */
    public void clear() {
        List<Attached> gone;
        synchronized (lock) {
            for (Space space : spaces.values()) space.registered = false;
            spaces = Map.of();
            gone = bridges;
            bridges = List.of();
        }
        for (Attached attached : gone) withdraw(attached.bridge);
        cache.clear();
    }

    /**
     * Core only: the manager that bridges ask, so that a manager replaced through
     * {@code api.overridePlaceholderHandler} also answers other plugins. {@code null} means this registry.
     */
    public void route(Supplier<? extends PlaceholderInterface> manager) {
        route = manager == null ? () -> this : manager;
    }

    @Override
    public List<PlaceholderNamespace> namespaces() {
        List<PlaceholderNamespace> out = new ArrayList<>(spaces.values());
        out.sort(Comparator.comparing(PlaceholderNamespace::name));
        return Collections.unmodifiableList(out);
    }

    @Override
    public Optional<PlaceholderNamespace> namespace(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(spaces.get(name.toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------ resolving

    @Override
    public String resolve(OfflinePlayer viewer, Player other, String text) {
        if (!mayContain(text)) return text;
        return scan(viewer, other, text, PlaceholderHandler::plain);
    }

    @Override
    public Prepared miniMessage(OfflinePlayer viewer, Player other, String text) {
        if (text == null) return new Prepared("", TagResolver.empty(), 0);
        if (!mayContain(text)) return new Prepared(text, TagResolver.empty(), 0);
        String name = "vyp";
        while (text.contains("<" + name)) name += "x";
        String prefix = name;
        List<TagResolver> tags = new ArrayList<>();
        String out = scan(viewer, other, text, value -> {
            String tag = prefix + tags.size();
            tags.add(tag(tag, value));
            return "<" + tag + ">";
        });
        return new Prepared(out, tags.isEmpty() ? TagResolver.empty() : TagResolver.resolver(tags), tags.size());
    }

    @Override
    public String request(OfflinePlayer viewer, Player other, String params) {
        if (params == null) return null;
        Resolved value = answer(viewer, other, params);
        return value == null ? null : plain(value);
    }

    @Override
    public String value(OfflinePlayer viewer, String namespace, String key, String... arguments) {
        if (namespace == null || key == null) return null;
        Space space = spaces.get(namespace.toLowerCase(Locale.ROOT));
        if (space == null) return null;
        List<String> list = arguments == null || arguments.length == 0 ? List.of() : List.of(arguments);
        String raw = list.isEmpty() ? key : key + ":" + String.join(":", list);
        Resolved value = space.direct(viewer, null, key.toLowerCase(Locale.ROOT), list, raw, ":");
        return value == null ? null : plain(value);
    }

    private static String plain(Resolved value) {
        if (!value.formatted()) return value.text();
        try { return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(value.text())); }
        catch (RuntimeException failure) { return value.text(); }
    }

    private static TagResolver tag(String name, Resolved value) {
        String text = value.text();
        if (value.formatted()) {
            try { return Placeholder.component(name, MiniMessage.miniMessage().deserialize(text)); }
            catch (RuntimeException failure) { return Placeholder.unparsed(name, text); }
        }
        if (text.indexOf('§') >= 0) return Placeholder.component(name, LegacyComponentSerializer.legacySection().deserialize(text));
        return Placeholder.unparsed(name, text);
    }

    /** The single pass over a text: every placeholder that is answered is replaced by what {@code sink} returns. */
    private String scan(OfflinePlayer viewer, Player other, String text, Sink sink) {
        int length = text.length();
        StringBuilder out = null;
        int copied = 0;
        for (int i = 0; i < length; ) {
            char c = text.charAt(i);
            if (c != '%' && c != '{') { i++; continue; }
            int end = c == '%' ? percentEnd(text, i) : braceEnd(text, i);
            if (end < 0) { i++; continue; }
            if (i > 0 && text.charAt(i - 1) == '\\') {
                if (c == '%' || braceSpace(text, i) != null) {
                    if (out == null) out = new StringBuilder(length);
                    out.append(text, copied, i - 1).append(text, i, end + 1);
                    copied = end + 1;
                }
                i = end + 1;
                continue;
            }
            Resolved value = c == '%' ? percent(viewer, other, text, i, end) : brace(viewer, other, text, i, end);
            if (value != null) {
                if (out == null) out = new StringBuilder(length + 16);
                out.append(text, copied, i).append(sink.insert(value));
                copied = end + 1;
            }
            i = end + 1;
        }
        return out == null ? text : out.append(text, copied, length).toString();
    }

    private static boolean word(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z');
    }

    /** The closing {@code %} of {@code %identifier_params%} that starts at {@code start}, or -1. */
    private static int percentEnd(String text, int start) {
        int length = text.length();
        int i = start + 1;
        while (i < length && word(text.charAt(i))) i++;
        if (i == start + 1 || i >= length || text.charAt(i) != '_') return -1;
        int limit = Math.min(length, start + MAX_TOKEN);
        for (int k = i + 1; k < limit; k++) {
            char c = text.charAt(k);
            if (c == '%') return k > i + 1 ? k : -1;
            if (c == '\n' || c == '\r') return -1;
        }
        return -1;
    }

    /** The closing brace of <code>{namespace:rest}</code> that starts at {@code start}, or -1. */
    private static int braceEnd(String text, int start) {
        int length = text.length();
        int i = start + 1;
        while (i < length && word(text.charAt(i))) i++;
        if (i == start + 1 || i - start - 1 > MAX_NAMESPACE || i >= length || text.charAt(i) != ':') return -1;
        int limit = Math.min(length, start + MAX_TOKEN);
        for (int k = i + 1; k < limit; k++) {
            char c = text.charAt(k);
            if (c == '}') return k > i + 1 ? k : -1;
            if (c == '{' || c == '\n' || c == '\r') return -1;
        }
        return -1;
    }

    private Space braceSpace(String text, int start) {
        Map<String, Space> known = spaces;
        if (known.isEmpty()) return null;
        return known.get(text.substring(start + 1, text.indexOf(':', start)).toLowerCase(Locale.ROOT));
    }

    private Resolved percent(OfflinePlayer viewer, Player other, String text, int start, int end) {
        int under = text.indexOf('_', start);
        int idLength = under - start - 1;
        if (idLength == IDENTIFIER.length() && text.regionMatches(true, start + 1, IDENTIFIER, 0, idLength))
            return answer(viewer, other, text.substring(under + 1, end));
        if (idLength == 3 && text.regionMatches(true, start + 1, "rel", 0, 3)
                && text.regionMatches(true, under + 1, RELATIONAL, 0, RELATIONAL.length()))
            return under + 1 + RELATIONAL.length() < end ? answer(viewer, other, text.substring(under + 1 + RELATIONAL.length(), end)) : null;
        return foreign(viewer, other, text.substring(start, end + 1));
    }

    private Resolved brace(OfflinePlayer viewer, Player other, String text, int start, int end) {
        Space space = braceSpace(text, start);
        if (space == null) return null;
        String rest = text.substring(text.indexOf(':', start) + 1, end);
        int cut = rest.indexOf(':');
        String key = (cut < 0 ? rest : rest.substring(0, cut)).toLowerCase(Locale.ROOT);
        List<String> arguments = cut < 0 ? List.of() : split(rest, cut + 1, ':');
        return space.direct(viewer, other, key, arguments, rest, ":");
    }

    /** {@code <namespace>_<key>[_<arguments>]}: the longest declared key wins, the rest are arguments. */
    private Resolved answer(OfflinePlayer viewer, Player other, String params) {
        int cut = params.indexOf('_');
        if (cut <= 0 || cut == params.length() - 1) return null;
        Space space = spaces.get(params.substring(0, cut).toLowerCase(Locale.ROOT));
        return space == null ? null : space.longest(viewer, other, params.substring(cut + 1));
    }

    private Resolved foreign(OfflinePlayer viewer, Player other, String placeholder) {
        List<Attached> list = bridges;
        if (list.isEmpty()) return null;
        int[] level = depth.get();
        if (level[0] >= MAX_DEPTH) return null;
        boolean server = serverThread.getAsBoolean();
        for (Attached attached : list) {
            PlaceholderBridge bridge = attached.bridge;
            level[0]++;
            try {
                if (!server && !bridge.asyncSafe()) continue;
                if (!bridge.available()) continue;
                String value = bridge.consume(viewer, other, placeholder);
                if (value != null && !value.equals(placeholder)) return new Resolved(value, false);
            } catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report(REGISTRY, bridge.name(), "consume", failure);
            } finally {
                level[0]--;
            }
        }
        return null;
    }

    private static List<String> split(String text, int from, char separator) {
        List<String> out = new ArrayList<>(4);
        int start = from;
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) != separator) continue;
            out.add(text.substring(start, i));
            start = i + 1;
        }
        out.add(text.substring(start));
        return Collections.unmodifiableList(out);
    }

    private static boolean enabled(Plugin owner) {
        try { return owner.isEnabled(); }
        catch (RuntimeException | LinkageError broken) { return false; }
    }

    private Resolved invoke(Space space, PlaceholderValue value, PlaceholderResolver resolver, boolean asyncSafe,
                            OfflinePlayer viewer, Player other, String key, List<String> arguments, String raw, String separator) {
        if (!enabled(space.owner)) return null;
        boolean server = serverThread.getAsBoolean();
        if (!asyncSafe && !server) return null;
        int[] level = depth.get();
        if (level[0] >= MAX_DEPTH) return null;
        boolean formatted = value != null && value.isFormatted();
        int keep = value == null ? 0 : value.cacheTicks();
        CacheKey cacheKey = null;
        long now = 0;
        if (keep > 0) {
            now = tick.getAsLong();
            cacheKey = new CacheKey(value, viewer == null ? null : viewer.getUniqueId(), other == null ? null : other.getUniqueId(), raw);
            CacheHit hit = cache.get(cacheKey);
            if (hit != null && now >= hit.tick() && now - hit.tick() < keep) return new Resolved(hit.text(), formatted);
        }
        String text;
        level[0]++;
        try {
            Object result = resolver.resolve(new PlaceholderContext(viewer, other, space.name, key, arguments, raw, separator, server));
            text = result == null ? null : String.valueOf(result);
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report(REGISTRY, space.ownerName, space.name + ":" + key, failure);
            text = null;
        } finally {
            level[0]--;
        }
        if (text == null) return null;
        if (cacheKey != null) {
            if (cache.size() >= CACHE_LIMIT) cache.clear();
            cache.put(cacheKey, new CacheHit(now, text));
        }
        return new Resolved(text, formatted);
    }

    // ------------------------------------------------------------------ bridges

    @Override
    public void bridge(Plugin owner, PlaceholderBridge bridge) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(bridge, "bridge");
        String name = Objects.requireNonNull(bridge.name(), "bridge name");
        List<PlaceholderBridge> replaced = new ArrayList<>();
        synchronized (lock) {
            List<Attached> next = new ArrayList<>();
            for (Attached attached : bridges) {
                if (attached.bridge == bridge) continue;
                if (name.equalsIgnoreCase(attached.bridge.name())) replaced.add(attached.bridge);
                else next.add(attached);
            }
            next.add(new Attached(owner, bridge));
            bridges = List.copyOf(next);
        }
        for (PlaceholderBridge old : replaced) withdraw(old);
        provide(bridge);
    }

    @Override
    public boolean removeBridge(PlaceholderBridge bridge) {
        if (bridge == null) return false;
        synchronized (lock) {
            List<Attached> next = new ArrayList<>(bridges);
            if (!next.removeIf(attached -> attached.bridge == bridge)) return false;
            bridges = List.copyOf(next);
        }
        withdraw(bridge);
        return true;
    }

    @Override
    public List<PlaceholderBridge> bridges() {
        List<PlaceholderBridge> out = new ArrayList<>();
        for (Attached attached : bridges) out.add(attached.bridge);
        return Collections.unmodifiableList(out);
    }

    @Override
    public void refreshBridges() {
        for (Attached attached : bridges) provide(attached.bridge);
    }

    private void provide(PlaceholderBridge bridge) {
        try { bridge.provide(source); }
        catch (RuntimeException | LinkageError failure) { ProviderFailures.report(REGISTRY, bridge.name(), "provide", failure); }
    }

    private static void withdraw(PlaceholderBridge bridge) {
        try { bridge.withdraw(); }
        catch (RuntimeException | LinkageError failure) { ProviderFailures.report(REGISTRY, bridge.name(), "withdraw", failure); }
    }

    // ------------------------------------------------------------------ a namespace

    private final class Space implements PlaceholderNamespace {
        final String name;
        final Plugin owner;
        final String ownerName;
        volatile Map<String, PlaceholderValue> values = Map.of();
        volatile int maxParts;
        volatile PlaceholderResolver fallback;
        volatile boolean fallbackAsync;
        volatile boolean registered = true;

        Space(String name, Plugin owner) {
            this.name = name;
            this.owner = owner;
            String label;
            try { label = owner.getName(); }
            catch (RuntimeException | LinkageError broken) { label = null; }
            this.ownerName = label == null ? "?" : label;
        }

        @Override public String name() { return name; }
        @Override public Plugin owner() { return owner; }
        @Override public boolean registered() { return registered; }
        @Override public boolean hasFallback() { return fallback != null; }
        @Override public List<PlaceholderValue> values() { return List.copyOf(values.values()); }

        @Override
        public Optional<PlaceholderValue> value(String key) {
            return key == null ? Optional.empty() : Optional.ofNullable(values.get(key.toLowerCase(Locale.ROOT)));
        }

        @Override
        public PlaceholderNamespace add(PlaceholderValue value) {
            Objects.requireNonNull(value, "value");
            synchronized (lock) {
                Map<String, PlaceholderValue> next = new LinkedHashMap<>(values);
                next.put(value.key(), value);
                publish(next);
            }
            return this;
        }

        @Override
        public boolean remove(String key) {
            if (key == null) return false;
            synchronized (lock) {
                Map<String, PlaceholderValue> next = new LinkedHashMap<>(values);
                if (next.remove(key.toLowerCase(Locale.ROOT)) == null) return false;
                publish(next);
            }
            return true;
        }

        private void publish(Map<String, PlaceholderValue> next) {
            int parts = 0;
            for (String key : next.keySet()) {
                int count = 1;
                for (int i = 0; i < key.length(); i++) if (key.charAt(i) == '_') count++;
                parts = Math.max(parts, count);
            }
            maxParts = parts;
            values = Collections.unmodifiableMap(next);
        }

        @Override public PlaceholderNamespace fallback(PlaceholderResolver resolver) { return fallback(resolver, false); }

        @Override
        public PlaceholderNamespace fallback(PlaceholderResolver resolver, boolean asyncSafe) {
            synchronized (lock) {
                fallbackAsync = asyncSafe;
                fallback = resolver;
            }
            return this;
        }

        /** Percent form: {@code rest} is {@code <key>[_<arguments>]} and the key may contain underscores. */
        Resolved longest(OfflinePlayer viewer, Player other, String rest) {
            Map<String, PlaceholderValue> declared = values;
            String lower = rest.toLowerCase(Locale.ROOT);
            int length = lower.length();
            int end = length;
            int parts = maxParts;
            if (parts > 0) {
                int at = -1;
                for (int seen = 0; seen < parts; seen++) {
                    at = lower.indexOf('_', at + 1);
                    if (at < 0) break;
                }
                if (at >= 0) end = at;
                while (end > 0) {
                    PlaceholderValue value = declared.get(end == length ? lower : lower.substring(0, end));
                    if (value != null) {
                        List<String> arguments = end == length ? List.of() : split(rest, end + 1, '_');
                        return invoke(this, value, value.resolver(), value.isAsyncSafe(), viewer, other, value.key(), arguments, rest, "_");
                    }
                    end = lower.lastIndexOf('_', end - 1);
                }
            }
            PlaceholderResolver resolver = fallback;
            if (resolver == null) return null;
            int cut = lower.indexOf('_');
            String key = cut < 0 ? lower : lower.substring(0, cut);
            List<String> arguments = cut < 0 ? List.of() : split(rest, cut + 1, '_');
            return invoke(this, null, resolver, fallbackAsync, viewer, other, key, arguments, rest, "_");
        }

        /** Brace form and direct lookups: the key is given. */
        Resolved direct(OfflinePlayer viewer, Player other, String key, List<String> arguments, String raw, String separator) {
            PlaceholderValue value = values.get(key);
            if (value != null)
                return invoke(this, value, value.resolver(), value.isAsyncSafe(), viewer, other, value.key(), arguments, raw, separator);
            PlaceholderResolver resolver = fallback;
            return resolver == null ? null : invoke(this, null, resolver, fallbackAsync, viewer, other, key, arguments, raw, separator);
        }

        @Override public String toString() { return "PlaceholderNamespace[" + name + " of " + ownerName + ", " + values.size() + " values]"; }
    }
}
