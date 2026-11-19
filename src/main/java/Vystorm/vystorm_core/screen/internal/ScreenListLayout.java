package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Settings as a list on Vystorm Screens (0.66.0): one full-width row per entry with an optional small icon, the full name
 * on the left (wrapped to two lines, cut with "…" only at the end of the second), the current value right-aligned in its
 * own column and a dimmer one- or two-line description under the name. Headings are full-width separator rows. The
 * whole row is one button, so a click anywhere on it counts. Shared by panel sections with the list layout, the feature
 * screen and chest menus shown as a list (config editor). Pure: tested without a server.
 */
public final class ScreenListLayout {
    public static final int TEXT = 0xF2F2F2, MUTED = 0x9AA3B2, GOLD = 0xE3B04B, OK = 0x6FCF7F, BAD = 0xE06C6C;
    public static final int ROW = 0x70252A33, ROW_LIT = 0xF03A4352, HEAD = 0xC0303A48, LINE = 0xFF343B47;
    public static final int NAME_LINES = 2, DESCRIPTION_LINES = 2;
    private static final String ELLIPSIS = "…";

    private ScreenListLayout() {}

    /**
     * One row. {@code heading}: a separator row with the name in gold (value muted on the right). {@code icon}: Material
     * or ItemStack, null = none. {@code value} and {@code description} may be null. {@code clickable}: the row is a button
     * with widget id {@code id}; otherwise a plain box.
     */
    public record Row(String id, boolean heading, Object icon, Component name, Component value, Component description, boolean clickable) {
        public Row {
            name = name == null ? Component.empty() : name;
            value = empty(value) ? null : value;
            description = empty(description) ? null : description;
        }

        public static Row heading(String id, Component name, Component value, boolean clickable) {
            return new Row(id, true, null, name, value, null, clickable);
        }

        public static Row entry(String id, Object icon, Component name, Component value, Component description, boolean clickable) {
            return new Row(id, false, icon, name, value, description, clickable);
        }
    }

    /** Sizes in panel units for UI scale {@code s} (the same text sizes as panels). */
    public record Sizes(double s) {
        public double text() { return 0.042 * s; }
        public double small() { return 0.034 * s; }
        public double pad() { return 0.012 * s; }
        public double inset() { return 0.02 * s; }
        public double minRow() { return 0.066 * s; }
        public double gap() { return 0.006 * s; }
        public double icon() { return 0.044 * s; }
    }

    /** Widgets of one placed row and the height it takes (including the gap below it). */
    public record Placed(List<ScreenWidget> widgets, double height) {}

    /** Rows {@code from} (inclusive) to {@code to} (exclusive) of {@code total} fit the window. */
    public record Window(int from, int to, int total) {}

    /** Height of {@code row} at width {@code w}, including the gap below it. */
    public static double height(Row row, double w, Sizes z) { return measure(row, w, z).height; }

    private record Measure(double nameX, double nameW, double valueW, List<String> name, String value, List<String> description, double height) {}

    private static Measure measure(Row row, double w, Sizes z) {
        double inset = z.inset();
        if (row.heading()) {
            String value = row.value() == null ? null : plain(row.value());
            double valueW = value == null ? 0 : Math.min(w * 0.4, textW(value, z.small()) + z.inset());
            double nameW = Math.max(0.05, w - 2 * inset - valueW);
            List<String> name = wrap(plain(row.name()), nameW, z.text(), 1);
            return new Measure(inset, nameW, valueW, name, value == null ? null : cut(value, valueW, z.small()), List.of(),
                    z.text() + 2 * z.pad() + z.gap());
        }
        double iconW = row.icon() != null ? z.icon() + z.pad() : 0;
        double nameX = inset + iconW;
        String value = row.value() == null ? null : plain(row.value());
        double valueW = value == null ? 0 : Math.min(w * 0.42, textW(value, z.text()) + z.pad());
        double nameW = Math.max(0.05, w - nameX - inset - valueW - (valueW > 0 ? z.inset() : 0));
        List<String> name = wrap(plain(row.name()), nameW, z.text(), NAME_LINES);
        // the description runs under the name and under the value column (the value sits on the first line)
        double descriptionW = Math.max(0.05, w - nameX - inset);
        List<String> description = row.description() == null ? List.of() : wrap(plain(row.description()), descriptionW, z.small(), DESCRIPTION_LINES);
        double h = Math.max(z.minRow(), 2 * z.pad() + name.size() * z.text() + description.size() * z.small());
        return new Measure(nameX, nameW, valueW, name, value == null ? null : cut(value, valueW, z.text()), description, h + z.gap());
    }

    /** Lays {@code row} out at {@code (x, y)} with width {@code w}. Sub-widget ids are {@code id + ".n0"}, {@code ".v"}, {@code ".d0"} … */
    public static Placed place(Row row, double x, double y, double w, Sizes z) {
        Measure m = measure(row, w, z);
        double h = m.height - z.gap();
        List<ScreenWidget> out = new ArrayList<>();
        String id = row.id();
        if (row.clickable()) {
            out.add(new ScreenWidget(id, ScreenWidget.Kind.BUTTON, x, y, w, h, "", true).withFill(row.heading() ? HEAD : ROW).withHighlight(ROW_LIT));
        } else {
            out.add(ScreenWidget.fill(id, x, y, w, h, row.heading() ? HEAD : ROW));
        }
        if (row.heading()) {
            // not bold: the runtime places text by its estimated width, and bold glyphs are wider than that estimate
            Component name = Component.text(m.name.isEmpty() ? "" : m.name.getFirst()).color(TextColor.color(GOLD));
            out.add(label(id + ".n0", x + m.nameX, y + z.pad(), m.nameW, z.text(), name, ScreenWidget.Align.START));
            if (m.value != null) out.add(label(id + ".v", x + w - z.inset() - m.valueW, y + z.pad() + (z.text() - z.small()) / 2, m.valueW, z.small(),
                    Component.text(m.value).color(color(row.value(), MUTED)), ScreenWidget.Align.END));
            out.add(ScreenWidget.fill(id + ".line", x, y + h - 0.003 * z.s(), w, 0.003 * z.s(), LINE));
            return new Placed(out, m.height);
        }
        if (row.icon() != null) {
            double size = z.icon();
            out.add(ScreenWidget.icon(id + ".i", x + z.inset(), y + z.pad() + (z.text() - size) / 2, size, size, row.icon()));
        }
        TextColor nameColor = row.clickable() ? color(row.name(), TEXT) : TextColor.color(MUTED);
        for (int i = 0; i < m.name.size(); i++) {
            out.add(label(id + ".n" + i, x + m.nameX, y + z.pad() + i * z.text(), m.nameW, z.text(),
                    Component.text(m.name.get(i)).color(nameColor), ScreenWidget.Align.START));
        }
        if (m.value != null) {
            out.add(label(id + ".v", x + w - z.inset() - m.valueW, y + z.pad(), m.valueW, z.text(),
                    Component.text(m.value).color(color(row.value(), GOLD)), ScreenWidget.Align.END));
        }
        double dy = y + z.pad() + m.name.size() * z.text();
        for (int i = 0; i < m.description.size(); i++) {
            out.add(label(id + ".d" + i, x + m.nameX, dy + i * z.small(), w - m.nameX - z.inset(), z.small(),
                    Component.text(m.description.get(i)).color(TextColor.color(MUTED)), ScreenWidget.Align.START));
        }
        return new Placed(out, m.height);
    }

    /** The rows from {@code offset} that fit into {@code height}; at least one row when there is any. */
    public static Window window(List<Row> rows, int offset, double w, double height, Sizes z) {
        int from = Math.max(0, Math.min(offset, Math.max(0, rows.size() - 1)));
        double used = 0;
        int to = from;
        while (to < rows.size()) {
            double h = height(rows.get(to), w, z);
            if (to > from && used + h - z.gap() > height + 1e-9) break;
            used += h;
            to++;
        }
        return new Window(from, to, rows.size());
    }

    /** The largest offset that still fills the window (the last row at the bottom). */
    public static int maxOffset(List<Row> rows, double w, double height, Sizes z) {
        double used = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            used += height(rows.get(i), w, z);
            if (used - z.gap() > height + 1e-9) return Math.min(rows.size() - 1, i + 1);
        }
        return 0;
    }

    /**
     * Position of a window counted in entries only (headings left out): {first, last, total}, 1-based; {0, 0, total}
     * when no entry is in the window.
     */
    public static int[] position(List<Row> rows, Window window) {
        int total = 0, first = 0, last = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).heading()) continue;
            total++;
            if (i >= window.from() && i < window.to()) {
                if (first == 0) first = total;
                last = total;
            }
        }
        return new int[] { first, last, total };
    }

    // ------------------------------------------------------------------ text

    /** Width of {@code text} in panel units at line height {@code h} (the estimate the runtime uses to fit text). */
    public static double textW(String text, double h) {
        return ScreenRuntime.textWidth(text) * (1.0 / 40) * (h / 0.25 * 0.8);
    }

    /**
     * Word wrap of {@code text} to width {@code w} in at most {@code maxLines} lines; words longer than a line are cut by
     * characters, and when text is left over the last line ends with "…". Never returns an empty list for non-blank text.
     */
    public static List<String> wrap(String text, double w, double h, int maxLines) {
        String plain = text == null ? "" : text.strip().replace('\n', ' ');
        List<String> out = new ArrayList<>();
        if (plain.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        String[] words = plain.split(" +");
        int i = 0;
        while (i < words.length) {
            String word = words[i];
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (textW(candidate, h) <= w) { line = new StringBuilder(candidate); i++; continue; }
            if (line.isEmpty()) {   // one word wider than the line: cut it
                int n = fitting(word, w, h);
                out.add(word.substring(0, n));
                words[i] = word.substring(n);
            } else {
                out.add(line.toString());
                line = new StringBuilder();
            }
            if (out.size() == maxLines) {
                StringBuilder rest = new StringBuilder(line);
                for (int j = i; j < words.length; j++) rest.append(rest.isEmpty() ? "" : " ").append(words[j]);
                if (!rest.isEmpty()) out.set(maxLines - 1, cut(out.get(maxLines - 1) + " " + rest, w, h));
                return out;
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    /** {@code text} cut to width {@code w} with "…" at the end when it does not fit. */
    public static String cut(String text, double w, double h) {
        if (textW(text, h) <= w) return text;
        String base = text;
        while (!base.isEmpty() && textW(base.stripTrailing() + ELLIPSIS, h) > w) base = base.substring(0, base.length() - 1);
        return base.stripTrailing() + ELLIPSIS;
    }

    private static int fitting(String word, double w, double h) {
        int n = word.length();
        while (n > 1 && textW(word.substring(0, n), h) > w) n--;
        return Math.max(1, n);
    }

    static String plain(Component c) { return c == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c); }

    private static boolean empty(Component c) { return c == null || plain(c).isBlank(); }

    /** The first colour in {@code c} (own or first child's), else {@code fallback}. */
    static TextColor color(Component c, int fallback) {
        if (c != null) {
            if (c.color() != null) return c.color();
            for (Component child : c.children()) if (child.color() != null) return child.color();
        }
        return TextColor.color(fallback);
    }

    private static ScreenWidget label(String id, double x, double y, double w, double h, Component text, ScreenWidget.Align align) {
        return ScreenWidget.label(id, x, y, Math.max(0.01, w), h, text).withAlign(align);
    }
}
