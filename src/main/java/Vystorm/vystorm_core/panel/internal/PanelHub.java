package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.VystormPanelCloseEvent;
import Vystorm.vystorm_core.panel.VystormPanelErrorEvent;
import Vystorm.vystorm_core.panel.VystormPanelOpenEvent;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.TokenBucket;
import Vystorm.vystorm_core.web.internal.clientproto.panel.ActionCheck;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelMessage;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Transfer;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Sitzungen, Prüfung jeder Aktion und Neu-Rendern (Spec §2.4, §5). Pro Spieler ein Stapel (oben = sichtbar, ≤ 5).
 * P_ACTION: Spieler-Ratenlimit (20/s Burst 40) zuerst, dann Sitzung gehört dem Spieler und ist oben, {@code seq} neu
 * (Doppelte werden ohne Antwort verworfen), Element existiert (erst dann ein Element-Bucket 10/s), Besitzer aktiv, Recht
 * des Panels erneut, {@link ActionCheck} gegen das **gesendete** Dokument, dann der Handler auf dem Server-Thread; jede
 * nicht doppelte Aktion bekommt ein P_RESULT (Ratenlimit-Antworten selbst höchstens 2/s, Burst 10). Dieselbe
 * Prüfkette nutzt der Paper-Dialog ({@link PanelDialogs}). {@code refresh} wird pro Tick gebündelt
 * (≤ {@code refreshPerSecond} je Sitzung) und als Diff (P_PATCH) oder volles OPEN mit „Zustand behalten“ geschickt;
 * große Dokumente (≥ 32 KiB geschätzt) werden außerhalb des Server-Threads geprüft, gedifft, serialisiert und gepackt.
 * Ein ersetztes Panel bleibt im Stapel, bis der Client es mit P_CLOSED schließt (verwirft der Client das neue OPEN, lebt
 * das alte weiter). P_ERROR {@code "resync: …"} fordert das volle Dokument an. Nur Server-Thread. Core-intern.
 * @since 0.25.0
 */
public final class PanelHub implements PanelChannel.Listener {
    static final int MAX_STACK = MAX_BACK_STACK + 1;
    /** Ab dieser geschätzten Dokumentgröße laufen Prüfen/Diff/Serialisieren/Packen eines Refreshs nicht auf dem Server-Thread. */
    static final int ASYNC_REFRESH_BYTES = 32 * 1024;
    /** Gleiches Panel per {@code Panels.open} innerhalb dieser Zeit noch einmal → nur neu rendern (kein zweites OPEN). */
    static final long COALESCE_NANOS = 50_000_000L;
    static final int MAX_ELEMENT_BUCKETS = 512;
    /** Nur Test: wirft zu Beginn der Off-Thread-Arbeit (Sicherheitsnetz für Errors). */
    static volatile Runnable testPrepareFault;

    /** Bukkit-Events (in Tests eine Liste). */
    @FunctionalInterface
    public interface Events { void fire(org.bukkit.event.Event event); }

    static final class Session implements PanelSession {
        final int id;
        final Player player;
        final PanelRegistry.Registered reg;
        final String arg;
        final Map<String, Object> state = new HashMap<>();
        final Map<String, TokenBucket> elementBuckets = new HashMap<>();
        final int[] seqRing = new int[64];
        int seqPos;
        JsonObject doc;
        PanelDocument model;
        Map<String, PanelWriter.Handler> handlers = Map.of();
        int rev = 1;
        boolean dirty;
        boolean packing;
        boolean forceFull;
        long lastRender;
        long lastFull;
        long openedAt;

        Session(int id, Player player, PanelRegistry.Registered reg, String arg) {
            this.id = id;
            this.player = player;
            this.reg = reg;
            this.arg = arg == null ? "" : arg;
        }

        @Override public int id() { return id; }
        @SuppressWarnings("unchecked") @Override public <T> T get(String key) { return (T) state.get(key); }
        @Override public void put(String key, Object value) { state.put(key, value); }
        @Override public void remove(String key) { state.remove(key); }

        /** true, wenn {@code seq} schon verarbeitet wurde (Doppelte ignorieren). */
        boolean seen(int seq) {
            for (int s : seqRing) if (s == seq) return true;
            seqRing[seqPos++ % seqRing.length] = seq;
            return false;
        }
    }

    /** Ergebnis der gemeinsamen Prüfkette: entweder {@code handler}+{@code value} oder {@code status} (+ Sprachschlüssel). */
    record Gate(PanelWriter.Handler handler, Object value, int status, String messageKey) {
        static Gate refuse(int status, String messageKey) { return new Gate(null, null, status, messageKey); }

        boolean passed() { return handler != null; }
    }

    private final PanelChannel channel;
    private final LongSupplier nanos;
    private final Logger logger;
    private final Events events;
    private final Consumer<Runnable> offThread;
    private final Consumer<Runnable> mainThread;
    private final Map<UUID, Deque<Session>> stacks = new HashMap<>();
    private final Map<UUID, TokenBucket> playerBuckets = new HashMap<>();
    private final Map<UUID, TokenBucket> rateReplies = new HashMap<>();
    private final Map<UUID, Integer> nextSession = new HashMap<>();
    private final Map<String, Long> errorLog = new HashMap<>();
    private final PanelDialogs dialogs;
    private final PanelScreens screens;
    /** Aktionen mit {@link Result#pending()}, die noch auf {@link PanelAction#complete} warten. */
    private final List<Act> deferred = new java.util.ArrayList<>();
    private int nextTransfer = 1;

    public PanelHub(PanelChannel channel, LongSupplier nanos, Logger logger, Events events, Consumer<Runnable> offThread, Consumer<Runnable> mainThread) {
        this.channel = channel;
        this.nanos = nanos;
        this.logger = logger;
        this.events = events;
        this.offThread = offThread;
        this.mainThread = mainThread;
        this.dialogs = new PanelDialogs(this);
        this.screens = new PanelScreens(this);
    }

    public boolean available(Player p) { return p != null && channel.ready(p.getUniqueId()); }

    public int openSessions(UUID player) {
        Deque<Session> s = stacks.get(player);
        return s == null ? 0 : s.size();
    }

    /** Nur Test: Anzahl Element-Buckets der obersten Sitzung. */
    int elementBuckets(UUID player) {
        Deque<Session> s = stacks.get(player);
        return s == null || s.isEmpty() ? 0 : s.peekFirst().elementBuckets.size();
    }

    Logger logger() { return logger; }

    LongSupplier nanos() { return nanos; }

    // ------------------------------------------------------------------ Öffnen

    /** Natives Panel (Recht schon geprüft). {@code requestId} 0 = Server-Initiative. */
    public PanelOpenResult openNative(Player p, PanelRegistry.Registered reg, String arg, int requestId) {
        Deque<Session> stack = stacks.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        long now = nanos.getAsLong();
        Session top = stack.peekFirst();
        if (requestId == 0 && top != null && top.reg == reg && top.arg.equals(arg == null ? "" : arg) && now - top.openedAt < COALESCE_NANOS) {
            top.dirty = true;   // mehrfaches Panels.open im selben Tick: ein Render, ein OPEN
            return PanelOpenResult.NATIVE;
        }
        int id = nextSession.merge(p.getUniqueId(), 1, Integer::sum);
        Session s = new Session(id, p, reg, arg);
        PanelOpener.seed(s);   // 0.71.0: values carried back by PickReturn
        try {
            render(s);
        } catch (RuntimeException failure) {
            renderFailed(reg, failure);
            if (requestId > 0) channel.send(p, new PanelMessage.Denied(requestId, Protocol.DENY_OTHER, plain(p, "panel.failed")));
            return PanelOpenResult.NONE;
        }
        int flags = stack.isEmpty() ? 0 : requestId > 0 ? FLAG_PUSH : FLAG_REPLACE;
        // Ersetzt: das alte bleibt darunter, bis der Client es mit P_CLOSED(1) schließt. Verwirft der Client das neue
        // OPEN (P_CLOSED des neuen), ist das alte wieder oben und lebt weiter (Final Review Important 4).
        stack.addFirst(s);
        while (stack.size() > MAX_STACK) stack.pollLast();
        s.lastRender = now;
        s.openedAt = now;
        sendDocument(s, requestId, flags);
        events.fire(new VystormPanelOpenEvent(p, reg.id(), s.id));
        return PanelOpenResult.NATIVE;
    }

    /** Rendert und prüft mit derselben Klasse wie der Client; setzt Dokument, Modell und Handler der Sitzung. */
    void render(Session s) {
        PanelWriter.Written w = write(s);
        PanelDocument model = validate(w.document());
        s.doc = w.document();
        s.model = model;
        s.handlers = w.handlers();
    }

    /** Plugin-Render + Schreiber (Server-Thread: Plugin-Code, Texte in der Spielersprache). */
    private PanelWriter.Written write(Session s) {
        PanelContext ctx = new Ctx(s);
        Panel panel = s.reg.spec().render().apply(ctx);
        String web = webModule(s);
        return PanelWriter.write(s.reg.id(), panel, s.player, web, web == null ? null : webPage(s));
    }

    /**
     * Volle Prüfung wie beim Client (fängt Plugin-Fehler wie > 2000 Zeilen, ungültige Farben, Bereiche ab, die der
     * Schreiber nicht prüft) und Modell für {@link ActionCheck}. Reine Funktion: läuft für große Refreshs off-thread.
     */
    static PanelDocument validate(JsonObject doc) {
        PanelDocument model = PanelSchema.document(doc, Deadline.NONE);
        if (!model.problems().isEmpty()) throw new IllegalStateException("unsupported panel elements: " + model.problems());
        return model;
    }

    static String webModule(Session s) {
        String module = s.reg.spec().webModule();
        if (module == null) return null;
        try {
            return Vystorm.vystorm_core.web.WebServices.available() && Vystorm.vystorm_core.web.WebServices.registered(module) ? module : null;
        } catch (RuntimeException | LinkageError off) {
            return null;
        }
    }

    private static String webPage(Session s) {
        try {
            return Vystorm.vystorm_core.web.WebServices.overlayPage(s.reg.spec().webModule(), s.reg.spec().webSubPath());
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    private void sendDocument(Session s, int requestId, int flags) { sendDocument(s, requestId, flags, s.doc.toString()); }

    /** @param json {@code s.doc} schon serialisiert (einmal pro Render) */
    private void sendDocument(Session s, int requestId, int flags, String json) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        if (Transfer.inline(raw.length)) {
            channel.send(s.player, new PanelMessage.Open(s.id, requestId, flags, 0, json));
            return;
        }
        int transfer = nextTransfer++;
        s.packing = true;
        boolean refresh = (flags & FLAG_KEEP_STATE) != 0;
        Runnable pack = () -> {
            List<PanelMessage> parts = null;
            Throwable failed = null;
            try {
                parts = new ArrayList<>();
                parts.add(new PanelMessage.Open(s.id, requestId, flags, transfer, ""));
                parts.addAll(Transfer.split(transfer, TRANSFER_DOCUMENT, raw));
            } catch (Throwable failure) {   // auch Errors: packing muss zurück (Re-Review Minor 4)
                failed = failure;
            }
            List<PanelMessage> done = failed == null ? parts : null;
            mainThread.accept(() -> packed(s, done, refresh));
            if (failed instanceof Error error) throw error;
        };
        if (raw.length > 64 * 1024) offThread.accept(pack);   // große Dokumente außerhalb des Server-Threads packen
        else pack.run();
    }

    /**
     * Server-Thread: gepacktes Dokument senden – aber nur, wenn die Sitzung noch oben ist (Re-Review Important A). Ein
     * erstes OPEN, das ein späteres (kleines) OPEN überholt hätte, wird verworfen; ein Refresh wird nachgeholt, sobald die
     * Sitzung wieder sichtbar ist.
     */
    private void packed(Session s, List<PanelMessage> parts, boolean refresh) {
        s.packing = false;
        Deque<Session> stack = stacks.get(s.player.getUniqueId());
        if (stack == null || !stack.contains(s)) return;
        if (stack.peekFirst() != s) {
            if (refresh) {
                s.dirty = true;
                s.forceFull = true;
            } else {
                stack.remove(s);   // der Client hat es nie gesehen: ein späteres OPEN steht schon oben
                events.fire(new VystormPanelCloseEvent(s.player, s.reg.id(), s.id, CLOSED_REPLACED));
            }
            return;
        }
        if (parts == null || !channel.queue(s.player, parts)) resend(s);
    }

    /** Senden ging nicht (Warteschlange voll, Packen gescheitert): beim nächsten erlaubten Tick volles Dokument. */
    private static void resend(Session s) {
        s.forceFull = true;
        s.dirty = true;
    }

    /** Volles Dokument mit „Zustand behalten“; {@code rev} springt, damit ein verlorenes OPEN beim nächsten Patch als Lücke auffällt. */
    private void sendFull(Session s, String json) {
        s.rev++;
        sendDocument(s, 0, FLAG_REPLACE | FLAG_KEEP_STATE, json);
    }

    // ------------------------------------------------------------------ Kanal

    @Override public void request(Player p, PanelMessage.Request r) {
        String id = r.panelId().isEmpty() ? channel.hotkeyPanel() : r.panelId();
        PanelRegistry.Registered reg = PanelRegistry.find(id).filter(x -> x.owner().isEnabled()).orElse(null);
        if (reg == null) {
            channel.send(p, new PanelMessage.Denied(r.requestId(), Protocol.DENY_UNKNOWN_MODULE, plain(p, "panel.unknown")));
            return;
        }
        if (!allowed(reg, p)) {
            channel.send(p, new PanelMessage.Denied(r.requestId(), Protocol.DENY_NO_PERMISSION, plain(p, "panel.no-permission")));
            return;
        }
        openNative(p, reg, r.arg(), r.requestId());
    }

    @Override public void action(Player p, PanelMessage.Action a) {
        if (!playerBucket(p).tryAcquire()) {   // vor allem anderen: auch STALE-Antworten kosten
            if (rateReplies.computeIfAbsent(p.getUniqueId(), k -> new TokenBucket(2, 10, nanos)).tryAcquire())
                result(p, a.session(), a.seq(), Result.error(""), STATUS_RATE);
            return;
        }
        Deque<Session> stack = stacks.get(p.getUniqueId());
        Session s = stack == null ? null : stack.peekFirst();
        if (s == null || s.id != a.session()) {
            result(p, a.session(), a.seq(), Result.error(""), STATUS_STALE);
            return;
        }
        if (s.seen(a.seq())) return;   // doppelt: schon beantwortet
        Gate gate = gate(s, a.elementId(), a.kind(), a.value());
        if (!gate.passed()) {
            if (gate.status() == STATUS_STALE && !s.reg.owner().isEnabled()) closeSession(s, CLOSED_SERVER);
            result(p, a.session(), a.seq(), Result.error(gate.messageKey() == null ? "" : plain(p, gate.messageKey())), gate.status());
            return;
        }
        Act act = new Act(p, gate.value(), s, this, a.seq());
        Result r = run(s, a.elementId(), gate, act);
        if (r.isPending()) {
            // Ergebnis kommt per PanelAction.complete (jeder Thread) – oder kam schon während des Handlers.
            act.deferredUntil = nanos.getAsLong() + Result.PENDING_TIMEOUT_SECONDS * 1_000_000_000L;
            if (act.early != null) finish(act, act.early);
            else deferred.add(act);
        } else {
            act.done = true;
            result(p, s.id, a.seq(), r, r.success() ? STATUS_OK : STATUS_ERROR);
            if (act.refresh) s.dirty = true;
        }
        handOff(act);
    }

    /** Nachgereichtes Ergebnis ({@link PanelAction#complete}); Server-Thread. */
    void complete(Act act, Result r) {
        if (act.done || r == null || r.isPending()) return;
        if (act.deferredUntil == 0) {   // complete() noch während des Handlers
            if (act.early == null) act.early = r;
            return;
        }
        finish(act, r);
    }

    private void finish(Act act, Result r) {
        if (act.done) return;
        act.done = true;
        deferred.remove(act);
        result(act.player(), act.session.id, act.seq, r, r.success() ? STATUS_OK : STATUS_ERROR);
        if (act.refresh && isOpen(act.session)) act.session.dirty = true;
    }

    /**
     * {@link PanelAction#closeAndThen}: alle Panels des Spielers schließen (wartende Transfer-Teile verwerfen, P_CLOSE 0 sofort
     * senden), dann {@code next} zwei Ticks später – so hat der Client das Panel zu, bevor ein Dialog kommt.
     */
    private void handOff(Act act) {
        java.util.function.Consumer<Player> next = act.closeThen;
        if (next == null) return;
        Player p = act.player();
        closeAll(p, CLOSED_SERVER);
        mainThread.accept(() -> mainThread.accept(() -> {
            if (!p.isOnline()) return;
            try {
                next.accept(p);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "[Panels] closeAndThen of " + act.session.reg.id() + " failed", failure);
            }
        }));
    }

    /** Alle Sitzungen des Spielers schließen (Close-Events), dem Client ein einziges P_CLOSE 0 schicken. */
    void closeAll(Player p, int reason) {
        Deque<Session> stack = stacks.get(p.getUniqueId());
        if (stack == null || stack.isEmpty()) return;
        List<Session> closed = List.copyOf(stack);
        stack.clear();
        channel.dropQueued(p);
        channel.send(p, new PanelMessage.Close(0, reason));
        for (Session s : closed) events.fire(new VystormPanelCloseEvent(p, s.reg.id(), s.id, reason));
    }

    TokenBucket playerBucket(Player p) {
        return playerBuckets.computeIfAbsent(p.getUniqueId(),
                k -> new TokenBucket(channel.settings().actionsPerSecond(), channel.settings().actionBurst(), nanos));
    }

    /**
     * Gemeinsame Prüfkette für Panel und Dialog (nach dem Spieler-Ratenlimit): Element existiert im gesendeten Dokument
     * (erst dann ein Element-Bucket – erfundene IDs legen nichts an), Element-Ratenlimit, Besitzer aktiv, Recht,
     * {@link ActionCheck}, Handler vorhanden.
     */
    Gate gate(Session s, String elementId, int kind, String valueJson) {
        if (s.model == null || s.model.find(elementId) == null || PanelIds.auto(elementId)) return Gate.refuse(STATUS_STALE, null);
        if (s.elementBuckets.size() >= MAX_ELEMENT_BUCKETS && !s.elementBuckets.containsKey(elementId)) s.elementBuckets.clear();
        if (!s.elementBuckets.computeIfAbsent(elementId, k -> new TokenBucket(10, 10, nanos)).tryAcquire()) return Gate.refuse(STATUS_RATE, null);
        if (!s.reg.owner().isEnabled()) return Gate.refuse(STATUS_STALE, null);
        if (!allowed(s.reg, s.player)) return Gate.refuse(STATUS_DENIED, "panel.no-permission");
        Object value;
        try {
            value = ActionCheck.check(s.model, elementId, kind, valueJson);
        } catch (ActionCheck.Rejected rejected) {
            return Gate.refuse(rejected.status(), null);
        }
        PanelWriter.Handler handler = handler(s, elementId, kind);
        if (handler == null) return Gate.refuse(STATUS_DENIED, null);
        return new Gate(handler, value, STATUS_OK, null);
    }

    /** Handler ausführen; Ausnahmen werden geloggt und zu einer Fehlermeldung. */
    Result run(Session s, String elementId, Gate gate, PanelAction act) {
        Result r;
        try {
            r = gate.handler().handle(act, gate.value());
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "[Panels] Handler of " + s.reg.id() + "#" + elementId + " failed", failure);
            r = Result.error(plain(s.player, "panel.failed"));
        }
        return r == null ? Result.ok() : r;
    }

    private static PanelWriter.Handler handler(Session s, String id, int kind) {
        return switch (kind) {
            case KIND_PAGE -> s.handlers.get(id + "#page");
            case KIND_SORT -> s.handlers.get(id + "#sort");
            case KIND_SELECT -> s.handlers.getOrDefault(id + "#select", s.handlers.get(id));
            default -> s.handlers.get(id);
        };
    }

    @Override public void closed(Player p, PanelMessage.Closed c) {
        Deque<Session> stack = stacks.get(p.getUniqueId());
        if (stack == null) return;
        Session top = stack.peekFirst();
        for (Iterator<Session> it = stack.iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (s.id != c.session()) continue;
            it.remove();
            events.fire(new VystormPanelCloseEvent(p, s.reg.id(), s.id, c.reason()));
        }
        Session now = stack.peekFirst();
        if (now != null && now != top) now.dirty = true;   // wieder sichtbar → frische Daten (Diff)
    }

    @Override public void error(Player p, PanelMessage.Error e) {
        Deque<Session> stack = stacks.get(p.getUniqueId());
        Session s = null;
        if (stack != null) for (Session x : stack) if (x.id == e.session()) s = x;
        String detail = UiText.sanitize(e.detail());
        if (s != null && detail.startsWith("resync")) {
            // Client hat eine rev-Lücke oder einen kaputten Patch: volles Dokument, sobald die Sitzung oben ist (≤ 1/s).
            s.forceFull = true;
            s.dirty = true;
        }
        events.fire(new VystormPanelErrorEvent(p, s == null ? null : s.reg.owner(), s == null ? "" : s.reg.id(), e.elementId(), e.code(), detail));
        String key = (s == null ? "?" : s.reg.id()) + "#" + e.code();
        long now = nanos.getAsLong();
        Long last = errorLog.get(key);
        if (last != null && now - last < 60_000_000_000L) return;
        if (errorLog.size() > 256) errorLog.clear();
        errorLog.put(key, now);
        logger.info("[Panels] Client of " + p.getName() + " reported " + key + " at " + (e.elementId().isEmpty() ? "(document)" : e.elementId()) + ": " + detail);
    }

    // ------------------------------------------------------------------ Refresh, Schließen

    public void refresh(Player p, String panelId) {
        Deque<Session> stack = stacks.get(p.getUniqueId());
        if (stack != null) for (Session s : stack) if (s.reg.id().equals(panelId)) s.dirty = true;
    }

    public void refreshAll(String panelId) {
        for (Deque<Session> stack : stacks.values()) for (Session s : stack) if (s.reg.id().equals(panelId)) s.dirty = true;
    }

    public void close(Player p, String panelId) {
        Deque<Session> stack = stacks.get(p.getUniqueId());
        if (stack == null) return;
        for (Session s : List.copyOf(stack)) if (s.reg.id().equals(panelId)) closeSession(s, CLOSED_SERVER);
    }

    /** Einmal pro Tick: nur die sichtbare Sitzung je Spieler, höchstens {@code refreshPerSecond} Renders. */
    public void tick() {
        long now = nanos.getAsLong();
        if (!deferred.isEmpty()) {
            for (Act act : List.copyOf(deferred)) {
                if (now - act.deferredUntil < 0) continue;
                finish(act, Result.error(plain(act.player(), "panel.no-answer")));
            }
        }
        long minGap = 1_000_000_000L / channel.settings().refreshPerSecond();
        for (Deque<Session> stack : List.copyOf(stacks.values())) {
            Session s = stack.peekFirst();
            if (s == null || !s.dirty || s.packing || now - s.lastRender < minGap) continue;
            if (s.forceFull && now - s.lastFull < 1_000_000_000L) continue;   // erzwungene volle Renders ≤ 1/s je Sitzung
            s.dirty = false;
            s.lastRender = now;
            if (s.forceFull) s.lastFull = now;
            refreshSession(s);
        }
    }

    private record Prepared(PanelWriter.Written written, PanelDocument model, String json, JsonObject patch, String patchJson, List<PanelMessage> parts) {}

    private void refreshSession(Session s) {
        boolean full = s.forceFull;
        s.forceFull = false;
        PanelWriter.Written w;
        try {
            w = write(s);
        } catch (RuntimeException failure) {
            renderFailed(s.reg, failure);
            if (full) sendFull(s, s.doc.toString());   // Client braucht trotzdem eine Grundlage
            return;
        }
        JsonObject old = s.doc;
        int rev = s.rev + 1;
        int estimate = PanelDiff.estimate(w.document());
        boolean async = estimate >= ASYNC_REFRESH_BYTES;
        int transfer = async ? nextTransfer++ : 0;
        Runnable job = () -> {
            Prepared prepared = null;
            Throwable failed = null;
            try {
                prepared = prepare(s, w, old, rev, full, async, transfer);
            } catch (Throwable failure) {   // auch Errors: apply läuft immer, packing kommt zurück (Re-Review Minor 4)
                failed = failure;
            }
            Prepared done = prepared;
            RuntimeException error = failed == null ? null : failed instanceof RuntimeException r ? r : new IllegalStateException(failed);
            Runnable apply = () -> apply(s, done, error, full, rev);
            if (async) mainThread.accept(apply);
            else apply.run();
            if (failed instanceof Error e) throw e;
        };
        if (async) {
            s.packing = true;
            offThread.accept(job);
        } else {
            job.run();
        }
    }

    /** Prüfen, diffen, serialisieren, ggf. packen – reine Arbeit ohne Sitzungszustand (darf off-thread laufen). */
    private static Prepared prepare(Session s, PanelWriter.Written w, JsonObject old, int rev, boolean full, boolean pack, int transfer) {
        Runnable fault = testPrepareFault;
        if (fault != null) fault.run();
        PanelDocument model = validate(w.document());
        JsonObject patch = full ? null : PanelDiff.diff(old, w.document(), rev);
        if (patch != null && patch.getAsJsonArray("ops").isEmpty()) return new Prepared(w, model, null, patch, null, List.of());
        String patchJson = patch == null ? null : patch.toString();
        if (patchJson != null && patchJson.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) {   // Re-Review Minor 5
            patch = null;
            patchJson = null;
        }
        String json = patch == null ? w.document().toString() : null;
        List<PanelMessage> parts = List.of();
        if (pack) {
            byte[] raw = (patch == null ? json : patchJson).getBytes(StandardCharsets.UTF_8);
            if (!Transfer.inline(raw.length)) {
                parts = new ArrayList<>();
                // volles OPEN: rev springt (rev + 1 = schon reserviert, s.rev wird in apply passend gesetzt)
                parts.add(patch == null ? new PanelMessage.Open(s.id, 0, FLAG_REPLACE | FLAG_KEEP_STATE, transfer, "") : new PanelMessage.Patch(s.id, rev, transfer, ""));
                parts.addAll(Transfer.split(transfer, patch == null ? TRANSFER_DOCUMENT : TRANSFER_PATCH, raw));
            }
        }
        return new Prepared(w, model, json, patch, patchJson, parts);
    }

    /** Server-Thread: neues Dokument/Modell/Handler übernehmen und senden (Aktionen bis dahin prüfen gegen das alte). */
    private void apply(Session s, Prepared p, RuntimeException failure, boolean full, int rev) {
        s.packing = false;
        Deque<Session> stack = stacks.get(s.player.getUniqueId());
        if (stack == null || !stack.contains(s)) return;
        if (stack.peekFirst() != s) {
            // Nicht mehr sichtbar (Re-Review Important A): nichts senden, nichts übernehmen; nachholen, wenn sie wieder oben ist.
            s.dirty = true;
            if (full) s.forceFull = true;
            return;
        }
        if (failure != null) {
            renderFailed(s.reg, failure);
            if (full) sendFull(s, s.doc.toString());
            return;
        }
        s.doc = p.written().document();
        s.model = p.model();
        s.handlers = p.written().handlers();
        if (p.patch() == null) {
            if (!p.parts().isEmpty()) {
                s.rev = rev;
                if (!channel.queue(s.player, p.parts())) resend(s);
            } else {
                sendFull(s, p.json());
            }
        } else if (p.patchJson() != null) {
            s.rev = rev;
            if (!p.parts().isEmpty()) {
                if (!channel.queue(s.player, p.parts())) resend(s);
            } else {
                sendPatch(s, p.patchJson());
            }
        }
    }

    private void sendPatch(Session s, String json) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        if (Transfer.inline(raw.length)) {
            channel.send(s.player, new PanelMessage.Patch(s.id, s.rev, 0, json));
            return;
        }
        int transfer = nextTransfer++;
        List<PanelMessage> parts = new ArrayList<>();
        parts.add(new PanelMessage.Patch(s.id, s.rev, transfer, ""));
        parts.addAll(Transfer.split(transfer, TRANSFER_PATCH, raw));
        if (!channel.queue(s.player, parts)) resend(s);
    }

    void closeSession(Session s, int reason) {
        Deque<Session> stack = stacks.get(s.player.getUniqueId());
        if (stack == null) return;
        Session top = stack.peekFirst();
        if (!stack.remove(s)) return;
        channel.send(s.player, new PanelMessage.Close(s.id, reason));
        events.fire(new VystormPanelCloseEvent(s.player, s.reg.id(), s.id, reason));
        Session now = stack.peekFirst();
        if (now != null && s == top) now.dirty = true;   // wieder sichtbar → frische Daten
    }

    private boolean isOpen(Session s) {
        Deque<Session> stack = stacks.get(s.player.getUniqueId());
        return stack != null && stack.contains(s);
    }

    public void quit(UUID player) {
        stacks.remove(player);
        deferred.removeIf(a -> a.player().getUniqueId().equals(player));
        dialogs.quit(player);
        playerBuckets.remove(player);
        rateReplies.remove(player);
        nextSession.remove(player);
    }

    /** Plugin-Disable ({@code CoreLifecycle}): Sitzungen seiner Panels schließen, Panels entfernen. */
    public void release(Plugin owner) {
        for (Deque<Session> stack : stacks.values())
            for (Session s : List.copyOf(stack)) if (s.reg.owner() == owner) closeSession(s, CLOSED_SERVER);
        deferred.removeIf(a -> a.session.reg.owner() == owner);
        dialogs.release(owner);
        PanelRegistry.release(owner);
    }

    public void clear() {
        stacks.clear();
        deferred.clear();
        dialogs.clear();
        playerBuckets.clear();
        rateReplies.clear();
    }

    // ------------------------------------------------------------------ Dialog-Abstieg

    /** Für Tests und Start: Dialog-Schnittstelle und Core-Plugin setzen. */
    public void dialogs(Plugin core, java.util.function.Supplier<Vystorm.vystorm_core.compat.dialog.DialogInterface> api) { dialogs.configure(core, api); screens.configure(core); }

    PanelDialogs dialogs() { return dialogs; }

    /** Automatischer Paper-Dialog; false = nicht dialogfähig (Aufrufer nimmt den nächsten Fallback). */
    public boolean openDialog(Player p, PanelRegistry.Registered reg, String arg) { return dialogs.open(p, reg, arg); }

    /** Panel as a Vystorm screen (0.57.0); false when it cannot be shown as one (inputs, refused). */
    public boolean openScreen(Player p, PanelRegistry.Registered reg, String arg) { return screens.open(p, reg, arg); }

    // ------------------------------------------------------------------ Hilfen

    /**
     * 0.71.0 ({@code PickReturn.panel}): panel, argument and a copy of the session values of one of Core's own actions
     * (client mod, screen, dialog); empty for a {@link PanelAction} implemented outside Core.
     */
    public static java.util.Optional<PanelResume> resumeOf(PanelAction action) {
        if (!(action instanceof Act act) || act.session == null) return java.util.Optional.empty();
        return java.util.Optional.of(new PanelResume(act.session.reg, act.session.arg, new java.util.LinkedHashMap<>(act.session.state)));
    }

    boolean allowed(PanelRegistry.Registered reg, Player p) {
        try {
            return reg.spec().access().test(p);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "[Panels] Access check of " + reg.id() + " failed", failure);
            return false;
        }
    }

    private void result(Player p, int session, int seq, Result r, int status) {
        JsonObject o = new JsonObject();
        String message = r.message().isEmpty() ? "" : plainText(p, r.message());
        if (!message.isEmpty()) o.addProperty("message", message.length() > 400 ? message.substring(0, 400) : message);
        if (!r.fields().isEmpty()) {
            JsonObject f = new JsonObject();
            r.fields().forEach((k, v) -> f.addProperty(k, plainText(p, v)));
            o.add("fields", f);
        }
        String json = o.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_JSON_BYTES) json = "{}";
        channel.send(p, new PanelMessage.Result(Math.max(1, session), seq, status, json));
    }

    void renderFailed(PanelRegistry.Registered reg, RuntimeException failure) {
        logger.log(Level.WARNING, "[Panels] Rendering " + reg.id() + " of plugin " + reg.owner().getName() + " failed", failure);
    }

    /** Core-Text in der Spielersprache (einfach). */
    static String plain(Player p, String key) {
        try { return Localization.get().core().plain(p, key); } catch (RuntimeException | LinkageError failure) { return ""; }
    }

    /** Text oder {@code Lang.ref} → einfacher Text ohne Farbcodes. */
    static String plainText(Player p, String text) {
        String resolved = Localization.get().resolve(p, text);
        return UiText.sanitize(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(resolved)));
    }

    private static final class Ctx implements PanelContext {
        private final Session s;

        Ctx(Session s) { this.s = s; }

        @Override public Player player() { return s.player; }
        @Override public String arg() { return s.arg; }
        @Override public String text(String key, Object... placeholders) {
            return Localization.get().bundle(s.reg.owner()).map(l -> l.plain(s.player, key, placeholders)).orElse(key);
        }
        @Override public Component component(String key, Object... placeholders) {
            return Localization.get().bundle(s.reg.owner()).map(l -> l.component(s.player, key, placeholders)).orElse(Component.text(key));
        }
        @Override public PanelSession session() { return s; }
    }

    static final class Act implements PanelAction {
        private final Player player;
        private final Object value;
        private final Session session;
        private final PanelHub hub;
        private final int seq;
        volatile boolean refresh;
        java.util.function.Consumer<Player> closeThen;
        /** Server-Thread: Frist für {@link #complete} (0 = Handler läuft noch bzw. nicht verzögert), schon geantwortet, frühes Ergebnis. */
        long deferredUntil;
        boolean done;
        Result early;

        Act(Player player, Object value, Session session) { this(player, value, session, null, 0); }

        Act(Player player, Object value, Session session, PanelHub hub, int seq) {
            this.player = player;
            this.value = value;
            this.session = session;
            this.hub = hub;
            this.seq = seq;
        }

        @Override public Player player() { return player; }
        @Override public Object value() { return value; }
        @Override public PanelSession session() { return session; }
        @Override public void refresh() { refresh = true; }
        @Override public void closeAndThen(java.util.function.Consumer<Player> next) { closeThen = next; }

        @Override public void complete(Result result) {
            if (hub == null || result == null || result.isPending()) return;   // Dialog-Fallback: nichts nachzureichen
            hub.mainThread.accept(() -> hub.complete(this, result));
        }
    }
}
