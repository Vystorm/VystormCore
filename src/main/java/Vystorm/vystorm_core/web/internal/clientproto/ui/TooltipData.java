// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, TooltipData.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pfade in {@code minecraft:custom_data}, Platzhalter {@code {data:pfad}} in Tooltip-Zeilen (Client ≥ 0.9.0) und die
 * Auswertung einer Tooltip-Regel gegen die Daten eines Items. Rein und ohne Minecraft-Klassen: der Client liefert die
 * Daten über {@link DataView}.
 *
 * <ul>
 *   <li><b>Pfad</b>: 1..4 Segmente {@code [A-Za-z0-9_:-]{1,64}}, durch {@code .} getrennt, ≤ 128 Zeichen, z. B.
 *       {@code PublicBukkitValues.vystorm_items:quality}.</li>
 *   <li><b>Platzhalter</b>: {@code {data:<pfad>}} in einem Textabschnitt. Ein {@code {data:} ohne gültigen Pfad und
 *       schließendes {@code }} ist ein Fehler; andere geschweifte Klammern sind normaler Text. Höchstens
 *       {@value UiLimits#MAX_PLACEHOLDERS_PER_LINE} je Zeile und {@value UiLimits#MAX_PLACEHOLDERS_PER_RULE} je Regel.</li>
 *   <li><b>Wert</b>: String-Tags wörtlich, Zahlen-Tags als Zahl (ganze ohne Nachkommastellen, sonst höchstens 2); andere
 *       Tags gelten als fehlend. Bereinigt wie jeder Text ({@link UiText#sanitize}), höchstens
 *       {@value UiLimits#MAX_PLACEHOLDER_VALUE_CHARS} Zeichen.</li>
 *   <li><b>Fehlende Werte</b>: ein fehlender Platzhalter wird zu leerem Text; eine Zeile, deren Platzhalter
 *       <em>alle</em> fehlen, entfällt.</li>
 * </ul>
 */
public final class TooltipData {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_:-]{1,64}");
    private static final String OPEN = "{data:";

    private TooltipData() {}

    /** Zugriff auf {@code minecraft:custom_data} eines Items (Client). Alle Methoden: null/false, wenn nicht vorhanden. */
    public interface DataView {
        /** Tag am Pfad vorhanden (beliebiger Typ). */
        boolean present(String path);

        /** Wert eines String-Tags, sonst null. */
        String string(String path);

        /** String- oder Zahlen-Tag als Text ({@link #formatNumber}), sonst null. */
        String scalar(String path);
    }

    /** Ob {@code path} ein gültiger Datenpfad ist. */
    public static boolean validPath(String path) {
        if (path == null || path.isEmpty() || path.length() > UiLimits.MAX_DATA_PATH) return false;
        String[] segments = path.split("\\.", -1);
        if (segments.length > 4) return false;
        for (String s : segments) if (!SEGMENT.matcher(s).matches()) return false;
        return true;
    }

    /**
     * Pfade aller Platzhalter einer Zeile in Reihenfolge.
     *
     * @throws IllegalArgumentException bei einem kaputten Platzhalter oder zu vielen
     */
    public static List<String> placeholders(UiText line) {
        List<String> out = new ArrayList<>(0);
        for (UiText.Segment s : line.segments()) scan(s.text(), out);
        if (out.size() > UiLimits.MAX_PLACEHOLDERS_PER_LINE) throw new IllegalArgumentException("too many placeholders in a line");
        return out;
    }

    private static void scan(String text, List<String> out) {
        int from = 0;
        int at;
        while ((at = text.indexOf(OPEN, from)) >= 0) {
            int end = text.indexOf('}', at + OPEN.length());
            if (end < 0) throw new IllegalArgumentException("placeholder not closed");
            String path = text.substring(at + OPEN.length(), end);
            if (!validPath(path)) throw new IllegalArgumentException("placeholder path invalid");
            out.add(path);
            if (out.size() > UiLimits.MAX_PLACEHOLDERS_PER_RULE) throw new IllegalArgumentException("too many placeholders");
            from = end + 1;
        }
    }

    /**
     * Ob die Regel auf ein Item passt. {@code itemId} z. B. {@code minecraft:diamond_sword}; {@code data} null = Item ohne
     * {@code custom_data}; {@code viewerUuid} die UUID des Spielers (z. B. {@code 0f…-…}), null = unbekannt.
     */
    public static boolean matches(UiElement.Tooltip rule, String itemId, DataView data, String viewerUuid) {
        if (rule.item() != null && !rule.item().equals(itemId)) return false;
        if (rule.conditions().isEmpty()) return true;
        if (data == null) return false;
        for (UiElement.DataCondition c : rule.conditions()) if (!matches(c, data, viewerUuid)) return false;
        return true;
    }

    /** Eine Bedingung gegen die Daten eines Items. */
    public static boolean matches(UiElement.DataCondition c, DataView data, String viewerUuid) {
        return switch (c.match()) {
            case EQUALS -> c.equals().equals(data.string(c.path()));
            case PRESENT -> data.present(c.path());
            case VIEWER -> {
                String v = data.string(c.path());
                yield viewerUuid != null && v != null && v.equalsIgnoreCase(viewerUuid);
            }
        };
    }

    /**
     * Zeilen einer (passenden) Regel mit eingesetzten Platzhaltern. Zeilen ohne Platzhalter bleiben, wie sie sind;
     * Zeilen, deren Platzhalter alle fehlen, entfallen.
     */
    public static List<UiText> lines(UiElement.Tooltip rule, DataView data) {
        if (!rule.dynamic()) return rule.lines();
        List<UiText> out = new ArrayList<>(rule.lines().size());
        for (UiText line : rule.lines()) {
            UiText resolved = resolve(line, data);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }

    /** Eine Zeile auflösen; null, wenn sie Platzhalter hat und alle fehlen. */
    public static UiText resolve(UiText line, DataView data) {
        boolean any = false;
        boolean found = false;
        List<UiText.Segment> segments = new ArrayList<>(line.segments().size());
        for (UiText.Segment s : line.segments()) {
            String text = s.text();
            if (text.indexOf(OPEN) < 0) {
                segments.add(s);
                continue;
            }
            StringBuilder sb = new StringBuilder(text.length());
            int from = 0;
            int at;
            while ((at = text.indexOf(OPEN, from)) >= 0) {
                int end = text.indexOf('}', at + OPEN.length());
                if (end < 0) break;   // vom Schema ausgeschlossen
                sb.append(text, from, at);
                any = true;
                String value = data == null ? null : data.scalar(text.substring(at + OPEN.length(), end));
                if (value != null) {
                    found = true;
                    sb.append(clean(value));
                }
                from = end + 1;
            }
            sb.append(text, from, text.length());
            segments.add(new UiText.Segment(sb.toString(), s.color(), s.bold(), s.italic(), s.underlined(), s.strikethrough()));
        }
        if (any && !found) return null;
        return any ? new UiText(segments) : line;
    }

    private static String clean(String value) {
        String v = UiText.sanitize(value);
        int max = UiLimits.MAX_PLACEHOLDER_VALUE_CHARS;
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }

    /** Zahl als Text: ganze Zahlen ohne Nachkommastellen, sonst höchstens 2 (ohne Nullen am Ende), nie Exponent. */
    public static String formatNumber(Number n) {
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) return Long.toString(n.longValue());
        double d = n.doubleValue();
        if (!Double.isFinite(d)) return null;
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        BigDecimal b = BigDecimal.valueOf(d).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return b.toPlainString();
    }
}
