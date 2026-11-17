package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelNode;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import Vystorm.vystorm_core.compat.dialog.DialogResponseView;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.SingleOptionDialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Automatischer Paper-Dialog aus einer Panel-Beschreibung für Clients ohne Panels (Spec §7): heading/text/kv/Fortschritt
 * → Text, item → Item-Body, toggle → bool, slider/number → numberRange, select → singleOption, input → text, button →
 * Dialog-Knopf (seitenweise, 12 pro Seite), table/list → höchstens 20 Zeilen als Text (+ „Im Browser öffnen“), tabs →
 * Knöpfe für die anderen Reiter (je Reiter-Block eigene Auswahl; lazy Reiter rufen {@code onTab}), form → Eingaben +
 * Senden. Gesperrte Eingaben erscheinen als Text, gesperrte Knöpfe gar nicht; Knöpfe mit {@code confirm} fragen erst in
 * einem Bestätigungsdialog. Nicht dialogfähig: image, chart, graph, Platzhalter und große Tabellen ohne Web-Link.
 * Aktualisierung nur nach einer Aktion. Core-intern.
 */
public final class DialogDowngrade {
    static final int MAX_ROWS = 20, BUTTONS_PER_PAGE = 12;

    public sealed interface Body permits TextBody, ItemBody {}
    public record TextBody(UiText text) implements Body {}
    public record ItemBody(String item, int count) implements Body {}

    public record Input(String elementId, Kind kind, UiText label, Object initial, double min, double max, double step,
                        List<String[]> options, int maxLength, String formId) {
        public enum Kind { BOOL, RANGE, OPTIONS, TEXT }
    }

    public enum ButtonKind { CLICK, SUBMIT, NAVIGATE, WEB, TAB, PAGE, APPLY }

    /** Bestätigung vor dem Ausführen (aus {@code button.confirm}). */
    public record Confirm(UiText title, UiText text, UiText yes, UiText no) {}

    /** {@code confirm} nur bei CLICK, {@code lazy} nur bei TAB. */
    public record Button(String elementId, ButtonKind kind, UiText label, String target, String arg, Confirm confirm, boolean lazy) {
        public Button(String elementId, ButtonKind kind, UiText label, String target, String arg) { this(elementId, kind, label, target, arg, null, false); }
    }

    public record Plan(boolean capable, String reason, List<Body> body, List<Input> inputs, List<Button> buttons, int page, int pages) {}

    /** Dialog-Antworten; {@code response} enthält die Werte aller Eingaben. */
    public interface Callbacks {
        void apply(Player player, DialogResponseView response, Plan plan);
        void button(Player player, DialogResponseView response, Plan plan, Button button);
    }

    private DialogDowngrade() {}

    // ------------------------------------------------------------------ Plan (rein)

    /** @param selectedTabs gewählter Reiter je Reiter-Block (ID des {@code tabs}-Elements → Reiter-ID) */
    public static Plan plan(PanelDocument doc, java.util.Map<String, String> selectedTabs, int page, boolean hasWeb) {
        Builder b = new Builder(doc, selectedTabs == null ? java.util.Map.of() : selectedTabs, hasWeb);
        for (PanelNode n : doc.root().children()) b.node(n, null);
        for (PanelNode n : doc.root().footer()) b.node(n, null);
        if (b.reason != null) return new Plan(false, b.reason, List.of(), List.of(), List.of(), 0, 1);
        int pages = Math.max(1, (b.actions.size() + BUTTONS_PER_PAGE - 1) / BUTTONS_PER_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        List<Button> buttons = new ArrayList<>(b.actions.subList(p * BUTTONS_PER_PAGE, Math.min(b.actions.size(), (p + 1) * BUTTONS_PER_PAGE)));
        if (b.inputs.stream().anyMatch(i -> i.formId() == null)) buttons.addFirst(new Button("", ButtonKind.APPLY, UiText.EMPTY, "", ""));
        if (p > 0) buttons.add(new Button("", ButtonKind.PAGE, UiText.plain("◀"), Integer.toString(p - 1), ""));
        if (p + 1 < pages) buttons.add(new Button("", ButtonKind.PAGE, UiText.plain("▶"), Integer.toString(p + 1), ""));
        buttons.addAll(b.tabs);
        if (b.needsWeb) buttons.add(new Button("", ButtonKind.WEB, UiText.EMPTY, "", ""));
        return new Plan(true, null, b.body, b.inputs, buttons, p, pages);
    }

    private static final class Builder {
        final PanelDocument doc;
        final java.util.Map<String, String> selectedTabs;
        final boolean hasWeb;
        final List<Body> body = new ArrayList<>();
        final List<Input> inputs = new ArrayList<>();
        final List<Button> actions = new ArrayList<>();
        final List<Button> tabs = new ArrayList<>();
        boolean needsWeb;
        String reason;

        Builder(PanelDocument doc, java.util.Map<String, String> selectedTabs, boolean hasWeb) {
            this.doc = doc;
            this.selectedTabs = selectedTabs;
            this.hasWeb = hasWeb;
        }

        /** Gesperrte Eingabe: nur als Text „Beschriftung: Wert“ (sonst lehnt die Prüfung sie ab). */
        boolean disabledInput(PanelNode n) {
            if (doc.effectivelyEnabled(n)) return false;
            String value = switch (n) {
                case PanelNode.Toggle t -> t.value() ? "✔" : "✖";
                case PanelNode.Slider s -> fmt(s.value());
                case PanelNode.NumberField s -> fmt(s.value());
                case PanelNode.Select s -> s.options().stream().filter(o -> o.id().equals(s.value())).map(o -> o.label().plainText()).findFirst().orElse("–");
                case PanelNode.Input i -> i.value();
                default -> null;
            };
            if (value == null) return false;
            UiText label = switch (n) {
                case PanelNode.Toggle t -> t.label();
                case PanelNode.Slider s -> s.label();
                case PanelNode.NumberField s -> s.label();
                case PanelNode.Select s -> s.label();
                case PanelNode.Input i -> i.label();
                default -> UiText.EMPTY;
            };
            body.add(new TextBody(join(label, UiText.plain(": " + value))));
            return true;
        }

        void node(PanelNode n, String formId) {
            if (reason != null || !n.common().visible()) return;
            if (disabledInput(n)) return;
            if (n instanceof PanelNode.Button && !doc.effectivelyEnabled(n)) return;   // gesperrter Knopf: im Dialog weglassen
            switch (n) {
                case PanelNode.Heading h -> body.add(new TextBody(h.text()));
                case PanelNode.Text t -> t.lines().forEach(l -> body.add(new TextBody(l)));
                case PanelNode.Kv kv -> kv.rows().forEach(r -> body.add(new TextBody(join(r.key(), UiText.plain(": "), r.value()))));
                case PanelNode.Badge badge -> body.add(new TextBody(badge.text()));
                case PanelNode.Progress p -> body.add(new TextBody(join(p.label(), UiText.plain(" " + fmt(p.value()) + "/" + fmt(p.max())))));
                case PanelNode.Timer t -> body.add(new TextBody(UiText.plain(String.format(Locale.ROOT, "%02d:%02d", t.remainingMs() / 60000, t.remainingMs() / 1000 % 60))));
                case PanelNode.ItemNode i -> body.add(new ItemBody(i.icon().base() == null ? "minecraft:barrier" : i.icon().base().id(), Math.max(1, i.icon().count())));
                case PanelNode.Section s -> {
                    if (!s.title().isEmpty()) body.add(new TextBody(s.title()));
                    s.children().forEach(c -> node(c, formId));
                }
                case PanelNode.Box box -> box.children().forEach(c -> node(c, formId));
                case PanelNode.Grid g -> g.children().forEach(c -> node(c, formId));
                case PanelNode.Form f -> {
                    f.children().forEach(c -> node(c, f.id()));
                    actions.add(new Button(f.id(), ButtonKind.SUBMIT, f.submit(), "", ""));
                }
                case PanelNode.Tabs t -> {
                    String chosen = t.id() == null ? null : selectedTabs.get(t.id());
                    String sel = chosen != null && t.tabs().stream().anyMatch(x -> x.id().equals(chosen)) ? chosen : t.selected();
                    for (PanelNode.Tabs.Tab tab : t.tabs()) {
                        if (tab.id().equals(sel)) tab.children().forEach(c -> node(c, formId));
                        else tabs.add(new Button(t.id() == null ? "" : t.id(), ButtonKind.TAB, tab.label(), tab.id(), "", null, tab.lazy()));
                    }
                }
                case PanelNode.Toggle t -> inputs.add(new Input(t.id(), Input.Kind.BOOL, t.label(), t.value(), 0, 0, 0, List.of(), 0, formId));
                case PanelNode.Slider s -> inputs.add(new Input(s.id(), Input.Kind.RANGE, s.label(), s.value(), s.min(), s.max(), s.step(), List.of(), 0, formId));
                case PanelNode.NumberField s -> inputs.add(new Input(s.id(), Input.Kind.RANGE, s.label(), s.value(), s.min(), s.max(), s.step(), List.of(), 0, formId));
                case PanelNode.Select s -> {
                    List<String[]> options = new ArrayList<>();
                    s.options().forEach(o -> options.add(new String[]{o.id(), o.label().plainText()}));
                    inputs.add(new Input(s.id(), Input.Kind.OPTIONS, s.label(), s.value() == null ? s.options().getFirst().id() : s.value(), 0, 0, 0, options, 0, formId));
                }
                case PanelNode.Input i -> inputs.add(new Input(i.id(), Input.Kind.TEXT, i.label(), i.value(), 0, 0, 0, List.of(), i.maxLength(), formId));
                case PanelNode.Button b -> actions.add(switch (b.target()) {
                    case PanelNode.Button.Act a -> new Button(b.id(), ButtonKind.CLICK, label(b), "", "", confirm(b), false);
                    case PanelNode.Button.Navigate nav -> new Button(b.id(), ButtonKind.NAVIGATE, label(b), nav.panel(), nav.arg());
                    case PanelNode.Button.Web w -> new Button(b.id(), ButtonKind.WEB, label(b), w.link().module(), w.link().page());
                });
                case PanelNode.Table t -> {
                    if (t.rows().size() > MAX_ROWS && !hasWeb) { reason = "table too large without web link"; return; }
                    for (int i = 0; i < Math.min(MAX_ROWS, t.rows().size()); i++) body.add(new TextBody(rowText(t.rows().get(i))));
                    if (t.rows().size() > MAX_ROWS) {
                        body.add(new TextBody(UiText.plain("… +" + (t.rows().size() - MAX_ROWS))));
                        needsWeb = true;
                    }
                }
                case PanelNode.ListView l -> {
                    if (l.items().size() > MAX_ROWS && !hasWeb) { reason = "list too large without web link"; return; }
                    for (int i = 0; i < Math.min(MAX_ROWS, l.items().size()); i++) body.add(new TextBody(l.items().get(i).title()));
                    if (l.items().size() > MAX_ROWS) {
                        body.add(new TextBody(UiText.plain("… +" + (l.items().size() - MAX_ROWS))));
                        needsWeb = true;
                    }
                }
                case PanelNode.Image img -> reason = "image";
                case PanelNode.Unsupported u -> reason = "unsupported " + u.type();
                case PanelNode.Divider d -> { }
                case PanelNode.Spacer s -> { }
                case PanelNode.Swatch s -> { }
                case PanelNode.Page p -> { }
            }
        }

        static Confirm confirm(PanelNode.Button b) {
            PanelNode.Button.Confirm c = b.confirm();
            return c == null ? null : new Confirm(c.title(), c.text(), c.yes(), c.no());
        }

        static UiText label(PanelNode.Button b) { return b.label().isEmpty() ? UiText.plain(b.id()) : b.label(); }

        static UiText rowText(PanelNode.Table.Row r) {
            StringBuilder sb = new StringBuilder();
            for (PanelNode.Table.Cell c : r.cells()) {
                if (c instanceof PanelNode.Table.TextCell t && !t.text().isEmpty()) sb.append(sb.isEmpty() ? "" : " · ").append(t.text().plainText());
            }
            return UiText.plain(sb.toString());
        }

        static UiText join(UiText... parts) {
            List<UiText.Segment> out = new ArrayList<>();
            for (UiText p : parts) out.addAll(p.segments());
            return new UiText(out);
        }

        static String fmt(double v) { return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v); }
    }

    // ------------------------------------------------------------------ Paper-Dialog

    public static DialogRequest request(Plan plan, Player player, UiText title, Callbacks cb) {
        List<DialogBody> body = new ArrayList<>();
        for (Body b : plan.body()) {
            switch (b) {
                case TextBody t -> body.add(DialogBody.plainMessage(component(t.text()), 300));
                case ItemBody i -> {
                    Material m = Material.matchMaterial(i.item());
                    if (m != null) body.add(DialogBody.item(new ItemStack(m, Math.min(64, i.count()))).build());
                }
            }
        }
        List<DialogInput> inputs = new ArrayList<>();
        for (int k = 0; k < plan.inputs().size(); k++) {
            Input in = plan.inputs().get(k);
            String key = "i" + k;
            Component label = component(in.label());
            inputs.add(switch (in.kind()) {
                case BOOL -> DialogInput.bool(key, label).initial(Boolean.TRUE.equals(in.initial())).build();
                case RANGE -> DialogInput.numberRange(key, label, (float) in.min(), (float) in.max()).step((float) in.step())
                        .initial((float) ((Double) in.initial()).doubleValue()).width(300).build();
                case OPTIONS -> {
                    List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                    for (String[] o : in.options()) entries.add(SingleOptionDialogInput.OptionEntry.create(o[0], Component.text(o[1]), o[0].equals(in.initial())));
                    yield DialogInput.singleOption(key, label, entries).width(300).build();
                }
                case TEXT -> DialogInput.text(key, label).initial((String) in.initial()).maxLength(Math.max(1, in.maxLength())).width(300).build();
            });
        }
        var lang = Localization.get().core();
        List<DialogButton> buttons = new ArrayList<>();
        for (Button b : plan.buttons()) {
            Component label = switch (b.kind()) {
                case APPLY -> lang.component(player, "panel.dialog.apply");
                case WEB -> b.label().isEmpty() ? lang.component(player, "panel.dialog.web") : component(b.label());
                default -> component(b.label());
            };
            buttons.add(b.kind() == ButtonKind.APPLY ? new DialogButton(label, (p, r) -> cb.apply(p, r, plan)) : new DialogButton(label, (p, r) -> cb.button(p, r, plan, b)));
        }
        return new DialogRequest(component(title), body, inputs, buttons, Player::isOnline, () -> { }, lang.plain(player, "panel.dialog.close"), 2);
    }

    /** Bestätigungsdialog eines Knopfs mit {@code confirm}: Ja führt {@code yes} aus, Nein/Esc {@code no}. */
    public static DialogRequest confirmation(Confirm c, Player player, Runnable yes, Runnable no) {
        var lang = Localization.get().core();
        List<DialogBody> body = c.text().isEmpty() ? List.of() : List.of(DialogBody.plainMessage(component(c.text()), 300));
        Component yesLabel = c.yes().isEmpty() ? lang.component(player, "panel.dialog.yes") : component(c.yes());
        String noLabel = c.no().isEmpty() ? lang.plain(player, "panel.dialog.no") : c.no().plainText();
        return new DialogRequest(component(c.title()), body, List.of(), List.of(new DialogButton(yesLabel, (p, r) -> yes.run())),
                Player::isOnline, no, noLabel, 2);
    }

    /** UiText → Adventure (Farbe, Dekorationen). */
    static Component component(UiText t) {
        TextComponent.Builder out = Component.text();
        for (UiText.Segment s : t.segments()) {
            var part = Component.text(s.text());
            if (s.color() != null) part = part.color(TextColor.color(s.color() & 0xFFFFFF));
            if (s.bold()) part = part.decorate(TextDecoration.BOLD);
            if (s.italic()) part = part.decorate(TextDecoration.ITALIC);
            if (s.underlined()) part = part.decorate(TextDecoration.UNDERLINED);
            if (s.strikethrough()) part = part.decorate(TextDecoration.STRIKETHROUGH);
            out.append(part);
        }
        return out.build();
    }
}
