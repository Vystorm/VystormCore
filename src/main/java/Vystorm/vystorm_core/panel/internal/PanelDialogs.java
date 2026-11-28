package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.compat.dialog.DialogInterface;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import Vystorm.vystorm_core.compat.dialog.DialogResponseView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Automatischer Paper-Dialog für Spieler ohne Panels (Spec §7): rendert das Panel, plant den Dialog
 * ({@link DialogDowngrade}) und führt Knöpfe/Eingaben durch **dieselbe** Prüfkette wie native Aktionen
 * ({@link PanelHub#gate}): Spieler-Ratenlimit, Element, Element-Ratenlimit, Besitzer, Recht, {@code ActionCheck}, Handler.
 * Knöpfe mit {@code confirm} fragen erst in einem Bestätigungsdialog; lazy Reiter rufen {@code onTab}; die gewählten
 * Reiter merkt sich die Sitzung je Reiter-Block. Nur Server-Thread. Core-intern. @since 0.25.1
 */
final class PanelDialogs {
    private static final String TABS = "dialog.tabs";

    private final PanelHub hub;
    private final Map<UUID, PanelHub.Session> current = new HashMap<>();
    private Supplier<DialogInterface> api = () -> Vystorm.vystorm_core.main.getApi().dialogs();
    private Plugin owner;

    PanelDialogs(PanelHub hub) { this.hub = hub; }

    void configure(Plugin core, Supplier<DialogInterface> api) {
        this.owner = core;
        this.api = api;
    }

    /** false = nicht dialogfähig (Aufrufer nimmt den nächsten Fallback). */
    boolean open(Player p, PanelRegistry.Registered reg, String arg) {
        handedOff.remove(p.getUniqueId());
        PanelHub.Session s = new PanelHub.Session(0, p, reg, arg);
        PanelOpener.Seed seed = PanelOpener.seed(s);   // 0.71.0: values and status carried back by PickReturn
        boolean shown = show(s, 0, seed == null ? List.of() : seed.status);
        if (shown && seed != null) seed.statusShown = true;
        return shown;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> tabs(PanelHub.Session s) {
        Object v = s.state.get(TABS);
        if (v instanceof Map<?, ?> m) return (Map<String, String>) m;
        Map<String, String> created = new HashMap<>();
        s.state.put(TABS, created);
        return created;
    }

    private boolean show(PanelHub.Session s, int page, List<String> status) {
        if (handedOff.remove(s.player.getUniqueId())) return true;   // closeAndThen hat schon etwas anderes geöffnet
        try {
            hub.render(s);
        } catch (RuntimeException failure) {
            hub.renderFailed(s.reg, failure);
            return false;
        }
        DialogDowngrade.Plan plan = DialogDowngrade.plan(s.model, tabs(s), page, PanelHub.webModule(s) != null);
        if (!plan.capable()) return false;
        List<DialogDowngrade.Body> body = new ArrayList<>();
        status.forEach(line -> body.add(new DialogDowngrade.TextBody(UiText.plain(line))));
        body.addAll(plan.body());
        DialogDowngrade.Plan shown = new DialogDowngrade.Plan(true, null, body, plan.inputs(), plan.buttons(), plan.page(), plan.pages());
        current.put(s.player.getUniqueId(), s);
        api.get().open(owner, s.player, DialogDowngrade.request(shown, s.player, s.model.root().title(), new DialogDowngrade.Callbacks() {
            @Override public void apply(Player p, DialogResponseView r, DialogDowngrade.Plan pl) { applyInputs(s, r, pl); }
            @Override public void button(Player p, DialogResponseView r, DialogDowngrade.Plan pl, DialogDowngrade.Button b) { pressed(s, r, pl, b); }
        }));
        return true;
    }

    /** „Übernehmen“: geänderte Eingaben außerhalb von Formularen als CHANGE durch dieselbe Prüfung und dieselben Handler. */
    private void applyInputs(PanelHub.Session s, DialogResponseView r, DialogDowngrade.Plan plan) {
        List<String> status = new ArrayList<>();
        for (int k = 0; k < plan.inputs().size(); k++) {
            DialogDowngrade.Input in = plan.inputs().get(k);
            if (in.formId() != null) continue;
            JsonElement value = read(in, r, "i" + k);
            if (value == null || unchanged(in, value)) continue;
            addStatus(s, status, action(s, in.elementId(), KIND_CHANGE, value.toString()));
        }
        show(s, 0, status);
    }

    private void pressed(PanelHub.Session s, DialogResponseView r, DialogDowngrade.Plan plan, DialogDowngrade.Button b) {
        List<String> status = new ArrayList<>();
        switch (b.kind()) {
            case CLICK -> {
                if (b.confirm() != null) {   // erst bestätigen (Final Review Important 8)
                    api.get().open(owner, s.player, DialogDowngrade.confirmation(b.confirm(), s.player, () -> {
                        List<String> after = new ArrayList<>();
                        addStatus(s, after, action(s, b.elementId(), KIND_CLICK, ""));
                        show(s, plan.page(), after);
                    }, () -> show(s, plan.page(), List.of())));
                    return;
                }
                addStatus(s, status, action(s, b.elementId(), KIND_CLICK, ""));
            }
            case SUBMIT -> {   // alle Eingaben dieses Formulars zusammen (≤ 3 KiB, Spec §3.2)
                JsonObject values = new JsonObject();
                for (int k = 0; k < plan.inputs().size(); k++) {
                    DialogDowngrade.Input in = plan.inputs().get(k);
                    JsonElement value = b.elementId().equals(in.formId()) ? read(in, r, "i" + k) : null;
                    if (value != null) values.add(in.elementId(), value);
                }
                addStatus(s, status, action(s, b.elementId(), KIND_SUBMIT, values.toString()));
            }
            case NAVIGATE -> {
                PanelRegistry.find(b.target()).ifPresent(reg -> PanelOpener.open(s.player, reg, b.arg()));
                return;
            }
            case WEB -> {
                String module = b.target().isEmpty() ? s.reg.spec().webModule() : b.target();
                if (module != null) Vystorm.vystorm_core.web.WebServices.openOrLink(s.player, module, b.target().isEmpty() ? s.reg.spec().webSubPath() : "", null);
                return;
            }
            case TAB -> {
                tabs(s).put(b.elementId(), b.target());
                if (b.lazy()) addStatusOnError(s, status, action(s, b.elementId(), KIND_TAB, new JsonPrimitive(b.target()).toString()));
            }
            case PAGE -> {
                show(s, Integer.parseInt(b.target()), status);
                return;
            }
            case APPLY -> { }
        }
        show(s, 0, status);
    }

    /**
     * Wie eine native P_ACTION, ohne Kanal: Spieler-Ratenlimit, dann {@link PanelHub#gate} (Element, Element-Ratenlimit,
     * Besitzer, Recht, Prüfung, Handler). Ratenlimit → „zu schnell“, nicht „kein Recht“.
     */
    Result action(PanelHub.Session s, String id, int kind, String valueJson) {
        if (!hub.playerBucket(s.player).tryAcquire()) return Result.error(PanelHub.plain(s.player, "panel.dialog.slow-down"));
        PanelHub.Gate gate = hub.gate(s, id, kind, valueJson);
        if (!gate.passed()) {
            return Result.error(PanelHub.plain(s.player, switch (gate.status()) {
                case STATUS_RATE -> "panel.dialog.slow-down";
                case STATUS_DENIED -> gate.messageKey() != null ? gate.messageKey() : "panel.dialog.rejected";
                default -> "panel.dialog.rejected";
            }));
        }
        PanelHub.Act act = new PanelHub.Act(s.player, gate.value(), s);
        Result r = hub.run(s, id, gate, act);
        if (act.closeThen != null) {
            // closeAndThen im Dialog-Fallback: Dialog nicht neu zeigen, sondern übergeben.
            current.remove(s.player.getUniqueId());
            handedOff.add(s.player.getUniqueId());
            java.util.function.Consumer<Player> next = act.closeThen;
            Player p = s.player;
            try {
                next.accept(p);
            } catch (RuntimeException failure) {
                hub.logger().log(java.util.logging.Level.WARNING, "[Panels] closeAndThen of " + s.reg.id() + " failed", failure);
            }
        }
        return r.isPending() ? Result.ok() : r;
    }

    /** Spieler, deren letzte Dialog-Aktion per {@code closeAndThen} übergeben hat: {@link #show} zeigt den Dialog nicht neu. */
    private final java.util.Set<UUID> handedOff = new java.util.HashSet<>();

    /** True once after an action handed the player over (closeAndThen); used by {@link PanelScreens}. */
    boolean takeHandoff(UUID player) { return handedOff.remove(player); }

    /** Nur Test: Aktion in der aktuellen Dialog-Sitzung des Spielers. */
    Result action(Player p, String id, int kind, String valueJson) {
        PanelHub.Session s = current.get(p.getUniqueId());
        if (s == null) throw new IllegalStateException("no dialog session");
        return action(s, id, kind, valueJson);
    }

    void quit(UUID player) {
        current.remove(player);
        handedOff.remove(player);
    }

    /** Plugin deaktiviert: seine Dialog-Sitzungen nicht festhalten (Klassen des Plugins freigeben). */
    void release(Plugin plugin) { current.values().removeIf(s -> s.reg.owner() == plugin); }

    void clear() { current.clear(); }

    private static JsonElement read(DialogDowngrade.Input in, DialogResponseView r, String key) {
        return switch (in.kind()) {
            case BOOL -> r.getBoolean(key) == null ? null : new JsonPrimitive(r.getBoolean(key));
            case RANGE -> r.getFloat(key) == null || !Float.isFinite(r.getFloat(key)) ? null : new JsonPrimitive(snap(in, r.getFloat(key)));
            case OPTIONS, TEXT -> r.getText(key) == null ? null : new JsonPrimitive(r.getText(key));
        };
    }

    private static boolean unchanged(DialogDowngrade.Input in, JsonElement v) {
        return switch (in.kind()) {
            case BOOL -> Boolean.valueOf(v.getAsBoolean()).equals(in.initial());
            case RANGE -> in.initial() instanceof Double d && Math.abs(v.getAsDouble() - d) < 1e-9;
            case OPTIONS, TEXT -> v.getAsString().equals(in.initial());
        };
    }

    /** Meldung eines Handlers als Statuszeile oben im neu geöffneten Dialog (Paper-Dialoge haben kein Live-Update). */
    static void addStatus(PanelHub.Session s, List<String> status, Result r) {
        if (!r.message().isEmpty()) status.add((r.success() ? "✔ " : "✖ ") + PanelHub.plainText(s.player, r.message()));
        r.fields().values().forEach(v -> status.add("✖ " + PanelHub.plainText(s.player, v)));
    }

    static void addStatusOnError(PanelHub.Session s, List<String> status, Result r) {
        if (!r.success()) addStatus(s, status, r);
    }

    private static double snap(DialogDowngrade.Input in, float v) {
        double s = in.min() + Math.rint((v - in.min()) / in.step()) * in.step();
        return Math.max(in.min(), Math.min(in.max(), s));
    }
}
