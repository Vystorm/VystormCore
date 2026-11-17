package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.ProtocolException;
import Vystorm.vystorm_core.web.internal.clientproto.TokenBucket;
import Vystorm.vystorm_core.web.internal.clientproto.UiMessage;
import Vystorm.vystorm_core.web.internal.clientproto.ui.ElementIds;
import Vystorm.vystorm_core.web.internal.clientproto.ui.ToastSpec;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiElement;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiLimits;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Server-Seite von {@code vystorm:ui} (native Elemente des Vystorm-Client-Mods, Protokoll: {@code PROTOCOL.md} §4).
 * Hält pro Spieler mit Mod einen Spiegel der gesendeten Elemente (für PATCH-Prüfung, Grenzen und {@code has}), prüft
 * jedes Element mit demselben Schema wie der Client ({@link UiSchema}), bündelt PATCHes pro Element (höchstens einer je
 * {@code patchIntervalTicks}, spätere Felder überschreiben frühere) und sendet ratenbegrenzt aus einer Warteschlange
 * ({@link #flush()} einmal pro Tick). Spieler ohne Mod oder ohne passende Fähigkeit: nichts passiert.
 *
 * <p>IDs sind hier immer vollständig ({@code <namespace>:<name>}); die Zuordnung zu Plugins macht
 * {@link Vystorm.vystorm_core.web.ClientUi}. Nur Server-Thread. Core-intern.
 *
 * @since 0.20.0
 */
public final class ClientUiHub {
    static final int MAX_MALFORMED = 20;

    /** Ergebnis eines Aufrufs; {@code detail} nur bei {@link Status#INVALID}. */
    public record Result(Status status, String detail) {
        static final Result OK = new Result(Status.OK, "");
        static final Result UNCHANGED = new Result(Status.UNCHANGED, "");
        public boolean ok() { return status == Status.OK || status == Status.UNCHANGED; }
        static Result of(Status status) { return new Result(status, ""); }
    }

    public enum Status { OK, UNCHANGED, NO_CLIENT, NO_CAPABILITY, INVALID, UNKNOWN_ID, LIMIT, TOO_LARGE, QUEUE_FULL, DISABLED }

    /** {@code web.client.ui}. */
    public record Settings(boolean enabled, int patchIntervalTicks, double messagesPerSecond, int burst, int perTick, int queueLimit) {
        public Settings {
            patchIntervalTicks = Math.max(1, Math.min(40, patchIntervalTicks));
            messagesPerSecond = Math.max(1, Math.min(40, messagesPerSecond));
            burst = Math.max(1, Math.min(120, burst));
            perTick = Math.max(1, Math.min(64, perTick));
            queueLimit = Math.max(16, Math.min(512, queueLimit));
        }

        public static Settings defaults() { return new Settings(true, 5, 30, 100, 32, 384); }

        public static Settings from(ConfigurationSection s) {
            Settings d = defaults();
            if (s == null) return d;
            return new Settings(s.getBoolean("enabled", d.enabled()), s.getInt("patch-interval-ticks", d.patchIntervalTicks()),
                    s.getDouble("messages-per-second", d.messagesPerSecond()), s.getInt("burst", d.burst()),
                    s.getInt("per-tick", d.perTick()), s.getInt("queue-limit", d.queueLimit()));
        }
    }

    /** Senden auf {@code vystorm:ui}. */
    public interface Sender { void send(Player player, byte[] data); }

    /** Vom Client abgelehntes Element (REJECT), bereits ratenbegrenzt. */
    public interface RejectListener { void rejected(Player player, String elementId, int code, String detail); }

    private enum Kind { HUD, MARKER, TOOLTIP }

    private record Mirror(JsonObject json, Kind kind, long expiresAt) {}

    private final class Ui {
        final Player player;
        final int capabilities;
        final Map<String, Mirror> elements = new HashMap<>();
        final Map<String, JsonObject> pending = new LinkedHashMap<>();
        final Map<String, Long> lastPatch = new HashMap<>();
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        final TokenBucket out;
        final TokenBucket rejects;
        final TokenBucket actions;
        /** Offene Toast-Aktionen (ID → Handler, Frist), älteste zuerst; höchstens {@link #MAX_ACTIONS}. */
        final Map<String, PendingAction> pendingActions = new LinkedHashMap<>();
        int malformed;

        Ui(Player player, int capabilities) {
            this.player = player;
            this.capabilities = capabilities;
            out = new TokenBucket(settings.messagesPerSecond(), settings.burst(), nanos);
            rejects = new TokenBucket(5, 10, nanos);
            actions = new TokenBucket(2, 4, nanos);
        }

        boolean extensions() { return (capabilities & Protocol.CAP_UI_EXTENSIONS) != 0; }
    }

    /** Toast-Aktion: Handler (Server-Thread, höchstens einmal) und Frist in {@code nanos}. */
    private record PendingAction(java.util.function.Consumer<Player> handler, long expiresAt) {}

    /** Offene Toast-Aktionen je Spieler; ältere fallen heraus. */
    static final int MAX_ACTIONS = 16;
    private long actionCounter;

    private final Settings settings;
    private final Sender sender;
    private final RejectListener rejects;
    private final LongSupplier nanos;
    private final Map<UUID, Ui> players = new ConcurrentHashMap<>();
    private final Set<Ui> dirty = new LinkedHashSet<>();
    private long tick;

    public ClientUiHub(Settings settings, Sender sender, RejectListener rejects, LongSupplier nanos) {
        this.settings = settings;
        this.sender = sender;
        this.rejects = rejects;
        this.nanos = nanos;
    }

    public Settings settings() { return settings; }

    // ------------------------------------------------------------------ Verbindung

    /** Nach angenommenem HELLO: Spieler kann native Elemente empfangen (falls {@code capabilities} passt). */
    public void connected(Player player, int capabilities) {
        if (player == null || !settings.enabled()) return;
        Ui old = players.put(player.getUniqueId(), new Ui(player, capabilities));
        if (old != null) dirty.remove(old);
    }

    public void disconnected(UUID player) {
        Ui old = players.remove(player);
        if (old != null) dirty.remove(old);
    }

    public void clear() {
        players.clear();
        dirty.clear();
    }

    /** Ob der Spieler native Elemente mit dieser Fähigkeit ({@code Protocol.CAP_*}) annimmt. */
    public boolean supports(UUID player, int capability) {
        Ui ui = players.get(player);
        return ui != null && (ui.capabilities & capability) == capability;
    }

    /** Ob ein Element mit dieser (vollständigen) ID gesendet wurde und nicht abgelaufen/entfernt ist. */
    public boolean has(UUID player, String id) {
        Ui ui = players.get(player);
        return ui != null && live(ui, id) != null;
    }

    // ------------------------------------------------------------------ Elemente

    /** Element anlegen oder ersetzen. {@code element} enthält die vollständige ID. */
    public Result put(Player player, JsonObject element) {
        Ui ui = ui(player);
        if (ui == null) return Result.of(settings.enabled() ? Status.NO_CLIENT : Status.DISABLED);
        UiElement parsed;
        String json;
        try {
            json = element.toString();
            parsed = UiSchema.element(UiSchema.parseObject(json, Protocol.MAX_ELEMENT_JSON_BYTES));
        } catch (UiSchema.SchemaException invalid) {
            return new Result(Status.INVALID, invalid.getMessage());
        }
        Kind kind = kind(parsed);
        if (!capable(ui, kind)) return Result.of(Status.NO_CAPABILITY);
        if (!ui.extensions()) {
            // Älterer Client: Typen/Felder, die er falsch zeigen würde → false; rein verbessernde Felder weglassen.
            if (UiSchema.needsExtensions(parsed)) return Result.of(Status.NO_CAPABILITY);
            JsonObject stripped = element.deepCopy();
            if (strip(stripped, parsed.type())) {
                element = stripped;
                json = element.toString();
            }
        }
        String id = parsed.id();
        Mirror existing = live(ui, id);
        if (existing == null && count(ui, kind) >= limit(kind)) return Result.of(Status.LIMIT);
        // Gleiches Element noch einmal: nichts senden (mit ttl zählt es als Verlängerung).
        if (existing != null && parsed.ttlMs() == 0 && existing.json().equals(element) && !ui.pending.containsKey(id)) return Result.UNCHANGED;
        byte[] data;
        try {
            data = UiMessage.encode(new UiMessage.Put(json));
        } catch (ProtocolException tooLarge) {
            return Result.of(Status.TOO_LARGE);
        }
        if (ui.queue.size() >= settings.queueLimit()) return Result.of(Status.QUEUE_FULL);
        ui.queue.addLast(data);
        ui.pending.remove(id);
        ui.elements.put(id, new Mirror(element.deepCopy(), kind, expiry(parsed.ttlMs())));
        dirty.add(ui);
        return Result.OK;
    }

    /**
     * Oberste Felder eines vorhandenen Elements ersetzen (flaches Merge wie beim Client). Wird gebündelt: pro Element
     * höchstens ein PATCH je {@code patchIntervalTicks}; Felder ohne Änderung werden nicht gesendet.
     */
    public Result patch(Player player, String id, JsonObject fields) {
        Ui ui = ui(player);
        if (ui == null) return Result.of(settings.enabled() ? Status.NO_CLIENT : Status.DISABLED);
        Mirror existing = live(ui, id);
        if (existing == null) return Result.of(Status.UNKNOWN_ID);
        if (!ui.extensions()) {
            JsonElement type = existing.json().get("type");
            fields = fields.deepCopy();
            strip(fields, type == null ? "" : type.getAsString());
        }
        JsonObject changed = new JsonObject();
        for (Map.Entry<String, JsonElement> e : fields.entrySet()) {
            if (e.getKey().equals("id")) continue;
            if (!e.getValue().equals(existing.json().get(e.getKey()))) changed.add(e.getKey(), e.getValue());
        }
        if (changed.isEmpty()) return Result.UNCHANGED;
        JsonObject merged;
        UiElement parsed;
        try {
            merged = UiSchema.merge(existing.json(), changed);
            // Wie der Client (0.3.2): das zusammengeführte Element darf nicht größer werden als ein PUT (hier in Bytes, also
            // mindestens so streng wie die Zeichenzählung des Clients).
            if (merged.toString().getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_ELEMENT_JSON_BYTES)
                return Result.of(Status.TOO_LARGE);
            parsed = UiSchema.element(merged);
        } catch (UiSchema.SchemaException invalid) {
            return new Result(Status.INVALID, invalid.getMessage());
        }
        if (!ui.extensions() && UiSchema.needsExtensions(parsed)) return Result.of(Status.NO_CAPABILITY);
        ui.elements.put(id, new Mirror(merged, existing.kind(), expiry(parsed.ttlMs())));
        JsonObject pending = ui.pending.computeIfAbsent(id, key -> {
            JsonObject p = new JsonObject();
            p.addProperty("id", key);
            return p;
        });
        for (Map.Entry<String, JsonElement> e : changed.entrySet()) pending.add(e.getKey(), e.getValue().deepCopy());
        dirty.add(ui);
        return Result.OK;
    }

    /** Elemente entfernen; unbekannte IDs werden übergangen. */
    public Result remove(Player player, List<String> ids) {
        Ui ui = ui(player);
        if (ui == null) return Result.of(settings.enabled() ? Status.NO_CLIENT : Status.DISABLED);
        List<String> known = new ArrayList<>();
        for (String id : ids) {
            if (id == null || !ElementIds.valid(id)) continue;
            ui.pending.remove(id);
            ui.lastPatch.remove(id);
            if (ui.elements.remove(id) != null) known.add(id);
        }
        if (known.isEmpty()) return Result.UNCHANGED;
        for (int i = 0; i < known.size(); i += Protocol.MAX_REMOVE_IDS) {
            if (ui.queue.size() >= settings.queueLimit()) return Result.of(Status.QUEUE_FULL);
            ui.queue.addLast(UiMessage.encode(new UiMessage.Remove(known.subList(i, Math.min(known.size(), i + Protocol.MAX_REMOVE_IDS)))));
        }
        dirty.add(ui);
        return Result.OK;
    }

    /** Alle Elemente mit diesem Präfix entfernen (Präfix nie leer). */
    public Result clear(Player player, String prefix) {
        Ui ui = ui(player);
        if (ui == null) return Result.of(settings.enabled() ? Status.NO_CLIENT : Status.DISABLED);
        return clear(ui, prefix, true);
    }

    private Result clear(Ui ui, String prefix, boolean always) {
        if (prefix == null || !ElementIds.validPrefix(prefix)) return new Result(Status.INVALID, "prefix invalid");
        boolean had = ui.elements.keySet().removeIf(id -> id.startsWith(prefix));
        ui.pending.keySet().removeIf(id -> id.startsWith(prefix));
        ui.lastPatch.keySet().removeIf(id -> id.startsWith(prefix));
        if (!had && !always) return Result.UNCHANGED;
        if (ui.queue.size() >= settings.queueLimit()) return Result.of(Status.QUEUE_FULL);
        ui.queue.addLast(UiMessage.encode(new UiMessage.Clear(prefix)));
        dirty.add(ui);
        return Result.OK;
    }

    /** Einmalige Meldung. */
    public Result toast(Player player, JsonObject toast) {
        Ui ui = ui(player);
        if (ui == null) return Result.of(settings.enabled() ? Status.NO_CLIENT : Status.DISABLED);
        if ((ui.capabilities & Protocol.CAP_TOASTS) == 0) return Result.of(Status.NO_CAPABILITY);
        String json = toast.toString();
        try {
            ToastSpec spec = UiSchema.toast(UiSchema.parseObject(json, Protocol.MAX_TOAST_JSON_BYTES));
            if (!ui.extensions() && UiSchema.needsExtensions(spec)) return Result.of(Status.NO_CAPABILITY);
        } catch (UiSchema.SchemaException invalid) {
            return new Result(Status.INVALID, invalid.getMessage());
        }
        byte[] data;
        try {
            data = UiMessage.encode(new UiMessage.Toast(json));
        } catch (ProtocolException tooLarge) {
            return Result.of(Status.TOO_LARGE);
        }
        if (ui.queue.size() >= settings.queueLimit()) return Result.of(Status.QUEUE_FULL);
        ui.queue.addLast(data);
        dirty.add(ui);
        return Result.OK;
    }

    /**
     * Toast mit Aktion (Client ≥ 0.9.0): vergibt eine Aktions-ID {@code <prefix>toast/<n>}, merkt sich {@code handler} bis
     * {@link ToastSpec#serverActionWindowMs} nach dem Senden und schickt den Toast. Der Handler läuft höchstens einmal,
     * nur auf ein UI_ACTION dieses Spielers mit genau dieser ID innerhalb der Frist. {@code toast} ohne {@code action.id}
     * (die setzt der Hub); {@code prefix} = Namensraum des Plugins mit {@code :}.
     */
    public Result toast(Player player, JsonObject toast, String prefix, java.util.function.Consumer<Player> handler) {
        Ui ui = ui(player);
        if (ui == null) return Result.of(settings.enabled() ? Status.NO_CLIENT : Status.DISABLED);
        if ((ui.capabilities & Protocol.CAP_TOASTS) == 0 || !ui.extensions()) return Result.of(Status.NO_CAPABILITY);
        if (handler == null || prefix == null || !ElementIds.validPrefix(prefix)) return new Result(Status.INVALID, "action handler or prefix missing");
        String actionId = prefix + "toast/" + Long.toString(++actionCounter, 36);
        if (!ElementIds.valid(actionId)) return new Result(Status.INVALID, "action id too long");
        JsonObject copy = toast.deepCopy();
        JsonObject action = copy.has("action") && copy.get("action").isJsonObject() ? copy.getAsJsonObject("action") : new JsonObject();
        action.addProperty("id", actionId);
        copy.add("action", action);
        int duration;
        try {
            duration = UiSchema.toast(UiSchema.parseObject(copy.toString(), Protocol.MAX_TOAST_JSON_BYTES)).durationMs();
        } catch (UiSchema.SchemaException invalid) {
            return new Result(Status.INVALID, invalid.getMessage());
        }
        Result r = toast(player, copy);
        if (!r.ok()) return r;
        if (ui.pendingActions.size() >= MAX_ACTIONS) ui.pendingActions.remove(ui.pendingActions.keySet().iterator().next());
        long expires = nanos.getAsLong() + ToastSpec.serverActionWindowMs(duration) * 1_000_000L;
        ui.pendingActions.put(actionId, new PendingAction(handler, expires));
        return r;
    }

    /** Anzahl offener Toast-Aktionen (Tests, Diagnose). */
    public int pendingActions(UUID player) {
        Ui ui = players.get(player);
        return ui == null ? 0 : ui.pendingActions.size();
    }

    /** Entfernt bei allen Spielern die Elemente mit diesem Präfix (Plugin deaktiviert). Sendet nur, wo etwas da war. */
    public void releasePrefix(String prefix) {
        for (Ui ui : players.values()) {
            clear(ui, prefix, false);
            ui.pendingActions.keySet().removeIf(id -> id.startsWith(prefix));
        }
    }

    // ------------------------------------------------------------------ Tick

    /** Einmal pro Tick: Warteschlangen und fällige PATCHes ratenbegrenzt senden. */
    public void flush() {
        tick++;
        if (dirty.isEmpty()) return;
        for (Iterator<Ui> it = dirty.iterator(); it.hasNext(); ) {
            Ui ui = it.next();
            if (players.get(ui.player.getUniqueId()) != ui) { it.remove(); continue; }
            int budget = settings.perTick();
            while (budget > 0 && !ui.queue.isEmpty()) {
                if (!ui.out.tryAcquire()) { budget = 0; break; }
                send(ui, ui.queue.pollFirst());
                budget--;
            }
            if (ui.queue.isEmpty()) {
                for (Iterator<Map.Entry<String, JsonObject>> p = ui.pending.entrySet().iterator(); p.hasNext() && budget > 0; ) {
                    Map.Entry<String, JsonObject> entry = p.next();
                    Long last = ui.lastPatch.get(entry.getKey());
                    if (last != null && tick - last < settings.patchIntervalTicks()) continue;
                    if (!ui.out.tryAcquire()) { budget = 0; break; }
                    p.remove();
                    ui.lastPatch.put(entry.getKey(), tick);
                    send(ui, patchMessage(ui, entry.getKey(), entry.getValue()));
                    budget--;
                }
            }
            if (ui.queue.isEmpty() && ui.pending.isEmpty()) it.remove();
        }
    }

    private static byte[] patchMessage(Ui ui, String id, JsonObject fields) {
        String json = fields.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length <= Protocol.MAX_PATCH_JSON_BYTES)
            return UiMessage.encode(new UiMessage.Patch(json));
        // Zu großer PATCH: das ganze (bereits geprüfte) Element neu senden.
        Mirror full = ui.elements.get(id);
        return full == null ? null : UiMessage.encode(new UiMessage.Put(full.json().toString()));
    }

    private void send(Ui ui, byte[] data) {
        if (data == null) return;
        try {
            sender.send(ui.player, data);
        } catch (RuntimeException gone) {
            // Spieler gerade weg oder Kanal zu: verwerfen, der Quit räumt auf.
        }
    }

    // ------------------------------------------------------------------ Client → Server

    /** Nachricht auf {@code vystorm:ui} vom Client (nur REJECT). */
    public void onMessage(Player player, byte[] data) {
        if (player == null) return;
        Ui ui = players.get(player.getUniqueId());
        if (ui == null || ui.malformed > MAX_MALFORMED) return;
        UiMessage message;
        try {
            message = UiMessage.decodeServerbound(data);
        } catch (ProtocolException bad) {
            ui.malformed++;
            return;
        }
        if (message instanceof UiMessage.Action action) {
            onAction(ui, action.actionId());
            return;
        }
        if (!(message instanceof UiMessage.Reject reject) || !ui.rejects.tryAcquire()) return;
        String id = reject.elementId();
        if (ElementIds.valid(id) && (reject.code() == Protocol.REJECT_LIMIT || reject.code() == Protocol.REJECT_UNKNOWN_TYPE
                || reject.code() == Protocol.REJECT_UNKNOWN_ID)) {
            // Der Client hat das Element nicht (mehr): Spiegel angleichen, damit das Plugin neu anlegen kann.
            ui.elements.remove(id);
            ui.pending.remove(id);
        }
        if (rejects != null) rejects.rejected(player, ElementIds.valid(id) ? id : "", reject.code(), reject.detail());
    }

    /**
     * UI_ACTION: nur eine eigene, offene, nicht abgelaufene Aktions-ID dieses Spielers führt den Handler aus – einmal
     * (vorher entfernt), ratenbegrenzt (2/s, Burst 4). Unbekannte/abgelaufene IDs werden still ignoriert. Der Handler muss
     * alles selbst erneut prüfen (Zeit ist vergangen; der Client ist nie Autorität).
     */
    private void onAction(Ui ui, String actionId) {
        if (!ui.actions.tryAcquire()) return;
        PendingAction pending = ui.pendingActions.remove(actionId);
        if (pending == null) return;
        if (nanos.getAsLong() - pending.expiresAt() >= 0) return;
        try {
            pending.handler().accept(ui.player);
        } catch (RuntimeException failure) {
            // ClientUi kapselt Handler und loggt beim Plugin; hier nur das Sicherheitsnetz.
        }
    }

    /** Rein verbessernde Felder von Client 0.9.0 entfernen (ältere Clients). @return ob etwas entfernt wurde */
    private static boolean strip(JsonObject element, String type) {
        boolean changed = false;
        for (String field : UiSchema.optionalExtensionFields(type)) changed |= element.remove(field) != null;
        return changed;
    }

    // ------------------------------------------------------------------ Hilfen

    private Ui ui(Player player) {
        if (player == null || !settings.enabled()) return null;
        return players.get(player.getUniqueId());
    }

    private Mirror live(Ui ui, String id) {
        Mirror m = ui.elements.get(id);
        if (m != null && m.expiresAt() != 0 && nanos.getAsLong() - m.expiresAt() >= 0) {
            ui.elements.remove(id);
            ui.pending.remove(id);
            return null;
        }
        return m;
    }

    private long expiry(long ttlMs) {
        if (ttlMs <= 0) return 0;
        long at = nanos.getAsLong() + ttlMs * 1_000_000L;
        return at == 0 ? 1 : at;
    }

    private int count(Ui ui, Kind kind) {
        int n = 0;
        long now = nanos.getAsLong();
        for (Iterator<Mirror> it = ui.elements.values().iterator(); it.hasNext(); ) {
            Mirror m = it.next();
            if (m.expiresAt() != 0 && now - m.expiresAt() >= 0) { it.remove(); continue; }
            if (m.kind() == kind) n++;
        }
        return n;
    }

    private static Kind kind(UiElement element) {
        return switch (element) {
            case UiElement.Hud ignored -> Kind.HUD;
            case UiElement.Marker ignored -> Kind.MARKER;
            case UiElement.Tooltip ignored -> Kind.TOOLTIP;
        };
    }

    private static boolean capable(Ui ui, Kind kind) {
        int cap = switch (kind) {
            case HUD -> Protocol.CAP_HUD;
            case MARKER -> Protocol.CAP_MARKERS;
            case TOOLTIP -> Protocol.CAP_TOOLTIPS;
        };
        return (ui.capabilities & cap) != 0;
    }

    private static int limit(Kind kind) {
        return switch (kind) {
            case HUD -> UiLimits.MAX_HUD_ELEMENTS;
            case MARKER -> UiLimits.MAX_MARKERS;
            case TOOLTIP -> UiLimits.MAX_TOOLTIP_RULES;
        };
    }
}
