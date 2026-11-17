package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.web.WebMethod;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebRoute;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.plugin.Plugin;

/**
 * Registrierte Web-Module mit ihren geladenen Dateien. Threadsicher; Lesezugriffe sind lockfrei. Core-intern.
 *
 * @since 0.17.0
 */
public final class ModuleRegistry {
    /** Modul-ID, die Core für das eingebaute Profil-Modul reserviert. */
    public static final String CORE_MODULE = "core";
    /** IDs, die mit Unterbefehlen von {@code /web} kollidieren würden. */
    static final Set<String> RESERVED = Set.of("logout", "status", "link");

    /** Ein registriertes Modul. */
    public record Registered(Plugin owner, WebModule module, Map<String, StaticAssets.Asset> assets) {
        public boolean active() {
            try { return owner.isEnabled(); } catch (RuntimeException failure) { return false; }
        }
    }

    /**
     * Statische Spieldaten eines Moduls (z. B. das Talentbaum-Layout) als JSON mit Inhalts-Hash.
     * {@link #url()} ist versioniert und damit unbegrenzt cachebar.
     */
    public record Data(String module, String name, byte[] json, String etag, String version) {
        public String url() { return "/data/" + module + "/" + name + "." + version + ".json"; }
        public String latestUrl() { return "/data/" + module + "/" + name + ".json"; }
    }

    /** Erlaubte Namen für Spieldaten. */
    public static final java.util.regex.Pattern DATA_NAME = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    static final int MAX_DATA_BYTES = 8 * 1024 * 1024;
    static final int MAX_DATA_PER_MODULE = 64;

    /** Ergebnis der Routensuche. */
    public record Match(Registered module, WebRoute route, Map<String, String> params) {}

    private final Map<String, Registered> modules = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Data>> data = new ConcurrentHashMap<>();
    private final List<java.util.function.Consumer<Data>> dataListeners = new CopyOnWriteArrayList<>();
    private volatile Plugin corePlugin;

    /** Das Plugin, dem die ID {@link #CORE_MODULE} gehört. */
    public void corePlugin(Plugin core) { this.corePlugin = core; }

    /**
     * Registriert {@code module} und lädt seine Dateien aus dem Jar von {@code owner}.
     *
     * @throws IllegalStateException wenn ein anderes, aktives Plugin die ID schon belegt
     * @throws IllegalArgumentException wenn die Dateien nicht geladen werden können
     */
    public Registered register(Plugin owner, WebModule module) {
        if (owner == null || module == null) throw new IllegalArgumentException("owner/module missing");
        if (module.id().equals(CORE_MODULE) && corePlugin != null && owner != corePlugin)
            throw new IllegalStateException("module id 'core' is reserved for Vystorm Core");
        if (RESERVED.contains(module.id())) throw new IllegalArgumentException("module id '" + module.id() + "' is reserved (/web subcommand)");
        Map<String, StaticAssets.Asset> assets = new LinkedHashMap<>();
        if (module.assetPrefix().isPresent()) {
            try {
                assets.putAll(StaticAssets.load(owner.getClass(), owner.getClass().getClassLoader(), module.assetPrefix().get()));
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException("web assets of module " + module.id() + " could not be loaded: " + failure.getMessage(), failure);
            }
        }
        for (Map.Entry<String, byte[]> inline : module.inlineAssets().entrySet()) {
            String type = StaticAssets.contentType(inline.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("asset type not allowed: " + inline.getKey()));
            assets.put(inline.getKey(), StaticAssets.asset(inline.getValue(), type));
        }
        Registered created = new Registered(owner, module, Map.copyOf(assets));
        Registered[] previous = new Registered[1];
        modules.compute(module.id(), (id, existing) -> {
            if (existing != null && existing.owner() != owner && existing.active())
                throw new IllegalStateException("web module '" + id + "' is already registered by " + existing.owner().getName());
            previous[0] = existing;
            return created;
        });
        changed();
        return created;
    }

    /** Entfernt alle Module von {@code owner} (mit ihren Spieldaten). */
    public int unregister(Plugin owner) {
        List<String> removed = new ArrayList<>();
        modules.values().removeIf(r -> { boolean hit = r.owner() == owner; if (hit) removed.add(r.module().id()); return hit; });
        removed.forEach(data::remove);
        if (!removed.isEmpty()) changed();
        return removed.size();
    }

    public boolean unregister(Plugin owner, String id) {
        Registered current = id == null ? null : modules.get(id);
        boolean removed = current != null && current.owner() == owner && modules.remove(id, current);
        if (removed) { data.remove(id); changed(); }
        return removed;
    }

    /** Aktives Modul (Owner aktiviert). */
    public Optional<Registered> get(String id) {
        Registered r = id == null ? null : modules.get(id);
        return r != null && r.active() ? Optional.of(r) : Optional.empty();
    }

    public Collection<Registered> all() { return modules.values().stream().filter(Registered::active).toList(); }

    public Set<String> ids() {
        Set<String> out = new TreeSet<>();
        for (Registered r : all()) out.add(r.module().id());
        return out;
    }

    /** Alle Berechtigungen, die Module und Routen verlangen. */
    public Set<String> permissionNodes() {
        Set<String> out = new TreeSet<>();
        for (Registered r : all()) {
            r.module().permission().ifPresent(out::add);
            for (WebRoute route : r.module().routes()) route.permission().ifPresent(out::add);
        }
        return out;
    }

    /** Stand der Registrierungen; steigt bei jeder Änderung (Gateway-Cache). */
    public long revision() { return revision.get(); }

    public void onChange(Runnable listener) { listeners.add(listener); }

    public void clearListeners() { listeners.clear(); dataListeners.clear(); }

    /**
     * Veröffentlicht (oder ersetzt) Spieldaten eines Moduls, das {@code owner} registriert hat.
     *
     * @throws IllegalStateException wenn das Modul fehlt oder einem anderen Plugin gehört
     * @throws IllegalArgumentException bei ungültigem Namen oder zu großen Daten
     */
    public Data publishData(Plugin owner, String module, String name, com.google.gson.JsonElement json) {
        if (name == null || !DATA_NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid data name: " + name);
        Registered registered = module == null ? null : modules.get(module);
        if (registered == null || registered.owner() != owner) throw new IllegalStateException("web module '" + module + "' is not registered by this plugin");
        byte[] bytes = new com.google.gson.Gson().toJson(json == null ? com.google.gson.JsonNull.INSTANCE : json).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_DATA_BYTES) throw new IllegalArgumentException("web data too large (max " + MAX_DATA_BYTES + " bytes)");
        String etag = StaticAssets.etag(bytes);
        Data entry = new Data(module, name, bytes, etag, etag.substring(1, 13));
        Map<String, Data> own = data.computeIfAbsent(module, k -> new ConcurrentHashMap<>());
        if (!own.containsKey(name) && own.size() >= MAX_DATA_PER_MODULE) throw new IllegalArgumentException("too many data sets in module " + module);
        Data previous = own.put(name, entry);
        if (previous == null || !previous.etag().equals(etag)) {
            for (var listener : dataListeners) {
                try { listener.accept(entry); } catch (RuntimeException ignored) { /* fehlertolerant */ }
            }
        }
        return entry;
    }

    public Optional<Data> data(String module, String name) {
        Map<String, Data> own = module == null ? null : data.get(module);
        Data entry = own == null || name == null ? null : own.get(name);
        return entry != null && get(module).isPresent() ? Optional.of(entry) : Optional.empty();
    }

    /** Alle Spieldaten eines Moduls (Name → Eintrag). */
    public Map<String, Data> data(String module) {
        Map<String, Data> own = module == null ? null : data.get(module);
        return own == null ? Map.of() : Map.copyOf(own);
    }

    /** Wird bei jeder neuen Datenversion aufgerufen. */
    public void onData(java.util.function.Consumer<Data> listener) { dataListeners.add(listener); }

    /**
     * Sucht die Route zu {@code segments} (bereits dekodiert). Liefert {@code null}-Route mit leerer Map, wenn der Pfad
     * existiert, aber nicht für diese Methode (→ 405).
     */
    public static Optional<Match> match(Registered module, WebMethod method, List<String> segments, boolean[] pathExists) {
        for (WebRoute route : module.module().routes()) {
            Map<String, String> params = matchSegments(route.segments(), segments);
            if (params == null) continue;
            if (pathExists != null && pathExists.length > 0) pathExists[0] = true;
            if (route.method() == method) return Optional.of(new Match(module, route, params));
        }
        return Optional.empty();
    }

    /** Erlaubte Methoden für einen Pfad (für {@code Allow}). */
    public static List<String> allowed(Registered module, List<String> segments) {
        List<String> out = new ArrayList<>();
        for (WebRoute route : module.module().routes())
            if (matchSegments(route.segments(), segments) != null && !out.contains(route.method().name())) out.add(route.method().name());
        return out;
    }

    private static Map<String, String> matchSegments(List<String> pattern, List<String> actual) {
        if (pattern.size() != actual.size()) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.size(); i++) {
            String p = pattern.get(i);
            String name = WebRoute.parameterName(p);
            if (name == null) {
                if (!p.equals(actual.get(i))) return null;
            } else {
                params.put(name, actual.get(i));
            }
        }
        return params;
    }

    private void changed() {
        revision.incrementAndGet();
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { /* Listener sind Core-intern und fehlertolerant */ }
        }
    }
}
