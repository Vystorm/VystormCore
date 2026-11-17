package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.web.WebException;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Live-Kanal Server → Browser (Server-Sent Events unter {@code GET /core/live}). Plugins veröffentlichen kleine,
 * typisierte Ereignisse je Spieler und Modul ({@code WebServices.push}); der Hub fasst sie pro Spieler zusammen
 * (gleicher Modul+Typ innerhalb von {@code coalesce-ms}: nur der letzte Wert) und verteilt sie an alle offenen Tabs und
 * Overlays des Spielers, jeweils nur mit gültiger Sitzung und Modulberechtigung.
 *
 * <p>Nachrichten (je eine SSE-{@code data:}-Zeile, JSON):
 * <ul>
 *   <li>{@code {"kind":"event","module":…,"type":…,"data":…,"snapshot":bool}}</li>
 *   <li>{@code {"kind":"ready"}}: Snapshot vollständig, danach nur noch Änderungen</li>
 *   <li>{@code {"kind":"data","module":…,"name":…,"url":…}}: statische Spieldaten haben eine neue Version</li>
 *   <li>{@code {"kind":"bye","reason":…}}: Server beendet den Kanal (Logout, Entzug, Überlauf → neu verbinden)</li>
 * </ul>
 * Threadsicher. Core-intern.
 *
 * @since 0.18.0
 */
public final class LiveHub implements AutoCloseable {
    public static final Pattern TYPE = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,63}");
    static final int QUEUE_FRAMES = 256;
    static final int MAX_PENDING_KEYS = 128;
    static final long HEARTBEAT_MS = 20_000;
    private static final Gson GSON = new Gson();

    private final WebSettings settings;
    private final PlayerAccess players;
    private final SessionStore sessions;
    private final ModuleRegistry modules;
    private final Map<UUID, Set<Subscription>> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Vystorm-Web-Live");
        t.setDaemon(true);
        return t;
    });

    private static final class Pending {
        final LinkedHashMap<String, String> events = new LinkedHashMap<>();
        boolean scheduled;
        boolean overflow;
    }

    public LiveHub(WebSettings settings, PlayerAccess players, SessionStore sessions, ModuleRegistry modules) {
        this.settings = settings;
        this.players = players;
        this.sessions = sessions;
        this.modules = modules;
        sessions.onRevoke(this::sessionEnded);
        scheduler.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- Abonnement

    /**
     * Öffnet einen Live-Kanal. Bis {@link Subscription#ready()} werden Push-Ereignisse gepuffert, damit sie nach dem
     * Snapshot ankommen (sie sind neuer).
     *
     * @param moduleFilter Module, die der Client sehen will; leer = alle erlaubten
     * @throws WebException 429 über dem Limit je Spieler, 503 über dem Gesamtlimit
     */
    public Subscription subscribe(SessionStore.Session session, Set<String> moduleFilter) {
        Subscription subscription = new Subscription(session, Set.copyOf(moduleFilter));
        // Prüfen und Eintragen atomar (sonst überschreiten parallele Verbindungsaufbauten das Limit je Spieler).
        byPlayer.compute(session.player(), (id, own) -> {
            Set<Subscription> set = own == null ? ConcurrentHashMap.newKeySet() : own;
            if (set.size() >= settings.liveMaxPerPlayer()) throw new WebException(429, Vystorm.vystorm_core.web.internal.WebText.error("too-many-live"));
            if (total.incrementAndGet() > settings.liveMaxConnections()) {
                total.decrementAndGet();
                throw new WebException(503, Vystorm.vystorm_core.web.internal.WebText.error("live-busy"));
            }
            set.add(subscription);
            return set;
        });
        return subscription;
    }

    public int connections() { return total.get(); }

    /** Players with at least one open live connection (snapshot). @since 0.24.0 */
    public Set<UUID> connectedPlayers() {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        byPlayer.forEach((id, own) -> { if (!own.isEmpty()) out.add(id); });
        return out;
    }

    public long dropped() { return dropped.get(); }

    // ---------------------------------------------------------------- Veröffentlichen

    /**
     * Veröffentlicht ein Ereignis für einen Spieler. Ohne offene Live-Verbindung des Spielers passiert nichts.
     *
     * @return ob mindestens eine Verbindung des Spielers existiert (das Ereignis also zugestellt wird)
     * @throws IllegalArgumentException bei ungültigem Typ oder zu großem Ereignis
     */
    public boolean publish(UUID player, String module, String type, JsonElement data) {
        if (player == null || module == null || type == null) throw new IllegalArgumentException("player/module/type missing");
        if (!TYPE.matcher(type).matches()) throw new IllegalArgumentException("invalid event type: " + type);
        Set<Subscription> own = byPlayer.get(player);
        if (own == null || own.isEmpty()) return false;
        JsonObject event = new JsonObject();
        event.addProperty("kind", "event");
        event.addProperty("module", module);
        event.addProperty("type", type);
        event.add("data", data == null ? com.google.gson.JsonNull.INSTANCE : data);
        event.addProperty("snapshot", false);
        String json = GSON.toJson(event);
        if (json.getBytes(StandardCharsets.UTF_8).length > settings.liveMaxEventBytes())
            throw new IllegalArgumentException("web event too large (" + module + "/" + type + ", max " + settings.liveMaxEventBytes() + " bytes)");
        Pending p = pending.computeIfAbsent(player, k -> new Pending());
        boolean schedule;
        synchronized (p) {
            p.events.remove(module + "\n" + type);
            p.events.put(module + "\n" + type, json);
            if (p.events.size() > MAX_PENDING_KEYS) { p.events.clear(); p.overflow = true; dropped.incrementAndGet(); }
            schedule = !p.scheduled;
            p.scheduled = true;
        }
        if (schedule) {
            try { scheduler.schedule(() -> flush(player), settings.liveCoalesceMs(), TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { return false; }
        }
        return true;
    }

    /** Meldet allen Abonnenten des Moduls eine neue Version statischer Spieldaten. */
    public void dataChanged(String module, String name, String url) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "data");
        o.addProperty("module", module);
        o.addProperty("name", name);
        o.addProperty("url", url);
        String frame = frame(GSON.toJson(o));
        for (Set<Subscription> own : byPlayer.values())
            for (Subscription s : own) if (s.accepts(module) && mayReceive(s.session.player(), module)) s.offer(frame);
    }

    private void flush(UUID player) {
        Pending p = pending.get(player);
        if (p == null) return;
        List<Map.Entry<String, String>> events;
        boolean overflow;
        synchronized (p) {
            events = new ArrayList<>(p.events.entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList());
            overflow = p.overflow;
            p.events.clear();
            p.overflow = false;
            p.scheduled = false;
        }
        pending.remove(player, p);
        Set<Subscription> own = byPlayer.get(player);
        if (own == null) return;
        for (Subscription s : own) {
            if (overflow) { s.closeWith("resync"); continue; }
            for (Map.Entry<String, String> event : events) {
                String module = event.getKey().substring(0, event.getKey().indexOf('\n'));
                if (s.accepts(module) && mayReceive(player, module)) s.offer(frame(event.getValue()));
            }
        }
    }

    boolean mayReceive(UUID player, String module) {
        if (!players.has(player, WebDispatcher.USE_PERMISSION)) return false;
        var registered = modules.get(module);
        if (registered.isEmpty()) return false;
        return registered.get().module().permission().map(node -> players.has(player, node)).orElse(true);
    }

    static String frame(String json) { return "data: " + json + "\n\n"; }

    // ---------------------------------------------------------------- Pflege

    private void heartbeat() {
        for (Set<Subscription> own : byPlayer.values()) {
            for (Subscription s : own) {
                // Ein offener Tab hält die Sitzung aktiv (bis zum absoluten Ende).
                if (!sessions.alive(s.session, true)) s.closeWith("session");
                else if (!players.has(s.session.player(), WebDispatcher.USE_PERMISSION)) s.closeWith("permission");
            }
        }
    }

    private void sessionEnded(SessionStore.Session session) {
        Set<Subscription> own = byPlayer.get(session.player());
        if (own == null) return;
        for (Subscription s : own) if (s.session.id().equals(session.id())) s.closeWith("logout");
    }

    @Override public void close() {
        for (Set<Subscription> own : byPlayer.values()) for (Subscription s : own) s.closeWith("shutdown");
        byPlayer.clear();
        pending.clear();
        scheduler.shutdownNow();
    }

    /** Ein offener Live-Kanal (ein Tab/Overlay). */
    public final class Subscription implements ResponseStream {
        private final SessionStore.Session session;
        private final Set<String> filter;
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_FRAMES);
        private final List<String> buffer = new ArrayList<>();
        private final List<Runnable> closeActions = new CopyOnWriteArrayList<>();
        private volatile boolean buffering = true;
        private volatile boolean closed;

        private Subscription(SessionStore.Session session, Set<String> filter) {
            this.session = session;
            this.filter = filter;
        }

        public SessionStore.Session session() { return session; }

        boolean accepts(String module) { return filter.isEmpty() || filter.contains(module); }

        /** Ein Snapshot-Ereignis direkt in die Warteschlange (vor {@link #ready()}). */
        public void snapshot(String module, String type, JsonElement data) {
            if (!TYPE.matcher(type).matches()) return;
            JsonObject event = new JsonObject();
            event.addProperty("kind", "event");
            event.addProperty("module", module);
            event.addProperty("type", type);
            event.add("data", data == null ? com.google.gson.JsonNull.INSTANCE : data);
            event.addProperty("snapshot", true);
            String json = GSON.toJson(event);
            if (json.getBytes(StandardCharsets.UTF_8).length > settings.liveMaxEventBytes() * 4L) return;
            enqueue(frame(json));
        }

        /** SSE-Wiederverbindungszeit für den Browser. */
        public void retry(long ms) { enqueue("retry: " + ms + "\n\n"); }

        /** Snapshot fertig: {@code ready} senden und gepufferte Push-Ereignisse nachliefern. */
        public void ready() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", "ready");
            List<String> buffered;
            synchronized (this) {
                enqueue(frame(GSON.toJson(o)));
                buffered = new ArrayList<>(buffer);
                buffer.clear();
                buffering = false;
            }
            for (String f : buffered) enqueue(f);
        }

        void offer(String frame) {
            if (closed) return;
            synchronized (this) {
                if (buffering) {
                    if (buffer.size() >= QUEUE_FRAMES) { closeWith("resync"); return; }
                    buffer.add(frame);
                    return;
                }
            }
            enqueue(frame);
        }

        private void enqueue(String frame) {
            if (closed) return;
            if (!queue.offer(frame)) closeWith("resync"); // Client liest nicht mit: neu verbinden, frischer Snapshot
        }

        /** Beendet den Kanal mit einer letzten {@code bye}-Nachricht. */
        public void closeWith(String reason) {
            if (closed) return;
            JsonObject o = new JsonObject();
            o.addProperty("kind", "bye");
            o.addProperty("reason", reason);
            queue.clear();
            queue.offer(frame(GSON.toJson(o)));
            finish();
        }

        @Override public String next(long timeoutMs) throws InterruptedException {
            String frame = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (frame != null) return END.equals(frame) ? END : frame;
            return closed ? END : null;
        }

        @Override public void close() { finish(); }

        @Override public void onClose(Runnable action) {
            closeActions.add(action);
            if (closed) action.run();
        }

        private void finish() {
            if (closed) return;
            closed = true;
            byPlayer.computeIfPresent(session.player(), (id, own) -> {
                if (own.remove(this)) total.decrementAndGet();
                return own.isEmpty() ? null : own;
            });
            // Nach dem bye kurz Zeit zum Schreiben lassen, dann Transport schließen (löst blockierte Writes).
            queue.offer(END); // weckt einen wartenden next()
            if (queue.size() <= 1) runCloseActions();
            else {
                try { scheduler.schedule(this::runCloseActions, 2, TimeUnit.SECONDS); }
                catch (java.util.concurrent.RejectedExecutionException stopped) { runCloseActions(); }
            }
        }

        private void runCloseActions() {
            for (Runnable action : closeActions) {
                try { action.run(); } catch (RuntimeException ignored) { /* Transport schon zu */ }
            }
        }
    }
}
