package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_CHANGE;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_CLICK;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_PAGE;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_SELECT;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_SORT;

import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.internal.ScreenListLayout;
import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelColor;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIcon;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelNode;
import Vystorm.vystorm_core.web.internal.clientproto.ui.Icon;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

/**
 * Lays a panel document out as a Vystorm Screen (since 0.58.0): header with title and close button, the page body as
 * real sections, rows, grids, tabs, tables, lists, bars and inputs, and a footer whose hint line shows what the pointer
 * is on. Pure: no server access except {@link Material} lookups, so it is tested without Bukkit. The body scrolls as a
 * whole with the mouse wheel ({@link View#scroll}); the panel shrinks to its content up to the visible height.
 *
 * <p>Look (owner approval 2026-11-15, mockup {@code docs/screens-mockups/menus.html}): panel slate {@code #1B1F27},
 * raised rows {@code #252A33}, lines {@code #343B47}, Vystorm gold {@code #E3B04B}, square shapes only. Core-internal.
 */
final class PanelScreenLayout {
    static final int TEXT = 0xF2F2F2, MUTED = 0x9AA3B2, GOLD = 0xE3B04B, INK = 0x252A33, OK = 0x6FCF7F, WARN = 0xE8A33D, BAD = 0xE06C6C;
    static final int RAISE = 0xD0252A33, RAISE_SOFT = 0x70252A33, LINE = 0xFF343B47, ROW_LIT = 0xF03A4352, CARD = 0x40303A48;
    static final String HINT = "hint", CLOSE = "close";

    /** Player-side view state that survives re-renders: tabs, form edits, scroll, collapsed sections, chosen rows. */
    static final class View {
        final Map<String, String> tabs = new HashMap<>();
        final Map<String, JsonElement> form = new HashMap<>();
        final Set<String> collapsed = new HashSet<>();
        final Set<String> expanded = new HashSet<>();
        final Map<String, String> chosen = new HashMap<>();
        double scroll;
    }

    /** Texts the layout needs from the language files. */
    interface Texts {
        Component close();
        Component page(int page, int pages);
        Component scrollHint();
        /** Value text of a switched-on toggle in the list layout (0.66.0). */
        default Component on() { return Component.text("ON").color(TextColor.color(OK)); }
        default Component off() { return Component.text("OFF").color(TextColor.color(BAD)); }
        /** Position of the visible list rows, e.g. "3 to 12 of 27" (0.66.0). */
        default Component rows(int from, int to, int total) { return Component.text(from + "–" + to + " / " + total); }
        /** Click help of a list row: {@code number} (left more, right less), {@code choice} (next/previous) or {@code type}. */
        default Component listHelp(String kind) { return Component.empty(); }
    }

    /** What a click on a widget does. */
    sealed interface Act {
        record Close() implements Act {}
        /** A panel action through the normal checks; {@code alt} (may be null) is the value for a right click. */
        record Action(String elementId, int kind, String value, String alt, DialogDowngrade.Confirm confirm) implements Act {}
        record Navigate(String panel, String arg) implements Act {}
        record Web(String module, String page) implements Act {}
        record Tab(String tabsId, String tabId, boolean lazy) implements Act {}
        /** A field inside a form changed: stored in the view until the form is sent. {@code alt}: right click. */
        record FormSet(String elementId, JsonElement value, JsonElement alt) implements Act {}
        record Submit(String formId, JsonObject values, DialogDowngrade.Confirm confirm) implements Act {}
        /**
         * Text input: opens a typing dialog; {@code formId} null = send as CHANGE (or SEARCH). {@code number} (0.66.0): the
         * typed text is sent as a number (list rows of number fields).
         */
        record Edit(String elementId, UiText label, String value, int maxLength, String formId, boolean search, boolean number) implements Act {
            Edit(String elementId, UiText label, String value, int maxLength, String formId, boolean search) {
                this(elementId, label, value, maxLength, formId, search, false);
            }
        }
        record Toggle(String sectionKey) implements Act {}
    }

    record Result(ScreenLayout layout, Map<String, Act> acts, Map<String, Component> hints, Component hint, double maxScroll) {}

    /** Sizes in panel units for scale {@code s}. */
    record Metrics(double width, double maxHeight, double s) {
        double margin() { return 0.04 * s; }
        double head() { return 0.1 * s; }
        double foot() { return 0.065 * s; }
        double text() { return 0.042 * s; }
        double small() { return 0.034 * s; }
        double row() { return 0.066 * s; }
        double button() { return 0.062 * s; }
        double gap() { return 0.012 * s; }
        /** One GUI pixel of the panel protocol. */
        double px() { return 0.0042 * s; }
    }

    /** Panel size for a page: width from {@link PanelNode.Page.PageSize}, at most 92 % of the view. */
    static Metrics metrics(PanelNode.Page page, double viewWidth, double viewHeight, double scale) {
        double s = scale;
        double wanted = switch (page.size() == null ? PanelNode.Page.PageSize.MEDIUM : page.size()) {
            case SMALL -> 1.05 * s;
            case MEDIUM -> 1.55 * s;
            case LARGE -> 2.25 * s;
            case FULL -> viewWidth * 0.9;
        };
        double width = Math.max(0.8, Math.min(viewWidth * 0.92, wanted));
        return new Metrics(width, Math.max(0.6, viewHeight * 0.86), s);
    }

    private final PanelDocument doc;
    private final View view;
    private final Metrics m;
    private final Texts texts;
    private final Set<String> lists;
    /** Content position {y, height} of every list entry row, for the "3 to 12 of 27" hint. */
    private final List<double[]> listRows = new ArrayList<>();
    private final List<ScreenWidget> body = new ArrayList<>();
    private final Map<String, Act> acts = new LinkedHashMap<>();
    private final Map<String, Component> hints = new HashMap<>();
    private int next, sections;

    private PanelScreenLayout(PanelDocument doc, View view, Metrics m, Texts texts, Set<String> lists) {
        this.lists = lists == null ? Set.of() : lists;
        this.doc = doc;
        this.view = view;
        this.m = m;
        this.texts = texts;
    }

    /** @param status handler messages of the last action ({@code ✔ …}/{@code ✖ …}), shown in the hint line */
    static Result layout(PanelDocument doc, View view, Metrics m, Texts texts, List<String> status) {
        return layout(doc, view, m, texts, status, Set.of());
    }

    /** @param lists ids of the sections drawn with the list layout ({@link #listIds}) */
    static Result layout(PanelDocument doc, View view, Metrics m, Texts texts, List<String> status, Set<String> lists) {
        return new PanelScreenLayout(doc, view, m, texts, lists).build(status);
    }

    /**
     * Ids of the elements a plugin marked with the list layout ({@code Ui.Section.list()}, field {@code layout: "list"}
     * in the written document; the shared panel schema ignores the field, so mod and web show a normal section).
     */
    static Set<String> listIds(JsonObject document) {
        Set<String> out = new HashSet<>();
        if (document != null) collectLists(document, out);
        return out;
    }

    private static void collectLists(JsonElement e, Set<String> out) {
        if (e instanceof JsonObject o) {
            if (o.get("layout") instanceof JsonPrimitive p && p.isString() && "list".equals(p.getAsString())
                    && o.get("id") instanceof JsonPrimitive id && id.isString()) out.add(id.getAsString());
            for (Map.Entry<String, JsonElement> child : o.entrySet()) collectLists(child.getValue(), out);
        } else if (e instanceof com.google.gson.JsonArray a) {
            for (JsonElement child : a) collectLists(child, out);
        }
    }

    // ------------------------------------------------------------------ frame

    private Result build(List<String> status) {
        PanelNode.Page page = doc.root();
        double w = m.width(), margin = m.margin(), inner = w - 2 * margin;
        // body in content coordinates (y from 0), clipped and shifted at the end
        double contentH = 0;
        for (PanelNode n : page.children()) {
            if (!n.common().visible()) continue;
            double h = node(n, margin, contentH, inner, null);
            if (h > 0) contentH += h + m.gap();
        }
        List<ScreenWidget> bodyWidgets = new ArrayList<>(body);
        Map<String, Act> bodyActs = new LinkedHashMap<>(acts);
        body.clear();
        // fixed footer nodes (page.footer) in their own row above the hint line
        double footerRowH = 0;
        if (page.footer().stream().anyMatch(n -> n.common().visible())) {
            footerRowH = row(page.footer(), margin, 0, inner, PanelNode.Justify.END, false, 0, null);
        }
        List<ScreenWidget> footerWidgets = new ArrayList<>(body);
        body.clear();

        double head = m.head() + (empty(page.subtitle()) ? 0 : m.small());
        double foot = m.foot() + (footerRowH > 0 ? footerRowH + m.gap() : 0);
        double bodyMax = m.maxHeight() - head - foot - m.gap();
        double bodyH = Math.max(m.row(), Math.min(bodyMax, contentH));
        double h = head + m.gap() + bodyH + foot;
        double maxScroll = Math.max(0, contentH - bodyH);
        view.scroll = Math.max(0, Math.min(maxScroll, view.scroll));

        List<ScreenWidget> out = new ArrayList<>();
        // header
        double titleX = margin;
        Material icon = material(page.icon());
        if (icon != null) {
            out.add(ScreenWidget.icon(id(), margin, 0.025 * m.s(), 0.06 * m.s(), 0.06 * m.s(), icon));
            titleX += 0.075 * m.s();
        }
        double closeW = Math.max(0.2 * m.s(), textW(plain(texts.close()), m.small()) + 0.05 * m.s());
        out.add(ScreenWidget.label(id(), titleX, 0.025 * m.s(), w - titleX - margin - closeW - m.gap(), 0.06 * m.s(),
                colored(DialogDowngrade.component(page.title() == null ? UiText.EMPTY : page.title()), TEXT).decorate(TextDecoration.BOLD)).withAlign(ScreenWidget.Align.START));
        if (!empty(page.subtitle())) {
            out.add(ScreenWidget.label(id(), titleX, 0.085 * m.s(), w - titleX - margin - closeW - m.gap(), m.small(),
                    colored(DialogDowngrade.component(page.subtitle()), MUTED)).withAlign(ScreenWidget.Align.START));
        }
        out.add(new ScreenWidget(CLOSE, ScreenWidget.Kind.BUTTON, w - margin - closeW, 0.025 * m.s(), closeW, 0.06 * m.s(), texts.close(), true).withTextHeight(m.small()));
        acts.put(CLOSE, new Act.Close());
        out.add(ScreenWidget.fill(id(), 0, head, w, 0.004 * m.s(), LINE));

        // body, clipped to its window
        double top = head + m.gap();
        Map<String, Act> shown = new LinkedHashMap<>();
        Set<String> cutRows = new HashSet<>();
        for (ScreenWidget wd : bodyWidgets) {
            double v = wd.v() - view.scroll;
            int dot = wd.id().indexOf('.');
            // a list row ("w12") and its parts ("w12.n0", "w12.v" …) are shown or left out together
            if (dot > 0 && cutRows.contains(wd.id().substring(0, dot))) continue;
            if (v < -1e-6 || v + wd.height() > bodyH + 1e-6) { cutRows.add(wd.id()); continue; }
            out.add(moved(wd, wd.u(), top + v));
            Act a = bodyActs.get(wd.id());
            if (a != null) shown.put(wd.id(), a);
        }
        if (maxScroll > 0) {   // scroll bar on the right edge
            double track = bodyH, thumb = Math.max(0.04 * m.s(), track * bodyH / contentH);
            double at = (track - thumb) * (view.scroll / maxScroll);
            out.add(ScreenWidget.fill(id(), w - margin / 2 - 0.004 * m.s(), top, 0.008 * m.s(), track, 0x60343B47));
            out.add(ScreenWidget.fill(id(), w - margin / 2 - 0.004 * m.s(), top + at, 0.008 * m.s(), thumb, 0xD0E3B04B));
        }

        // footer
        double footTop = h - foot;
        out.add(ScreenWidget.fill(id(), 0, footTop, w, 0.004 * m.s(), LINE));
        for (ScreenWidget wd : footerWidgets) {
            out.add(moved(wd, wd.u(), footTop + m.gap() + wd.v()));
            Act a = acts.get(wd.id());
            if (a != null) shown.put(wd.id(), a);
        }
        Component idle = maxScroll > 0 ? colored(texts.scrollHint(), MUTED) : Component.empty();
        if (maxScroll > 0 && !listRows.isEmpty()) {   // 0.66.0: which list rows are on screen
            int first = 0, last = 0;
            for (int i = 0; i < listRows.size(); i++) {
                double v = listRows.get(i)[0] - view.scroll;
                if (v < -1e-6 || v + listRows.get(i)[1] > bodyH + 1e-6) continue;
                if (first == 0) first = i + 1;
                last = i + 1;
            }
            if (first > 0) idle = idle.append(Component.text("  ·  ").color(TextColor.color(MUTED))).append(colored(texts.rows(first, last, listRows.size()), MUTED));
        }
        Component hint = status.isEmpty() ? idle
                : colored(Component.text(String.join("  ", status)), status.getFirst().startsWith("✖") ? BAD : OK);
        out.add(ScreenWidget.label(HINT, margin, h - m.foot() + 0.016 * m.s(), inner, m.small(), hint).withAlign(ScreenWidget.Align.START));

        shown.put(CLOSE, acts.get(CLOSE));
        return new Result(new ScreenLayout(w, h, out), shown, hints, hint, maxScroll);
    }

    private static ScreenWidget moved(ScreenWidget w, double u, double v) {
        return new ScreenWidget(w.id(), w.kind(), u, v, w.width(), w.height(), w.text(), w.enabled(), w.icon(), w.textHeight(),
                w.fill(), w.align(), w.selected(), w.highlight());
    }

    // ------------------------------------------------------------------ nodes

    /** Lays {@code n} out at {@code (x, y)} with width {@code w}; returns the height it used. */
    private double node(PanelNode n, double x, double y, double w, String form) {
        if (!n.common().visible()) return 0;
        boolean on = doc.effectivelyEnabled(n);
        return switch (n) {
            case PanelNode.Heading h -> {
                double lh = switch (h.level()) { case 1 -> 0.062; case 2 -> 0.052; default -> 0.046; } * m.s();
                add(ScreenWidget.label(id(), x, y, w, lh, colored(DialogDowngrade.component(h.text()), TEXT).decorate(TextDecoration.BOLD))
                        .withAlign(ScreenWidget.Align.START));
                yield lh;
            }
            case PanelNode.Text t -> text(t, x, y, w);
            case PanelNode.Kv kv -> kv(kv, x, y, w);
            case PanelNode.Badge b -> {
                double bw = Math.min(w, textW(b.text().plainText(), m.small()) + 0.03 * m.s());
                add(label(x, y, bw, m.text(), colored(DialogDowngrade.component(b.text()), color(b.color(), TEXT))).withFill(RAISE));
                yield m.text();
            }
            case PanelNode.Swatch sw -> {
                double size = Math.max(m.small(), (sw.size() > 0 ? sw.size() : 12) * m.px());
                add(ScreenWidget.fill(id(), x, y, size, size, 0xFF000000 | color(sw.color(), GOLD)));
                yield size;
            }
            case PanelNode.Divider d -> {
                add(ScreenWidget.fill(id(), x, y + m.gap() / 2, w, Math.max(0.003, Math.max(1, d.size()) * 0.003) * m.s(), LINE));
                yield m.gap() + 0.004 * m.s();
            }
            case PanelNode.Spacer sp -> Math.max(0, sp.size()) * m.px();
            case PanelNode.Progress p -> progress(p.label(), p.value(), p.max(), color(p.color(), GOLD), p.height(), x, y, w, empty(p.label()));
            case PanelNode.Timer t -> {
                Component time = Component.text(clock(t.remainingMs(), t.format()));
                if (!t.bar() || t.totalMs() <= 0) {
                    add(label(x, y, w, m.text(), colored(time, TEXT)).withAlign(ScreenWidget.Align.START));
                    yield m.text();
                }
                yield progress(UiText.plain(clock(t.remainingMs(), t.format())), t.remainingMs(), t.totalMs(), GOLD, 0, x, y, w, false);
            }
            case PanelNode.ItemNode i -> item(i, x, y, on);
            case PanelNode.Image img -> image(img, x, y, w);
            case PanelNode.Section sec -> section(sec, x, y, w, form);
            case PanelNode.Box box -> box.horizontal()
                    ? row(box.children(), x + box.pad() * m.px(), y + box.pad() * m.px(), w - 2 * box.pad() * m.px(), box.justify(), box.wrap(), box.gap(), form) + 2 * box.pad() * m.px()
                    : col(box.children(), x + box.pad() * m.px(), y + box.pad() * m.px(), w - 2 * box.pad() * m.px(), box.gap(), form) + 2 * box.pad() * m.px();
            case PanelNode.Grid g -> grid(g, x, y, w, form);
            case PanelNode.Tabs t -> tabs(t, x, y, w, form);
            case PanelNode.Table t -> table(t, x, y, w, on);
            case PanelNode.ListView l -> list(l, x, y, w, on);
            case PanelNode.Button b -> button(b, x, y, w, on);
            case PanelNode.Toggle t -> toggle(t, x, y, w, on, form);
            case PanelNode.Slider s -> number(s.id(), s.label(), s.min(), s.max(), s.step(), s.value(), s.format(), s.decimals(), x, y, w, on, form);
            case PanelNode.NumberField s -> number(s.id(), s.label(), s.min(), s.max(), s.step(), s.value(), null, s.decimals(), x, y, w, on, form);
            case PanelNode.Select s -> select(s, x, y, w, on, form);
            case PanelNode.Input i -> input(i, x, y, w, on, form);
            case PanelNode.Form f -> form(f, x, y, w, on);
            case PanelNode.Unsupported u -> {
                add(label(x, y, w, m.text(), colored(Component.text("[" + u.type() + "]"), MUTED)).withAlign(ScreenWidget.Align.START));
                yield m.text();
            }
            case PanelNode.Page p -> col(p.children(), x, y, w, 0, form);
        };
    }

    /** Preferred width of elements that keep their own size in a row; -1 = takes a share of the free width. */
    private double pref(PanelNode n) {
        PanelNode.Size size = n.common().width();
        if (size != null && size.kind() == PanelNode.Size.Kind.PX) return size.value() * m.px();
        if (size != null && size.kind() == PanelNode.Size.Kind.FILL) return -1;
        return switch (n) {
            case PanelNode.Button b -> buttonWidth(b);
            case PanelNode.Badge b -> textW(b.text().plainText(), m.small()) + 0.03 * m.s();
            case PanelNode.ItemNode i -> itemSize(i);
            case PanelNode.Swatch sw -> Math.max(m.small(), (sw.size() > 0 ? sw.size() : 12) * m.px());
            case PanelNode.Spacer sp -> Math.max(0, sp.size()) * m.px();
            case PanelNode.Heading h -> textW(h.text().plainText(), 0.052 * m.s()) + 0.02 * m.s();
            case PanelNode.Toggle t -> textW("■ " + t.label().plainText(), m.text()) + 0.05 * m.s();
            default -> -1;
        };
    }

    private double col(List<PanelNode> children, double x, double y, double w, int gapPx, String form) {
        double gap = gapPx > 0 ? gapPx * m.px() : m.gap(), at = 0;
        boolean first = true;
        for (PanelNode c : children) {
            if (!c.common().visible()) continue;
            double h = node(c, x, y + at + (first ? 0 : gap), w, form);
            if (h <= 0) continue;
            at += h + (first ? 0 : gap);
            first = false;
        }
        return at;
    }

    private double row(List<PanelNode> children, double x, double y, double w, PanelNode.Justify justify, boolean wrap, int gapPx, String form) {
        double gap = gapPx > 0 ? gapPx * m.px() : m.gap();
        List<PanelNode> items = children.stream().filter(c -> c.common().visible()).toList();
        if (items.isEmpty()) return 0;
        // split into lines when wrapping and the fixed widths do not fit
        List<List<PanelNode>> lines = new ArrayList<>();
        List<PanelNode> line = new ArrayList<>();
        double used = 0;
        for (PanelNode c : items) {
            double p = pref(c);
            double need = (p < 0 ? 0.25 * m.s() : p) + (line.isEmpty() ? 0 : gap);
            if (wrap && !line.isEmpty() && used + need > w) { lines.add(line); line = new ArrayList<>(); used = 0; need -= gap; }
            line.add(c);
            used += need;
        }
        lines.add(line);
        double at = 0;
        for (List<PanelNode> l : lines) {
            at += rowLine(l, x, y + at, w, justify, gap, form);
            if (l != lines.getLast()) at += gap;
        }
        return at;
    }

    private double rowLine(List<PanelNode> items, double x, double y, double w, PanelNode.Justify justify, double gap, String form) {
        double fixed = 0, weight = 0;
        double[] widths = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            PanelNode c = items.get(i);
            PanelNode.Size size = c.common().width();
            double p = size != null && size.kind() == PanelNode.Size.Kind.PERCENT ? w * size.value() / 100.0 : pref(c);
            widths[i] = p;
            if (p >= 0) fixed += p; else weight += Math.max(1, c.common().grow());
        }
        double free = w - fixed - gap * (items.size() - 1);
        if (free < 0 && fixed > 0) {   // too wide: shrink the fixed ones
            double k = Math.max(0.1, (w - gap * (items.size() - 1)) / fixed);
            for (int i = 0; i < widths.length; i++) if (widths[i] >= 0) widths[i] *= k;
            free = 0;
        }
        double total = 0;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] < 0) widths[i] = weight > 0 ? Math.max(0.05, free * Math.max(1, items.get(i).common().grow()) / weight) : 0.05;
            total += widths[i];
        }
        total += gap * (items.size() - 1);
        double start = x, between = gap;
        if (weight == 0 && justify != null) {
            double rest = Math.max(0, w - total);
            switch (justify) {
                case CENTER -> start += rest / 2;
                case END -> start += rest;
                case BETWEEN -> between = items.size() > 1 ? gap + rest / (items.size() - 1) : gap;
                default -> { }
            }
        }
        double height = 0, at = start;
        for (int i = 0; i < items.size(); i++) {
            height = Math.max(height, node(items.get(i), at, y, widths[i], form));
            at += widths[i] + between;
        }
        return height;
    }

    private double grid(PanelNode.Grid g, double x, double y, double w, String form) {
        double gap = g.gap() > 0 ? g.gap() * m.px() : m.gap();
        int cols = g.columns() > 0 ? g.columns() : Math.max(1, (int) Math.floor((w + gap) / (g.cell() * m.px() + gap)));
        double cell = (w - gap * (cols - 1)) / cols;
        List<PanelNode> items = g.children().stream().filter(c -> c.common().visible()).toList();
        double at = 0;
        for (int r = 0; r * cols < items.size(); r++) {
            double h = 0;
            for (int c = 0; c < cols && r * cols + c < items.size(); c++) {
                h = Math.max(h, node(items.get(r * cols + c), x + c * (cell + gap), y + at, cell, form));
            }
            at += h + gap;
        }
        return Math.max(0, at - gap);
    }

    private double section(PanelNode.Section sec, double x, double y, double w, String form) {
        String key = sec.id() != null ? sec.id() : "s#" + sections;
        sections++;
        if (sec.id() != null && lists.contains(sec.id())) return listSection(sec, key, x, y, w, form);
        boolean collapsed = sec.collapsible() && (sec.collapsed() ? !view.expanded.contains(key) : view.collapsed.contains(key));
        int backdrop = body.size();
        boolean card = sec.variant() == PanelNode.Section.Variant.CARD;
        int accent = switch (sec.variant()) { case NOTICE -> GOLD; case WARN -> WARN; case ERROR -> BAD; default -> 0; };
        double pad = card || accent != 0 ? 0.02 * m.s() : 0;
        double at = pad;
        if (!sec.title().isEmpty() || sec.collapsible()) {
            Component title = colored(DialogDowngrade.component(sec.title()), MUTED);
            if (sec.collapsible()) {
                String bid = id();
                add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, x + pad, y + at, w - 2 * pad, m.text(),
                        Component.text(collapsed ? "▸ " : "▾ ").color(TextColor.color(GOLD)).append(title), true)
                        .withFill(ScreenWidget.FILL_NONE).withHighlight(ROW_LIT).withAlign(ScreenWidget.Align.START));
                acts.put(bid, new Act.Toggle(key));
            } else {
                add(label(x + pad, y + at, w - 2 * pad, m.text(), title).withAlign(ScreenWidget.Align.START));
            }
            at += m.text() + m.gap() / 2;
        }
        if (!collapsed) at += col(sec.children(), x + pad, y + at, w - 2 * pad, 0, form);
        at += pad;
        if (card) body.add(backdrop, ScreenWidget.fill(id(), x, y, w, at, CARD));
        if (accent != 0) body.add(backdrop, ScreenWidget.fill(id(), x, y, 0.006 * m.s(), at, 0xFF000000 | accent));
        return at;
    }

    private double tabs(PanelNode.Tabs t, double x, double y, double w, String form) {
        String key = t.id() == null ? "" : t.id();
        String chosen = view.tabs.get(key);
        String sel = chosen != null && t.tabs().stream().anyMatch(tab -> tab.id().equals(chosen)) ? chosen
                : t.selected() != null ? t.selected() : t.tabs().isEmpty() ? null : t.tabs().getFirst().id();
        double at = x, h = m.button();
        for (PanelNode.Tabs.Tab tab : t.tabs()) {
            Component label = DialogDowngrade.component(tab.label());
            if (tab.badge() != null && !tab.badge().isEmpty()) label = label.append(Component.text(" " + tab.badge().plainText()).color(TextColor.color(MUTED)));
            double tw = Math.min(w - (at - x), textW(plain(label), m.small()) + 0.05 * m.s());
            if (tw <= 0.02) break;
            String bid = id();
            add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, at, y, tw, h, label, true).withTextHeight(m.small()).withSelected(tab.id().equals(sel)));
            acts.put(bid, new Act.Tab(key, tab.id(), tab.lazy()));
            at += tw + m.gap() / 2;
        }
        double used = h + m.gap();
        for (PanelNode.Tabs.Tab tab : t.tabs()) {
            if (tab.id().equals(sel)) used += col(tab.children(), x, y + used, w, 0, form);
        }
        return used;
    }

    private double text(PanelNode.Text t, double x, double y, double w) {
        int rgb = switch (t.style()) { case MUTED -> MUTED; case OK -> OK; case WARN -> WARN; case ERROR -> BAD; default -> TEXT; };
        double at = 0;
        int count = 0, max = t.maxLines() > 0 ? t.maxLines() : Integer.MAX_VALUE;
        for (UiText line : t.lines()) {
            List<Component> parts = t.wrap() ? wrap(line, w, m.text()) : List.of(DialogDowngrade.component(line));
            for (Component part : parts) {
                if (count++ >= max) return at;
                add(label(x, y + at, w, m.text(), colored(part, rgb)).withAlign(ScreenWidget.Align.START));
                at += m.text();
            }
        }
        return at;
    }

    private double kv(PanelNode.Kv kv, double x, double y, double w) {
        double keyW = kv.keyWidth() > 0 ? kv.keyWidth() * m.px() : 0;
        if (keyW <= 0) for (PanelNode.Kv.KvRow r : kv.rows()) keyW = Math.max(keyW, textW(r.key().plainText(), m.text()));
        keyW = Math.min(w * 0.5, keyW + 0.03 * m.s());
        double at = 0;
        for (PanelNode.Kv.KvRow r : kv.rows()) {
            add(label(x, y + at, keyW, m.text(), colored(DialogDowngrade.component(r.key()), MUTED)).withAlign(ScreenWidget.Align.START));
            ScreenWidget value = label(x + keyW, y + at, w - keyW, m.text(), colored(DialogDowngrade.component(r.value()), TEXT)).withAlign(ScreenWidget.Align.START);
            add(value);
            if (!r.tooltip().isEmpty()) hints.put(value.id(), tooltip(r.tooltip()));
            at += m.text();
        }
        return at;
    }

    private double progress(UiText label, double value, double max, int rgb, int heightPx, double x, double y, double w, boolean numbers) {
        double at = 0;
        if (!label.isEmpty() || numbers) {
            if (!label.isEmpty()) add(label(x, y, w * 0.7, m.small(), colored(DialogDowngrade.component(label), MUTED)).withAlign(ScreenWidget.Align.START));
            if (numbers) add(label(x + w * 0.5, y, w * 0.5, m.small(), Component.text(fmt(value) + " / " + fmt(max)).color(TextColor.color(MUTED))).withAlign(ScreenWidget.Align.END));
            at += m.small() + 0.006 * m.s();
        }
        double bar = Math.max(0.014 * m.s(), (heightPx > 0 ? heightPx : 8) * m.px());
        add(ScreenWidget.fill(id(), x, y + at, w, bar, RAISE));
        double share = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
        if (share > 0) add(ScreenWidget.fill(id(), x, y + at, w * share, bar, 0xF0000000 | rgb));
        return at + bar;
    }

    private double itemSize(PanelNode.ItemNode i) { return Math.max(m.row(), (i.size() > 0 ? i.size() : 16) * m.px() * 1.4); }

    private double item(PanelNode.ItemNode i, double x, double y, boolean on) {
        double size = itemSize(i);
        if (i.action() && i.id() != null) {
            String bid = id();
            add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, x, y, size, size, "", on).withFill(i.slot() ? RAISE : ScreenWidget.FILL_NONE).withHighlight(ROW_LIT));
            acts.put(bid, new Act.Action(i.id(), KIND_CLICK, "", null, null));
            if (!i.common().tooltip().isEmpty()) hints.put(bid, tooltip(i.common().tooltip()));
        } else if (i.slot()) {
            add(ScreenWidget.fill(id(), x, y, size, size, RAISE));
        }
        Material mat = material(i.icon());
        add(ScreenWidget.icon(id(), x + size * 0.12, y + size * 0.12, size * 0.76, size * 0.76, mat == null ? Material.BARRIER : mat));
        if (i.icon() != null && i.icon().count() > 1) {
            add(label(x, y + size * 0.6, size * 0.95, size * 0.38, Component.text(Integer.toString(i.icon().count())).color(TextColor.color(TEXT))).withAlign(ScreenWidget.Align.END));
        }
        return size;
    }

    private double image(PanelNode.Image img, double x, double y, double w) {
        Material mat = img.src() != null && img.src().kind() == PanelNode.Image.Source.Kind.SPRITE ? spriteMaterial(img.src().value()) : null;
        if (mat != null) {
            double size = Math.min(w, Math.max(m.row(), img.height() * m.px()));
            add(ScreenWidget.icon(id(), x, y, size, size, mat));
            return size;
        }
        if (img.alt() == null || img.alt().isEmpty()) return 0;
        add(label(x, y, w, m.text(), colored(DialogDowngrade.component(img.alt()), MUTED)).withAlign(ScreenWidget.Align.START));
        return m.text();
    }

    // ------------------------------------------------------------------ list layout (0.66.0)

    /**
     * A section with the list layout: its title as a full-width heading row, then one row per setting with the name on
     * the left, the current value on the right and the description (or the first tooltip line) under the name. The whole
     * row is the click target with the meaning the control had: a toggle flips, a choice steps to the next option (right
     * click: previous), a slider goes one step up (right click: down), number and text fields open the typing dialog,
     * a button runs its action. Other elements keep their normal look inside the section.
     */
    private double listSection(PanelNode.Section sec, String key, double x, double y, double w, String form) {
        ScreenListLayout.Sizes z = new ScreenListLayout.Sizes(m.s());
        boolean collapsed = sec.collapsible() && (sec.collapsed() ? !view.expanded.contains(key) : view.collapsed.contains(key));
        double at = 0;
        if (!sec.title().isEmpty() || sec.collapsible()) {
            Component title = DialogDowngrade.component(sec.title());
            if (sec.collapsible()) title = Component.text(collapsed ? "▸ " : "▾ ").append(title);
            String hid = id();
            at += place(ScreenListLayout.Row.heading(hid, title, null, sec.collapsible()), x, y + at, w);
            if (sec.collapsible()) acts.put(hid, new Act.Toggle(key));
        }
        if (collapsed) return at;
        for (PanelNode c : sec.children()) {
            if (!c.common().visible()) continue;
            boolean on = doc.effectivelyEnabled(c);
            String rid = id();
            ListEntry entry = listEntry(c, rid, on, form);
            if (entry == null) {
                double h = node(c, x + z.inset(), y + at, w - 2 * z.inset(), form);
                if (h > 0) at += h + m.gap() / 2;
                continue;
            }
            double h = place(entry.row(), x, y + at, w);
            listRows.add(new double[] { y + at, h - z.gap() });
            if (entry.act() != null && on) acts.put(rid, entry.act());
            Component help = entry.help() == null ? Component.empty() : texts.listHelp(entry.help());
            Component hint = entry.row().name().colorIfAbsent(TextColor.color(TEXT));
            if (entry.row().value() != null) hint = hint.append(Component.text(": ").color(TextColor.color(MUTED))).append(entry.row().value());
            if (entry.tip() != null) hint = hint.append(Component.text(" – ").color(TextColor.color(MUTED))).append(colored(entry.tip(), MUTED));
            if (!plain(help).isEmpty()) hint = hint.append(Component.text("  ·  ").color(TextColor.color(MUTED))).append(colored(help, MUTED));
            hints.put(rid, hint);
            at += h;
        }
        return at;
    }

    private record ListEntry(ScreenListLayout.Row row, Act act, String help, Component tip) {}

    private double place(ScreenListLayout.Row row, double x, double y, double w) {
        ScreenListLayout.Placed placed = ScreenListLayout.place(row, x, y, w, new ScreenListLayout.Sizes(m.s()));
        body.addAll(placed.widgets());
        return placed.height();
    }

    /** The list row of a control, or null when the element keeps its normal look. */
    private ListEntry listEntry(PanelNode n, String rid, boolean on, String form) {
        return switch (n) {
            case PanelNode.Toggle t -> {
                boolean value = form != null && view.form.get(t.id()) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : t.value();
                Component desc = t.description() != null && !t.description().isEmpty() ? DialogDowngrade.component(t.description()) : firstTip(t.common().tooltip());
                Act act = form != null ? new Act.FormSet(t.id(), new JsonPrimitive(!value), null) : new Act.Action(t.id(), KIND_CHANGE, Boolean.toString(!value), null, null);
                yield new ListEntry(row(rid, DialogDowngrade.component(t.label()), value ? texts.on() : texts.off(), desc, on), act, null, allTips(t.common().tooltip(), desc));
            }
            case PanelNode.Slider s -> numberEntry(rid, s.id(), s.label(), s.min(), s.max(), s.step(), s.value(), s.format(), s.decimals(), s.common().tooltip(), on, form, false);
            case PanelNode.NumberField s -> numberEntry(rid, s.id(), s.label(), s.min(), s.max(), s.step(), s.value(), null, s.decimals(), s.common().tooltip(), on, form, true);
            case PanelNode.Select s -> {
                if (s.options().isEmpty()) yield null;
                String value = form != null && view.form.get(s.id()) instanceof JsonPrimitive p && p.isString() ? p.getAsString()
                        : s.value() != null ? s.value() : s.options().getFirst().id();
                int current = 0;
                for (int i = 0; i < s.options().size(); i++) if (s.options().get(i).id().equals(value)) current = i;
                String nextId = s.options().get((current + 1) % s.options().size()).id();
                String prevId = s.options().get((current - 1 + s.options().size()) % s.options().size()).id();
                Act act = form != null ? new Act.FormSet(s.id(), new JsonPrimitive(nextId), new JsonPrimitive(prevId))
                        : new Act.Action(s.id(), KIND_CHANGE, json(nextId), json(prevId), null);
                Component shown = colored(DialogDowngrade.component(s.options().get(current).label()), GOLD);
                Component desc = firstTip(s.common().tooltip());
                yield new ListEntry(row(rid, DialogDowngrade.component(s.label()), shown, desc, on), act, s.options().size() > 1 ? "choice" : null,
                        allTips(s.common().tooltip(), desc));
            }
            case PanelNode.Input i -> {
                String value = form != null && view.form.get(i.id()) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : i.value() == null ? "" : i.value();
                Component shown = value.isEmpty()
                        ? colored(i.placeholder() == null || i.placeholder().isEmpty() ? Component.text("–") : DialogDowngrade.component(i.placeholder()), MUTED)
                        : Component.text(value).color(TextColor.color(TEXT));
                Component desc = firstTip(i.common().tooltip());
                Act act = new Act.Edit(i.id(), i.label(), value, i.maxLength() > 0 ? i.maxLength() : 256, form, i.search(), false);
                yield new ListEntry(row(rid, DialogDowngrade.component(i.label()), shown, desc, on), act, "type", allTips(i.common().tooltip(), desc));
            }
            case PanelNode.Button b -> {
                Act act = switch (b.target()) {
                    case PanelNode.Button.Act a -> new Act.Action(b.id(), KIND_CLICK, "", null, confirm(b.confirm()));
                    case PanelNode.Button.Navigate nav -> new Act.Navigate(nav.panel(), nav.arg());
                    case PanelNode.Button.Web web -> new Act.Web(web.link().module(), web.link().page());
                };
                Component label = DialogDowngrade.component(b.label().isEmpty() ? UiText.plain(b.id() == null ? "?" : b.id()) : b.label());
                Component desc = firstTip(b.common().tooltip());
                yield new ListEntry(new ScreenListLayout.Row(rid, false, material(b.icon()), label, Component.text("›").color(TextColor.color(GOLD)), desc, on),
                        act, null, allTips(b.common().tooltip(), desc));
            }
            default -> null;
        };
    }

    private ListEntry numberEntry(String rid, String elementId, UiText label, double min, double max, double step, double value, String format, int decimals,
                                  List<UiText> tooltip, boolean on, String form, boolean typed) {
        double v = form != null && view.form.get(elementId) instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : value;
        double s = step > 0 ? step : 1;
        double up = clamp(v + s, min, max), down = clamp(v - s, min, max);
        String shown = format != null && format.contains("{v}") ? format.replace("{v}", fmt(v, decimals)) : fmt(v, decimals);
        Act act;
        if (form != null) act = new Act.FormSet(elementId, new JsonPrimitive(up), new JsonPrimitive(down));
        else if (typed) act = new Act.Edit(elementId, label, fmt(v, decimals), 32, null, false, true);
        else act = new Act.Action(elementId, KIND_CHANGE, num(up), num(down), null);
        Component desc = firstTip(tooltip);
        return new ListEntry(row(rid, DialogDowngrade.component(label), Component.text(shown).color(TextColor.color(GOLD)), desc, on), act,
                typed && form == null ? "type" : "number", allTips(tooltip, desc));
    }

    private static ScreenListLayout.Row row(String id, Component name, Component value, Component description, boolean on) {
        return ScreenListLayout.Row.entry(id, null, name, value, description, on);
    }

    private static Component firstTip(List<UiText> tooltip) {
        return tooltip == null || tooltip.isEmpty() ? null : DialogDowngrade.component(tooltip.getFirst());
    }

    /** All tooltip lines for the hover line when there are several; else the description. */
    private static Component allTips(List<UiText> tooltip, Component description) {
        if (tooltip != null && tooltip.size() > 1) return tooltip(tooltip);
        return description;
    }

    // ------------------------------------------------------------------ tables and lists

    private double table(PanelNode.Table t, double x, double y, double w, boolean on) {
        int cols = t.columns().size();
        if (cols == 0) return 0;
        double[] widths = new double[cols];
        double fixed = 0;
        int flex = 0;
        for (int c = 0; c < cols; c++) {
            PanelNode.Size size = t.columns().get(c).width();
            double v = size == null ? -1 : switch (size.kind()) {
                case PX -> size.value() * m.px();
                case PERCENT -> w * size.value() / 100.0;
                default -> -1;
            };
            widths[c] = v;
            if (v >= 0) fixed += v; else flex++;
        }
        double free = Math.max(0, w - fixed);
        for (int c = 0; c < cols; c++) if (widths[c] < 0) widths[c] = flex > 0 ? free / flex : 0;
        double total = 0;
        for (double cw : widths) total += cw;
        if (total > w) for (int c = 0; c < cols; c++) widths[c] *= w / total;

        String key = t.id() == null ? "" : t.id();
        double at = 0, hh = m.text();
        // header
        double cx = x;
        for (int c = 0; c < cols; c++) {
            PanelNode.Table.Column col = t.columns().get(c);
            Component label = colored(DialogDowngrade.component(col.label()), MUTED);
            boolean sorted = t.sort() != null && col.id().equals(t.sort().column());
            if (sorted) label = label.append(Component.text(t.sort().descending() ? " ▼" : " ▲").color(TextColor.color(GOLD)));
            ScreenWidget.Align align = align(col.align());
            if (col.sortable() && t.paging() != null && t.id() != null && on) {
                String bid = id();
                add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, cx, y, widths[c], hh, label, true)
                        .withFill(ScreenWidget.FILL_NONE).withHighlight(ROW_LIT).withAlign(align).withTextHeight(m.small()));
                boolean desc = sorted && !t.sort().descending();
                acts.put(bid, new Act.Action(t.id(), KIND_SORT, sortJson(col.id(), desc), sortJson(col.id(), !desc), null));
            } else {
                add(label(cx, y, widths[c], hh, label).withAlign(align).withTextHeight(m.small()));
            }
            cx += widths[c];
        }
        at += hh;
        add(ScreenWidget.fill(id(), x, y + at, w, 0.004 * m.s(), LINE));
        at += 0.006 * m.s();

        if (t.rows().isEmpty()) {
            Component empty = t.empty() == null || t.empty().isEmpty() ? Component.text("–") : DialogDowngrade.component(t.empty());
            add(label(x, y + at, w, m.text(), colored(empty, MUTED)));
            at += m.text();
        }
        boolean selectable = t.select() == PanelNode.SelectMode.SINGLE && t.id() != null;
        String selected = view.chosen.getOrDefault(key, t.selected());
        double rowH = m.row() * Math.max(1, Math.min(3, t.rowLines()));
        int index = 0;
        for (PanelNode.Table.Row r : t.rows()) {
            boolean picked = r.key().equals(selected);
            int fill = index++ % 2 == 0 ? RAISE_SOFT : 0x40252A33;
            String rowId = id();
            if (selectable) {
                add(new ScreenWidget(rowId, ScreenWidget.Kind.BUTTON, x, y + at, w, rowH - 0.004 * m.s(), "", on).withFill(picked ? RAISE : fill).withHighlight(ROW_LIT));
                acts.put(rowId, new Act.Action(t.id(), KIND_SELECT, json(r.key()), null, null));
            } else {
                add(ScreenWidget.fill(rowId, x, y + at, w, rowH - 0.004 * m.s(), picked ? RAISE : fill));
            }
            if (picked) add(ScreenWidget.fill(id(), x, y + at, 0.006 * m.s(), rowH - 0.004 * m.s(), 0xFF000000 | GOLD));
            if (!r.tooltip().isEmpty()) hints.put(rowId, tooltip(r.tooltip()));
            cx = x;
            for (int c = 0; c < cols && c < r.cells().size(); c++) {
                PanelNode.Table.Cell cell = r.cells().get(c);
                double cy = y + at + (rowH - m.text()) / 2 - 0.002 * m.s();
                switch (cell) {
                    case PanelNode.Table.TextCell tc -> add(label(cx + 0.01 * m.s(), cy, widths[c] - 0.02 * m.s(), m.text(),
                            colored(DialogDowngrade.component(tc.text()), picked ? GOLD : TEXT)).withAlign(align(t.columns().get(c).align())));
                    case PanelNode.Table.NodeCell nc -> {
                        double ny = y + at + Math.max(0, (rowH - m.button()) / 2) - 0.002 * m.s();
                        node(nc.node(), cx + 0.01 * m.s(), ny, widths[c] - 0.02 * m.s(), null);
                    }
                }
                cx += widths[c];
            }
            at += rowH;
        }
        if (t.paging() != null && t.paging().pages() > 1 && t.id() != null) {
            at += m.gap() / 2;
            int page = t.paging().page(), pages = t.paging().pages();
            double bw = 0.1 * m.s(), lw = 0.3 * m.s(), start = x + (w - 2 * bw - lw - m.gap()) / 2;
            String prev = id(), nextId = id();
            add(new ScreenWidget(prev, ScreenWidget.Kind.BUTTON, start, y + at, bw, m.button(), "◀", on && page > 0));
            add(label(start + bw + m.gap() / 2, y + at, lw, m.button(), colored(texts.page(page + 1, pages), MUTED)));
            add(new ScreenWidget(nextId, ScreenWidget.Kind.BUTTON, start + bw + lw + m.gap(), y + at, bw, m.button(), "▶", on && page + 1 < pages));
            acts.put(prev, new Act.Action(t.id(), KIND_PAGE, Integer.toString(Math.max(0, page - 1)), null, null));
            acts.put(nextId, new Act.Action(t.id(), KIND_PAGE, Integer.toString(Math.min(pages - 1, page + 1)), null, null));
            at += m.button();
        }
        return at;
    }

    private double list(PanelNode.ListView l, double x, double y, double w, boolean on) {
        String key = l.id() == null ? "" : l.id();
        boolean selectable = l.select() == PanelNode.SelectMode.SINGLE && l.id() != null;
        String selected = view.chosen.getOrDefault(key, l.selected());
        double at = 0;
        if (l.items().isEmpty()) {
            Component empty = l.empty() == null || l.empty().isEmpty() ? Component.text("–") : DialogDowngrade.component(l.empty());
            add(label(x, y, w, m.text(), colored(empty, MUTED)).withAlign(ScreenWidget.Align.START));
            return m.text();
        }
        for (PanelNode.ListView.Entry e : l.items()) {
            boolean sub = e.subtitle() != null && !e.subtitle().isEmpty();
            double h = sub ? m.row() * 1.45 : m.row();
            boolean picked = e.key().equals(selected);
            String rowId = id();
            double iconSize = m.row() * 0.66, textX = x + 0.02 * m.s();
            Material mat = material(e.icon());
            if (mat != null) textX += iconSize + 0.015 * m.s();
            double rightW = e.right() == null || e.right().isEmpty() ? 0 : Math.min(w * 0.35, textW(e.right().plainText(), m.small()) + 0.03 * m.s());
            double badgeW = e.badge() == null || e.badge().isEmpty() ? 0 : Math.min(w * 0.25, textW(e.badge().plainText(), m.small()) + 0.03 * m.s());
            double titleW = Math.max(0.05, x + w - textX - rightW - badgeW - 0.02 * m.s());
            Component title = colored(DialogDowngrade.component(e.title()), picked ? GOLD : TEXT);
            if (selectable) {
                // the row is one button; its own text is the title, so hover keeps everything readable on the slate highlight
                add(new ScreenWidget(rowId, ScreenWidget.Kind.BUTTON, x, y + at, w, h - 0.006 * m.s(), "", on).withFill(picked ? 0xF0303A48 : RAISE).withHighlight(ROW_LIT));
                acts.put(rowId, new Act.Action(l.id(), KIND_SELECT, json(e.key()), null, null));
            } else {
                add(ScreenWidget.fill(rowId, x, y + at, w, h - 0.006 * m.s(), RAISE));
            }
            if (picked) add(ScreenWidget.fill(id(), x, y + at, 0.006 * m.s(), h - 0.006 * m.s(), 0xFF000000 | GOLD));
            if (!e.tooltip().isEmpty()) hints.put(rowId, tooltip(e.tooltip()));
            if (mat != null) add(ScreenWidget.icon(id(), x + 0.02 * m.s(), y + at + (h - iconSize) / 2 - 0.003 * m.s(), iconSize, iconSize, mat));
            double ty = sub ? y + at + 0.012 * m.s() : y + at + (h - m.text()) / 2 - 0.003 * m.s();
            add(label(textX, ty, titleW, m.text(), title).withAlign(ScreenWidget.Align.START));
            if (sub) add(label(textX, ty + m.text(), titleW, m.small(), colored(DialogDowngrade.component(e.subtitle()), MUTED)).withAlign(ScreenWidget.Align.START));
            if (badgeW > 0) add(label(x + w - rightW - badgeW - 0.01 * m.s(), y + at + (h - m.small()) / 2, badgeW, m.small(),
                    colored(DialogDowngrade.component(e.badge()), GOLD)).withFill(0xD0303A48));
            if (rightW > 0) add(label(x + w - rightW - 0.01 * m.s(), y + at + (h - m.small()) / 2, rightW, m.small(),
                    colored(DialogDowngrade.component(e.right()), MUTED)).withAlign(ScreenWidget.Align.END));
            at += h;
        }
        return at;
    }

    // ------------------------------------------------------------------ inputs

    private double buttonWidth(PanelNode.Button b) {
        double icon = material(b.icon()) != null ? m.button() * 0.7 + 0.01 * m.s() : 0;
        return Math.max(0.12 * m.s(), textW(b.label().plainText(), m.small()) + 0.05 * m.s() + icon);
    }

    private double button(PanelNode.Button b, double x, double y, double w, boolean on) {
        String bid = id();
        double bw = w;   // the parent row (intrinsic width, see pref) or column decides
        Material mat = material(b.icon());
        Component label = DialogDowngrade.component(b.label().isEmpty() ? UiText.plain(b.id() == null ? "?" : b.id()) : b.label());
        ScreenWidget widget = new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, x, y, bw, m.button(), mat == null ? label : Component.empty(), on)
                .withTextHeight(m.small());
        widget = switch (b.style()) {
            case PRIMARY -> widget.withFill(0xF0E3B04B).withHighlight(0xF0F2C66A).withText(mat == null ? label.colorIfAbsent(TextColor.color(INK)) : Component.empty());
            case DANGER -> widget.withFill(0xE0A33B3B).withHighlight(0xF0C24A4A);
            case GHOST -> widget.withFill(ScreenWidget.FILL_NONE).withHighlight(ROW_LIT);
            default -> mat == null ? widget : widget.withHighlight(ROW_LIT);
        };
        add(widget);
        if (mat != null) {
            double size = m.button() * 0.7;
            double tw = Math.min(bw - size - 0.03 * m.s(), textW(b.label().plainText(), m.small()) + 0.02 * m.s());
            double start = x + Math.max(0.01 * m.s(), (bw - size - 0.01 * m.s() - tw) / 2);
            add(ScreenWidget.icon(id(), start, y + (m.button() - size) / 2, size, size, mat));
            add(label(start + size + 0.01 * m.s(), y + (m.button() - m.small()) / 2 - 0.002 * m.s(), Math.max(0.03, tw), m.small(),
                    b.style() == PanelNode.Button.Style.PRIMARY ? label.colorIfAbsent(TextColor.color(INK)) : label));
        }
        Act act = switch (b.target()) {
            case PanelNode.Button.Act a -> new Act.Action(b.id(), KIND_CLICK, "", null, confirm(b.confirm()));
            case PanelNode.Button.Navigate nav -> new Act.Navigate(nav.panel(), nav.arg());
            case PanelNode.Button.Web web -> new Act.Web(web.link().module(), web.link().page());
        };
        acts.put(bid, act);
        if (!b.common().tooltip().isEmpty()) hints.put(bid, tooltip(b.common().tooltip()));
        return m.button();
    }

    private double toggle(PanelNode.Toggle t, double x, double y, double w, boolean on, String form) {
        boolean value = form != null && view.form.get(t.id()) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : t.value();
        String bid = id();
        Component label = Component.text(value ? "■ " : "□ ").color(TextColor.color(value ? GOLD : MUTED)).append(colored(DialogDowngrade.component(t.label()), TEXT));
        add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, x, y, w, m.button(), label, on).withTextHeight(m.text())
                .withAlign(ScreenWidget.Align.START).withFill(ScreenWidget.FILL_NONE).withHighlight(ROW_LIT));
        acts.put(bid, form != null ? new Act.FormSet(t.id(), new JsonPrimitive(!value), null) : new Act.Action(t.id(), KIND_CHANGE, Boolean.toString(!value), null, null));
        if (!t.common().tooltip().isEmpty()) hints.put(bid, tooltip(t.common().tooltip()));
        double at = m.button();
        if (t.description() != null && !t.description().isEmpty()) {
            for (Component part : wrap(t.description(), w - 0.04 * m.s(), m.small())) {
                add(label(x + 0.04 * m.s(), y + at, w - 0.04 * m.s(), m.small(), colored(part, MUTED)).withAlign(ScreenWidget.Align.START));
                at += m.small();
            }
        }
        return at;
    }

    private double number(String elementId, UiText label, double min, double max, double step, double value, String format, int decimals,
                          double x, double y, double w, boolean on, String form) {
        double v = form != null && view.form.get(elementId) instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : value;
        double bw = 0.08 * m.s(), valueW = Math.min(0.32 * m.s(), w * 0.3), labelW = Math.max(0.05, w - 2 * bw - valueW - m.gap());
        add(label(x, y, labelW, m.button(), colored(DialogDowngrade.component(label), TEXT)).withAlign(ScreenWidget.Align.START).withTextHeight(m.text()));
        double bx = x + labelW + m.gap();
        String minus = id(), plus = id();
        double s = step > 0 ? step : 1;
        double down = clamp(v - s, min, max), up = clamp(v + s, min, max), bigDown = clamp(v - 10 * s, min, max), bigUp = clamp(v + 10 * s, min, max);
        add(new ScreenWidget(minus, ScreenWidget.Kind.BUTTON, bx, y, bw, m.button(), "−", on && v > min));
        String shown = format != null && format.contains("{v}") ? format.replace("{v}", fmt(v, decimals)) : fmt(v, decimals);
        add(label(bx + bw, y, valueW, m.button(), Component.text(shown).color(TextColor.color(GOLD))).withTextHeight(m.text()));
        add(new ScreenWidget(plus, ScreenWidget.Kind.BUTTON, bx + bw + valueW, y, bw, m.button(), "+", on && v < max));
        if (form != null) {
            acts.put(minus, new Act.FormSet(elementId, new JsonPrimitive(down), new JsonPrimitive(bigDown)));
            acts.put(plus, new Act.FormSet(elementId, new JsonPrimitive(up), new JsonPrimitive(bigUp)));
        } else {
            acts.put(minus, new Act.Action(elementId, KIND_CHANGE, num(down), num(bigDown), null));
            acts.put(plus, new Act.Action(elementId, KIND_CHANGE, num(up), num(bigUp), null));
        }
        return m.button();
    }

    private double select(PanelNode.Select s, double x, double y, double w, boolean on, String form) {
        if (s.options().isEmpty()) return 0;
        String value = form != null && view.form.get(s.id()) instanceof JsonPrimitive p && p.isString() ? p.getAsString()
                : s.value() != null ? s.value() : s.options().getFirst().id();
        double at = 0;
        if (!s.label().isEmpty()) {
            add(label(x, y, w, m.small(), colored(DialogDowngrade.component(s.label()), MUTED)).withAlign(ScreenWidget.Align.START));
            at += m.small() + 0.004 * m.s();
        }
        int current = 0;
        for (int i = 0; i < s.options().size(); i++) if (s.options().get(i).id().equals(value)) current = i;
        if (s.style() == PanelNode.Select.Style.SEGMENTED && s.options().size() <= 8) {
            double ow = (w - m.gap() / 2 * (s.options().size() - 1)) / s.options().size();
            for (int i = 0; i < s.options().size(); i++) {
                PanelNode.Select.Option o = s.options().get(i);
                String bid = id();
                add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, x + i * (ow + m.gap() / 2), y + at, ow, m.button(), DialogDowngrade.component(o.label()), on)
                        .withTextHeight(m.small()).withSelected(i == current));
                acts.put(bid, form != null ? new Act.FormSet(s.id(), new JsonPrimitive(o.id()), null) : new Act.Action(s.id(), KIND_CHANGE, json(o.id()), null, null));
                if (!o.tooltip().isEmpty()) hints.put(bid, tooltip(o.tooltip()));
            }
        } else {
            String bid = id();
            String nextId = s.options().get((current + 1) % s.options().size()).id();
            String prevId = s.options().get((current - 1 + s.options().size()) % s.options().size()).id();
            Component shown = DialogDowngrade.component(s.options().get(current).label()).append(Component.text("  ◀ ▶").color(TextColor.color(GOLD)));
            add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, x, y + at, w, m.button(), shown, on).withTextHeight(m.text()).withAlign(ScreenWidget.Align.START).withHighlight(ROW_LIT));
            acts.put(bid, form != null ? new Act.FormSet(s.id(), new JsonPrimitive(nextId), new JsonPrimitive(prevId))
                    : new Act.Action(s.id(), KIND_CHANGE, json(nextId), json(prevId), null));
            hints.put(bid, tooltipOr(s.common().tooltip(), "◀ ▶"));
        }
        return at + m.button();
    }

    private double input(PanelNode.Input i, double x, double y, double w, boolean on, String form) {
        String value = form != null && view.form.get(i.id()) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : i.value() == null ? "" : i.value();
        double at = 0;
        if (!i.label().isEmpty()) {
            add(label(x, y, w, m.small(), colored(DialogDowngrade.component(i.label()), MUTED)).withAlign(ScreenWidget.Align.START));
            at += m.small() + 0.004 * m.s();
        }
        String bid = id();
        Component shown = value.isEmpty()
                ? colored(i.placeholder() == null || i.placeholder().isEmpty() ? Component.text("…") : DialogDowngrade.component(i.placeholder()), MUTED)
                : Component.text(value).color(TextColor.color(TEXT));
        if (i.search()) shown = Component.text("⌕ ").color(TextColor.color(GOLD)).append(shown);
        add(new ScreenWidget(bid, ScreenWidget.Kind.INPUT, x, y + at, w, m.button(), shown, on).withTextHeight(m.text()).withAlign(ScreenWidget.Align.START).withHighlight(ROW_LIT));
        acts.put(bid, new Act.Edit(i.id(), i.label(), value, i.maxLength() > 0 ? i.maxLength() : 256, form, i.search(), false));
        if (!i.common().tooltip().isEmpty()) hints.put(bid, tooltip(i.common().tooltip()));
        return at + m.button();
    }

    private double form(PanelNode.Form f, double x, double y, double w, boolean on) {
        double at = col(f.children(), x, y, w, 0, f.id());
        at += m.gap();
        String bid = id();
        Component label = DialogDowngrade.component(f.submit().isEmpty() ? UiText.plain("OK") : f.submit()).colorIfAbsent(TextColor.color(INK));
        double bw = Math.min(w, textW(plain(label), m.small()) + 0.08 * m.s());
        add(new ScreenWidget(bid, ScreenWidget.Kind.BUTTON, x + w - bw, y + at, bw, m.button(), label, on)
                .withTextHeight(m.small()).withFill(0xF0E3B04B).withHighlight(0xF0F2C66A));
        acts.put(bid, new Act.Submit(f.id(), formValues(f), confirm(f.confirm())));
        return at + m.button();
    }

    /** Values of all fields of a form: the player's edits in the view, else the values the server sent. */
    JsonObject formValues(PanelNode.Form f) {
        JsonObject out = new JsonObject();
        collect(f.children(), out);
        return out;
    }

    private void collect(List<PanelNode> nodes, JsonObject out) {
        for (PanelNode n : nodes) {
            if (n instanceof PanelNode.Form) continue;
            String id = n.id();
            JsonElement edited = id == null ? null : view.form.get(id);
            switch (n) {
                case PanelNode.Toggle t -> out.add(id, edited != null ? edited : new JsonPrimitive(t.value()));
                case PanelNode.Slider s -> out.add(id, edited != null ? edited : new JsonPrimitive(s.value()));
                case PanelNode.NumberField s -> out.add(id, edited != null ? edited : new JsonPrimitive(s.value()));
                case PanelNode.Select s -> {
                    String v = s.value() != null ? s.value() : s.options().isEmpty() ? null : s.options().getFirst().id();
                    if (edited != null) out.add(id, edited); else if (v != null) out.add(id, new JsonPrimitive(v));
                }
                case PanelNode.Input i -> out.add(id, edited != null ? edited : new JsonPrimitive(i.value() == null ? "" : i.value()));
                default -> collect(n.nested(), out);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private String id() { return "w" + next++; }

    private static boolean empty(UiText t) { return t == null || t.isEmpty(); }

    private void add(ScreenWidget w) { body.add(w); }

    private ScreenWidget label(double x, double y, double w, double h, Component text) {
        return ScreenWidget.label(id(), x, y, Math.max(0.01, w), h, text);
    }

    /** Width of {@code text} in panel units at line height {@code h} (same estimate the runtime uses to fit text). */
    static double textW(String text, double h) {
        return ScreenRuntime.textWidth(text) * (1.0 / 40) * (h / 0.25 * 0.8);
    }

    /** Word wrap of one line to width {@code w}; keeps the first segment's colour for every part. */
    List<Component> wrap(UiText line, double w, double h) {
        String plain = line.plainText();
        if (textW(plain, h) <= w || plain.isBlank()) return List.of(DialogDowngrade.component(line));
        Integer color = line.segments().isEmpty() ? null : line.segments().getFirst().color();
        List<Component> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : plain.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textW(candidate, h) > w && !current.isEmpty()) {
                out.add(seg(current.toString(), color));
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) out.add(seg(current.toString(), color));
        return out;
    }

    private static Component seg(String text, Integer color) {
        Component c = Component.text(text);
        return color == null ? c : c.color(TextColor.color(color & 0xFFFFFF));
    }

    static Component colored(Component c, int rgb) { return c.colorIfAbsent(TextColor.color(rgb)); }

    static String plain(Component c) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c); }

    private static Component tooltip(List<UiText> lines) {
        StringBuilder sb = new StringBuilder();
        for (UiText l : lines) sb.append(sb.isEmpty() ? "" : " · ").append(l.plainText());
        return Component.text(sb.toString()).color(TextColor.color(MUTED));
    }

    private static Component tooltipOr(List<UiText> lines, String fallback) {
        return lines.isEmpty() ? Component.text(fallback).color(TextColor.color(MUTED)) : tooltip(lines);
    }

    static ScreenWidget.Align align(PanelNode.Align a) {
        if (a == null) return ScreenWidget.Align.START;
        return switch (a) { case END -> ScreenWidget.Align.END; case CENTER -> ScreenWidget.Align.CENTER; default -> ScreenWidget.Align.START; };
    }

    static int color(PanelColor c, int fallback) {
        if (c == null) return fallback;
        if (c.token() == null) return c.argb() & 0xFFFFFF;
        return switch (c.token()) {
            case ACCENT -> GOLD;
            case OK -> OK;
            case WARN -> WARN;
            case DANGER -> BAD;
            case MUTED -> MUTED;
            case RARITY_COMMON -> 0xFFFFFF;
            case RARITY_UNCOMMON -> 0x55FF55;
            case RARITY_RARE -> 0x55FFFF;
            case RARITY_EPIC -> 0xFF55FF;
            case RARITY_LEGENDARY -> 0xFFAA00;
            case RARITY_MYTHIC -> 0xFF5555;
        };
    }

    static Material material(PanelIcon icon) {
        if (icon == null || icon.base() == null) return null;
        Icon base = icon.base();
        if (base.kind() == Icon.Kind.ITEM) return matchItem(base.id());
        if (base.kind() == Icon.Kind.SPRITE) return spriteMaterial(base.id());
        return null;
    }

    /** {@code minecraft:item/diamond} or {@code item/diamond} → DIAMOND; other sprites have no item. */
    static Material spriteMaterial(String sprite) {
        if (sprite == null) return null;
        String path = sprite.contains(":") ? sprite.substring(sprite.indexOf(':') + 1) : sprite;
        if (path.startsWith("item/")) return matchItem(path.substring(5));
        if (path.startsWith("block/")) return matchItem(path.substring(6));
        return null;
    }

    private static Material matchItem(String id) {
        if (id == null) return null;
        Material m = Material.matchMaterial(id);
        return m == null || m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR ? null : m;
    }

    static DialogDowngrade.Confirm confirm(PanelNode.Button.Confirm c) {
        return c == null ? null : new DialogDowngrade.Confirm(c.title(), c.text(), c.yes(), c.no());
    }

    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    private static String json(String s) { return new JsonPrimitive(s).toString(); }

    private static String num(double v) { return new JsonPrimitive(v == Math.rint(v) ? (Number) (long) v : (Number) v).toString(); }

    private static String sortJson(String column, boolean desc) {
        JsonObject o = new JsonObject();
        o.addProperty("column", column);
        o.addProperty("dir", desc ? "desc" : "asc");
        return o.toString();
    }

    static String fmt(double v) { return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    static String fmt(double v, int decimals) {
        if (decimals <= 0) return v == Math.rint(v) ? Long.toString((long) v) : fmt(v);
        return String.format(Locale.ROOT, "%." + Math.min(6, decimals) + "f", v);
    }

    static String clock(long ms, PanelNode.Timer.Format format) {
        long sec = Math.max(0, ms / 1000);
        return switch (format == null ? PanelNode.Timer.Format.MM_SS : format) {
            case HH_MM_SS -> String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
            case RELATIVE -> sec >= 3600 ? (sec / 3600) + " h" : sec >= 60 ? (sec / 60) + " min" : sec + " s";
            default -> String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
        };
    }
}
