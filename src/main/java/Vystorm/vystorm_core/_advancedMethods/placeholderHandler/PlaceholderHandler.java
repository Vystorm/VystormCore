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
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private volatile Executor serverExecutor = PlaceholderHandler::bukkitRunTask;
    private volatile Function<OfflinePlayer, String> language = PlaceholderHandler::bukkitLanguage;
    private volatile Consumer<String> warnings = PlaceholderHandler::bukkitWarning;
    private final Set<String> reportedRankings = ConcurrentHashMap.newKeySet();
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

    private static void bukkitRunTask(Runnable task) {
        Bukkit.getScheduler().runTask(Vystorm.vystorm_core.main.getPlugin(), task);
    }

    private static String bukkitLanguage(OfflinePlayer viewer) {
        try {
            Vystorm.vystorm_core.lang.Lang core = Vystorm.vystorm_core.lang.Localization.get().core();
            if (viewer == null) return core.language((org.bukkit.command.CommandSender) null);
            Player online = viewer.getPlayer();
            return online != null ? core.language(online) : core.languageFor(viewer.getUniqueId(), List.of());
        } catch (RuntimeException | LinkageError noLanguages) {
            return "en";
        }
    }

    private static void bukkitWarning(String line) {
        java.util.logging.Logger logger;
        try { logger = Vystorm.vystorm_core.main.getPlugin().getLogger(); }
        catch (RuntimeException | LinkageError noServer) { logger = java.util.logging.Logger.getLogger("VystormCore"); }
        logger.warning(line);
    }

    /** Tests: where {@link #topAsync} runs its work when called off the server thread. */
    void serverExecutor(Executor executor) { serverExecutor = Objects.requireNonNull(executor, "executor"); }

    /** Tests: the language code of a viewer, for ranking values. */
    void language(Function<OfflinePlayer, String> language) { this.language = Objects.requireNonNull(language, "language"); }

    /** Tests: where the once-per-run ranking failures are written. */
    void warnings(Consumer<String> warnings) { this.warnings = Objects.requireNonNull(warnings, "warnings"); }

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
        reportedRankings.clear();
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

    // ------------------------------------------------------------------ rankings

    /** Most rows a ranking gives: {@code n} is capped here. */
    static final int MAX_ROWS = RANKING_MAX_ROWS;

    @Override
    public List<PlaceholderRanking> rankings() {
        List<PlaceholderRanking> out = new ArrayList<>();
        for (PlaceholderNamespace space : namespaces()) out.addAll(space.rankings());
        return Collections.unmodifiableList(out);
    }

    @Override
    public Optional<PlaceholderRanking> ranking(String id) {
        Ranking ranking = find(id);
        return ranking == null ? Optional.empty() : Optional.of(ranking.view());
    }

    @Override
    public List<RankingSource.Row> top(String id, int n) {
        requireServerThread();
        Ranking ranking = find(id);
        return ranking == null || !enabled(ranking.space.owner) ? List.of() : ranking.rows(n);
    }

    @Override
    public CompletableFuture<List<RankingSource.Row>> topAsync(String id, int n) {
        if (serverThread.getAsBoolean()) {
            try { return CompletableFuture.completedFuture(top(id, n)); }
            catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        }
        CompletableFuture<List<RankingSource.Row>> result = new CompletableFuture<>();
        try {
            serverExecutor.execute(() -> {
                try { result.complete(top(id, n)); }
                catch (RuntimeException | LinkageError failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException | LinkageError notRunning) {
            result.completeExceptionally(new IllegalStateException("The server does not run tasks now (Core disabled?)", notRunning));
        }
        return result;
    }

    @Override
    public OptionalInt rank(String id, UUID player) {
        requireServerThread();
        Ranking ranking = find(id);
        return ranking == null || player == null || !enabled(ranking.space.owner) ? OptionalInt.empty() : ranking.rankOf(player, false);
    }

    @Override
    public OptionalDouble rankingValue(String id, UUID player) {
        requireServerThread();
        Ranking ranking = find(id);
        return ranking == null || player == null || !enabled(ranking.space.owner) ? OptionalDouble.empty() : ranking.valueOf(player, false);
    }

    private void requireServerThread() {
        if (!serverThread.getAsBoolean())
            throw new IllegalStateException("Rankings are read on the server thread; off it use api.placeholders().topAsync(id, n)");
    }

    private Ranking find(String id) {
        if (id == null) return null;
        int cut = id.indexOf(':');
        if (cut <= 0 || cut == id.length() - 1) return null;
        Space space = spaces.get(id.substring(0, cut).trim().toLowerCase(Locale.ROOT));
        return space == null ? null : space.rankingMap.get(id.substring(cut + 1).trim().toLowerCase(Locale.ROOT));
    }

    /**
     * A ranking key: lower-case letters, digits and {@code -}, 1 to 32 characters, starting and ending with a letter
     * or digit. No underscore, so {@code top_<key>_<n>_<field>} splits in exactly one way.
     */
    static String checkRankingKey(String key) {
        Objects.requireNonNull(key, "key");
        String folded = key.toLowerCase(Locale.ROOT);
        int length = folded.length();
        boolean valid = length > 0 && length <= 32;
        for (int i = 0; valid && i < length; i++) {
            char c = folded.charAt(i);
            boolean word = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            valid = word || (c == '-' && i > 0 && i < length - 1);
        }
        if (!valid) throw new IllegalArgumentException("Invalid ranking key '" + key
                + "': lower-case letters, digits and '-', 1 to 32 characters, starting and ending with a letter or digit");
        return folded;
    }

    /** One declared ranking: its source, the three placeholder values made for it and the rows of the current tick. */
    private final class Ranking {
        final Space space;
        final String key;
        final RankingInfo info;
        final RankingSource source;
        final PlaceholderValue top, rank, value;
        private volatile long cachedTick = Long.MIN_VALUE;
        private volatile List<RankingSource.Row> cached = List.of();

        Ranking(Space space, String key, RankingInfo info, RankingSource source) {
            this.space = space;
            this.key = key;
            this.info = info;
            this.source = source;
            this.top = PlaceholderValue.of("top_" + key, this::resolveTop).description(info.label())
                    .arguments("<n> <name|value|raw|uuid>");
            this.rank = PlaceholderValue.of("rank_" + key, this::resolveRank).description(info.label());
            this.value = PlaceholderValue.of("value_" + key, this::resolveValue).description(info.label());
        }

        PlaceholderRanking view() { return new PlaceholderRanking(space.name, key, info, space.owner); }

        String id() { return space.name + ":" + key; }

        /** Asks the source; a failure is logged once per ranking and server run and gives an empty list. */
        List<RankingSource.Row> rows(int n) {
            int limit = Math.min(n, MAX_ROWS);
            if (limit <= 0) return List.of();
            List<RankingSource.Row> answer;
            try { answer = source.top(limit); }
            catch (RuntimeException | LinkageError failure) {
                failed("top", failure);
                return List.of();
            }
            if (answer == null || answer.isEmpty()) return List.of();
            List<RankingSource.Row> rows = new ArrayList<>(Math.min(answer.size(), limit));
            for (RankingSource.Row row : answer) if (row != null && Double.isFinite(row.value())) rows.add(row);
            Comparator<RankingSource.Row> order = Comparator.comparingDouble(RankingSource.Row::value);
            rows.sort(info.order() == RankingInfo.Order.ASCENDING ? order : order.reversed());
            return List.copyOf(rows.size() > limit ? rows.subList(0, limit) : rows);
        }

        /** The best 100 rows, asked at most once a server tick (all placeholders of one board share them). */
        List<RankingSource.Row> tickRows() {
            long now = tick.getAsLong();
            if (cachedTick == now) return cached;
            List<RankingSource.Row> rows = rows(MAX_ROWS);
            cached = rows;
            cachedTick = now;
            return rows;
        }

        OptionalInt rankOf(UUID player, boolean shared) {
            try {
                OptionalInt own = source.rank(player);
                if (own != null && own.isPresent()) return own;
            } catch (RuntimeException | LinkageError failure) {
                failed("rank", failure);
            }
            List<RankingSource.Row> rows = shared ? tickRows() : rows(MAX_ROWS);
            for (int i = 0; i < rows.size(); i++) if (player.equals(rows.get(i).player())) return OptionalInt.of(i + 1);
            return OptionalInt.empty();
        }

        OptionalDouble valueOf(UUID player, boolean shared) {
            try {
                OptionalDouble own = source.value(player);
                if (own != null && own.isPresent() && Double.isFinite(own.getAsDouble())) return own;
            } catch (RuntimeException | LinkageError failure) {
                failed("value", failure);
            }
            for (RankingSource.Row row : shared ? tickRows() : rows(MAX_ROWS))
                if (player.equals(row.player())) return OptionalDouble.of(row.value());
            return OptionalDouble.empty();
        }

        private void failed(String call, Throwable failure) {
            String id = id();
            if (!reportedRankings.add(id)) return;
            String line = "[Placeholders] Ranking " + id + " of " + space.ownerName + " failed in " + call
                    + "() and counts as empty (logged once per server run): " + failure;
            try { warnings.accept(line); }
            catch (RuntimeException ignored) { /* logging must not break a placeholder */ }
        }

        private Object resolveTop(PlaceholderContext context) {
            if (context.arguments().size() != 2) return null;
            int n = context.intArgument(0, -1);
            if (n < 1 || n > MAX_ROWS) return null;
            String field = context.argument(1).toLowerCase(Locale.ROOT);
            if (!field.equals("name") && !field.equals("value") && !field.equals("raw") && !field.equals("uuid")) return null;
            List<RankingSource.Row> rows = tickRows();
            if (n > rows.size()) return "";
            RankingSource.Row row = rows.get(n - 1);
            return switch (field) {
                case "name" -> row.name();
                case "value" -> info.format().format(row.value(), language(context.viewer()));
                case "raw" -> raw(row.value());
                default -> row.player().toString();
            };
        }

        private Object resolveRank(PlaceholderContext context) {
            if (!context.arguments().isEmpty() || context.viewerId() == null) return null;
            OptionalInt rank = rankOf(context.viewerId(), true);
            return rank.isPresent() ? Integer.toString(rank.getAsInt()) : "-";
        }

        private Object resolveValue(PlaceholderContext context) {
            if (!context.arguments().isEmpty() || context.viewerId() == null) return null;
            OptionalDouble own = valueOf(context.viewerId(), true);
            return own.isPresent() ? info.format().format(own.getAsDouble(), language(context.viewer())) : "-";
        }
    }

    private String language(OfflinePlayer viewer) {
        try {
            String code = language.apply(viewer);
            return code == null ? "en" : code;
        } catch (RuntimeException | LinkageError failure) {
            return "en";
        }
    }

    /** A raw value as plain text: whole numbers without decimals, others with the decimals they have, never an exponent. */
    static String raw(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
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
        /** Declared rankings in declaration order. */
        volatile Map<String, Ranking> rankingMap = Map.of();
        /** The values made for rankings (by identity): a plugin's own placeholders and its fallback come first. */
        volatile Set<PlaceholderValue> generated = Set.of();
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

        @Override
        public PlaceholderNamespace ranking(String key, RankingInfo info, RankingSource source) {
            String folded = checkRankingKey(key);
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(source, "source");
            synchronized (lock) {
                Ranking ranking = new Ranking(this, folded, info, source);
                Map<String, PlaceholderValue> next = new LinkedHashMap<>(values);
                Set<PlaceholderValue> made = new java.util.HashSet<>(generated);
                Ranking old = rankingMap.get(folded);
                if (old != null) withdrawValues(old, next, made);
                for (PlaceholderValue value : List.of(ranking.top, ranking.rank, ranking.value)) {
                    PlaceholderValue present = next.get(value.key());
                    if (present != null && !made.contains(present)) continue;   // the plugin's own placeholder wins
                    if (present != null) made.remove(present);
                    next.put(value.key(), value);
                    made.add(value);
                }
                Map<String, Ranking> nextRankings = new LinkedHashMap<>(rankingMap);
                nextRankings.put(folded, ranking);
                generated = Set.copyOf(made);
                rankingMap = Collections.unmodifiableMap(nextRankings);
                publish(next);
            }
            cache.clear();
            return this;
        }

        @Override
        public boolean removeRanking(String key) {
            if (key == null) return false;
            synchronized (lock) {
                Ranking old = rankingMap.get(key.toLowerCase(Locale.ROOT));
                if (old == null) return false;
                Map<String, PlaceholderValue> next = new LinkedHashMap<>(values);
                Set<PlaceholderValue> made = new java.util.HashSet<>(generated);
                withdrawValues(old, next, made);
                Map<String, Ranking> nextRankings = new LinkedHashMap<>(rankingMap);
                nextRankings.remove(old.key);
                generated = Set.copyOf(made);
                rankingMap = Collections.unmodifiableMap(nextRankings);
                publish(next);
            }
            cache.clear();
            return true;
        }

        /** Removes the values of a ranking that are still the ones made for it (not replaced by the plugin). */
        private void withdrawValues(Ranking ranking, Map<String, PlaceholderValue> next, Set<PlaceholderValue> made) {
            for (PlaceholderValue value : List.of(ranking.top, ranking.rank, ranking.value)) {
                made.remove(value);
                if (next.get(value.key()) == value) next.remove(value.key());
            }
        }

        @Override
        public List<PlaceholderRanking> rankings() {
            List<PlaceholderRanking> out = new ArrayList<>();
            for (Ranking ranking : rankingMap.values()) out.add(ranking.view());
            return Collections.unmodifiableList(out);
        }

        /**
         * Percent form: {@code rest} is {@code <key>[_<arguments>]} and the key may contain underscores. A value made
         * for a ranking only answers when no shorter declared value of the plugin and not its fallback answer.
         */
        Resolved longest(OfflinePlayer viewer, Player other, String rest) {
            Map<String, PlaceholderValue> declared = values;
            Set<PlaceholderValue> made = generated;
            String lower = rest.toLowerCase(Locale.ROOT);
            int length = lower.length();
            int end = length;
            int parts = maxParts;
            PlaceholderValue spare = null;
            int spareEnd = 0;
            if (parts > 0) {
                int at = -1;
                for (int seen = 0; seen < parts; seen++) {
                    at = lower.indexOf('_', at + 1);
                    if (at < 0) break;
                }
                if (at >= 0) end = at;
                while (end > 0) {
                    PlaceholderValue value = declared.get(end == length ? lower : lower.substring(0, end));
                    if (value != null && made.contains(value)) {
                        if (spare == null) { spare = value; spareEnd = end; }
                    } else if (value != null) {
                        List<String> arguments = end == length ? List.of() : split(rest, end + 1, '_');
                        Resolved own = invoke(this, value, value.resolver(), value.isAsyncSafe(), viewer, other, value.key(), arguments, rest, "_");
                        if (own != null || spare == null) return own;
                        return generatedAnswer(spare, spareEnd, viewer, other, rest, length);
                    }
                    end = lower.lastIndexOf('_', end - 1);
                }
            }
            PlaceholderResolver resolver = fallback;
            if (resolver != null) {
                int cut = lower.indexOf('_');
                String key = cut < 0 ? lower : lower.substring(0, cut);
                List<String> arguments = cut < 0 ? List.of() : split(rest, cut + 1, '_');
                Resolved own = invoke(this, null, resolver, fallbackAsync, viewer, other, key, arguments, rest, "_");
                if (own != null || spare == null) return own;
            }
            return spare == null ? null : generatedAnswer(spare, spareEnd, viewer, other, rest, length);
        }

        private Resolved generatedAnswer(PlaceholderValue value, int end, OfflinePlayer viewer, Player other, String rest, int length) {
            List<String> arguments = end == length ? List.of() : split(rest, end + 1, '_');
            return invoke(this, value, value.resolver(), value.isAsyncSafe(), viewer, other, value.key(), arguments, rest, "_");
        }

        /** Brace form and direct lookups: the key is given. The plugin's fallback comes before a value made for a ranking. */
        Resolved direct(OfflinePlayer viewer, Player other, String key, List<String> arguments, String raw, String separator) {
            PlaceholderValue value = values.get(key);
            PlaceholderResolver resolver = fallback;
            if (value != null && resolver != null && generated.contains(value)) {
                Resolved own = invoke(this, null, resolver, fallbackAsync, viewer, other, key, arguments, raw, separator);
                if (own != null) return own;
            }
            if (value != null)
                return invoke(this, value, value.resolver(), value.isAsyncSafe(), viewer, other, value.key(), arguments, raw, separator);
            return resolver == null ? null : invoke(this, null, resolver, fallbackAsync, viewer, other, key, arguments, raw, separator);
        }

        @Override public String toString() { return "PlaceholderNamespace[" + name + " of " + ownerName + ", " + values.size() + " values]"; }
    }
}
