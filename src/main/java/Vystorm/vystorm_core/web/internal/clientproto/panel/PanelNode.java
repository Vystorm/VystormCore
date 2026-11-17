// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelNode.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import java.util.ArrayList;
import java.util.List;

/** Unveränderliches Panel-Modell (Schema v1, Spec §3.2). Ein Record pro Elementtyp. */
public sealed interface PanelNode {
    Common common();

    default String id() { return common().id(); }

    /** Alle direkt verschachtelten Elemente (für ID-Index, Eltern, Traversierung). */
    default List<PanelNode> nested() { return List.of(); }

    /** Nimmt Eingaben an → braucht eine echte (nicht Pfad-)ID. */
    default boolean interactive() { return false; }

    enum Align { START, CENTER, END, STRETCH }

    enum Justify { START, CENTER, END, BETWEEN }

    enum SelectMode { NONE, SINGLE }

    record Size(Kind kind, int value) {
        public enum Kind { AUTO, PX, PERCENT, FILL }
        public static final Size AUTO = new Size(Kind.AUTO, 0);
    }

    /** Gemeinsame Felder; {@code id} null = namenlos. */
    record Common(String id, List<UiText> tooltip, boolean visible, boolean disabled, Size width, int minWidth, int maxWidth,
                  int grow, Align align) {
        public Common { tooltip = List.copyOf(tooltip); }
    }

    record WebLink(String module, String page) {}

    record Page(Common common, UiText title, UiText subtitle, PanelIcon icon, PageSize size, Theme theme, PanelColor accent,
                WebLink web, List<PanelNode> children, List<PanelNode> footer) implements PanelNode {
        public enum PageSize {
            SMALL(240), MEDIUM(360), LARGE(520), FULL(0);
            public final int width;
            PageSize(int width) { this.width = width; }
        }
        public enum Theme { VYSTORM, VANILLA }
        public Page { children = List.copyOf(children); footer = List.copyOf(footer); }
        @Override public List<PanelNode> nested() { return concat(children, footer); }
    }

    record Section(Common common, UiText title, Variant variant, boolean collapsible, boolean collapsed, List<PanelNode> children) implements PanelNode {
        public enum Variant { CARD, PLAIN, NOTICE, WARN, ERROR }
        public Section { children = List.copyOf(children); }
        @Override public List<PanelNode> nested() { return children; }
    }

    /** {@code row} ({@code horizontal}) oder {@code col}. */
    record Box(Common common, boolean horizontal, int gap, int pad, Justify justify, boolean wrap, Scroll scroll, int maxHeight,
               List<PanelNode> children) implements PanelNode {
        public enum Scroll { NONE, Y, X }
        public Box { children = List.copyOf(children); }
        @Override public List<PanelNode> nested() { return children; }
    }

    /** Genau eins von {@code columns} (1..12) oder {@code cell} (Mindestbreite 16..400); das andere ist 0. */
    record Grid(Common common, int columns, int cell, int gap, List<PanelNode> children) implements PanelNode {
        public Grid { children = List.copyOf(children); }
        @Override public List<PanelNode> nested() { return children; }
    }

    record Tabs(Common common, List<Tab> tabs, String selected) implements PanelNode {
        public record Tab(String id, UiText label, PanelIcon icon, UiText badge, List<PanelNode> children, boolean lazy) {
            public Tab { children = List.copyOf(children); }
        }
        public Tabs { tabs = List.copyOf(tabs); }
        @Override public List<PanelNode> nested() {
            List<PanelNode> out = new ArrayList<>();
            for (Tab t : tabs) out.addAll(t.children());
            return out;
        }
        @Override public boolean interactive() { return tabs.stream().anyMatch(Tab::lazy); }
    }

    record Heading(Common common, UiText text, int level) implements PanelNode {}

    record Text(Common common, List<UiText> lines, TextStyle style, int maxLines, boolean wrap) implements PanelNode {
        public enum TextStyle { BODY, MUTED, OK, WARN, ERROR }
        public Text { lines = List.copyOf(lines); }
    }

    record Kv(Common common, List<KvRow> rows, int keyWidth) implements PanelNode {
        public record KvRow(UiText key, UiText value, PanelIcon icon, List<UiText> tooltip) {
            public KvRow { tooltip = List.copyOf(tooltip); }
        }
        public Kv { rows = List.copyOf(rows); }
    }

    /** {@code selected} (Client ≥ 0.9.0, sonst null): vom Server gewählte Zeile, siehe {@link ViewState#selected(String, String)}. */
    record Table(Common common, List<Column> columns, List<Row> rows, Sort sort, int rowLines, Paging paging, SelectMode select,
                 UiText empty, String selected) implements PanelNode {
        public record Column(String id, UiText label, Size width, Align align, boolean sortable) {}
        public record Row(String key, List<Cell> cells, List<UiText> tooltip) {
            public Row { cells = List.copyOf(cells); tooltip = List.copyOf(tooltip); }
        }
        public sealed interface Cell permits TextCell, NodeCell {}
        /** {@code sort} null = nach Text sortieren. */
        public record TextCell(UiText text, Double sort) implements Cell {}
        /** {@code button}, {@code badge}, {@code item} oder eine {@code row} daraus (Plan-Entscheidung 5). */
        public record NodeCell(PanelNode node) implements Cell {}
        public record Sort(String column, boolean descending) {}
        public record Paging(int page, int pages) {}
        public Table { columns = List.copyOf(columns); rows = List.copyOf(rows); }
        @Override public List<PanelNode> nested() {
            List<PanelNode> out = new ArrayList<>();
            for (Row r : rows) for (Cell c : r.cells()) if (c instanceof NodeCell n) out.add(n.node());
            return out;
        }
        @Override public boolean interactive() { return paging != null || select != SelectMode.NONE; }
    }

    /** {@code selected} (Client ≥ 0.9.0, sonst null): vom Server gewählter Eintrag, siehe {@link ViewState#selected(String, String)}. */
    record ListView(Common common, List<Entry> items, SelectMode select, UiText empty, String selected) implements PanelNode {
        public record Entry(String key, PanelIcon icon, UiText title, UiText subtitle, UiText right, List<UiText> tooltip, UiText badge) {
            public Entry { tooltip = List.copyOf(tooltip); }
        }
        public ListView { items = List.copyOf(items); }
        @Override public boolean interactive() { return select != SelectMode.NONE; }
    }

    record Button(Common common, UiText label, PanelIcon icon, Style style, Confirm confirm, Target target) implements PanelNode {
        public enum Style { PRIMARY, DEFAULT, DANGER, GHOST }
        public record Confirm(UiText title, UiText text, UiText yes, UiText no) {}
        public sealed interface Target permits Act, Navigate, Web {}
        public record Act() implements Target {}
        public record Navigate(String panel, String arg) implements Target {}
        public record Web(WebLink link) implements Target {}
        @Override public boolean interactive() { return target instanceof Act; }
    }

    record Toggle(Common common, UiText label, UiText description, boolean value) implements PanelNode {
        @Override public boolean interactive() { return true; }
    }

    record Slider(Common common, UiText label, double min, double max, double step, double value, String format, int decimals,
                  boolean live) implements PanelNode {
        @Override public boolean interactive() { return true; }
    }

    record NumberField(Common common, UiText label, double min, double max, double step, int decimals, double value) implements PanelNode {
        @Override public boolean interactive() { return true; }
    }

    record Input(Common common, UiText label, String value, UiText placeholder, int maxLength, Chars chars, boolean submit,
                 boolean search) implements PanelNode {
        public enum Chars { ANY, NAME, INTEGER, DECIMAL }
        @Override public boolean interactive() { return true; }
    }

    record Select(Common common, UiText label, List<Option> options, String value, Style style, boolean search) implements PanelNode {
        public enum Style { DROPDOWN, SEGMENTED }
        public record Option(String id, UiText label, PanelIcon icon, List<UiText> tooltip) {
            public Option { tooltip = List.copyOf(tooltip); }
        }
        public Select { options = List.copyOf(options); }
        @Override public boolean interactive() { return true; }
    }

    /** {@code confirm} (Client ≥ 0.9.0, sonst null): vor dem SUBMIT nachfragen wie bei {@link Button}. */
    record Form(Common common, List<PanelNode> children, UiText submit, Button.Confirm confirm) implements PanelNode {
        public Form { children = List.copyOf(children); }
        @Override public List<PanelNode> nested() { return children; }
        @Override public boolean interactive() { return true; }
    }

    /**
     * Fortschrittsbalken. {@code rate} (Client ≥ 0.9.0, Einheiten pro Sekunde, auch negativ): der Client lässt den Wert ab
     * Empfang selbst weiterlaufen, begrenzt auf 0..max ({@link #valueAt}).
     */
    record Progress(Common common, double value, double max, UiText label, PanelColor color, int segments, int height, double rate)
            implements PanelNode {
        /** Wert {@code elapsedMs} nach dem Empfang. */
        public double valueAt(long elapsedMs) {
            double v = rate == 0 || elapsedMs <= 0 ? value : value + rate * (elapsedMs / 1000.0);
            return v < 0 ? 0 : v > max ? max : v;
        }
    }

    record Timer(Common common, long remainingMs, long totalMs, Format format, boolean bar) implements PanelNode {
        public enum Format { MM_SS, HH_MM_SS, RELATIVE }
    }

    /** {@code item}-Element; {@code action} = anklickbar (CLICK). */
    record ItemNode(Common common, PanelIcon icon, int size, boolean slot, PanelColor.Token rarity, boolean action) implements PanelNode {
        @Override public boolean interactive() { return action; }
    }

    record Image(Common common, Source src, int width, int height, boolean pixelated, UiText alt) implements PanelNode {
        public record Source(Kind kind, String value) {
            public enum Kind { WEB, SPRITE, BLOB }
        }
    }

    record Badge(Common common, UiText text, PanelColor color) implements PanelNode {}

    record Swatch(Common common, PanelColor color, int size) implements PanelNode {}

    record Divider(Common common, int size) implements PanelNode {}

    record Spacer(Common common, int size) implements PanelNode {}

    /** Unbekannter oder nicht vereinbarter Typ: Platzhalter „Nicht unterstützt – Mod aktualisieren“. */
    record Unsupported(Common common, String type) implements PanelNode {}

    private static List<PanelNode> concat(List<PanelNode> a, List<PanelNode> b) {
        if (b.isEmpty()) return a;
        List<PanelNode> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return List.copyOf(out);
    }
}
