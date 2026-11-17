// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, UiElement.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.util.List;
import java.util.UUID;

/**
 * Ein vom Server beschriebenes, geprüftes natives Element. Unveränderlich; der Client zeichnet nur, was hier steht.
 * Koordinaten und Größen sind GUI-Pixel (skalieren mit der GUI-Skalierung).
 */
public sealed interface UiElement {

    String id();

    /** Typname im JSON ({@code panel}, {@code bar}, ...). */
    String type();

    /** Lebensdauer ab Empfang in ms, 0 = unbegrenzt. */
    long ttlMs();

    /** HUD-Elemente (panel, bar, slots, list) teilen sich ein Limit und werden nach {@link Hud#order()} gezeichnet. */
    sealed interface Hud extends UiElement {
        Anchor anchor();
        int x();
        int y();
        int order();

        /**
         * Client ≥ 0.9.0: mit anderen {@code stack}-Elementen desselben Ankers ohne Überlappung stapeln (siehe
         * {@code HudLayout}). Standard false (Position genau wie angegeben, wie bisher).
         */
        boolean stack();
    }

    /** Kasten mit Titel und Zeilen, z. B. Quest-Tracker oder Charakterwerte. */
    record Panel(String id, Anchor anchor, int x, int y, int order, boolean stack, long ttlMs, int width, int padding, int background,
                 int border, int textColor, UiText title, List<UiText> lines, Icon icon) implements Hud {
        public Panel { lines = List.copyOf(lines); }
        @Override public String type() { return "panel"; }
    }

    /**
     * Fortschrittsbalken, z. B. Charakterlevel-XP. {@code value}/{@code max} beliebig skaliert; {@code segments} > 1
     * zeichnet Trennstriche wie die Vanilla-XP-Leiste. {@code rate} (Client ≥ 0.9.0, Einheiten pro Sekunde, auch
     * negativ) lässt den Client den Wert zwischen zwei PATCHes selbst weiterlaufen ({@link #valueAt}).
     */
    record Bar(String id, Anchor anchor, int x, int y, int order, boolean stack, long ttlMs, int width, int height, double value,
               double max, double rate, int color, int background, int textColor, UiText label, int segments) implements Hud {
        @Override public String type() { return "bar"; }

        /** Füllstand 0..1 beim Empfang. */
        public double fraction() { return fractionAt(0); }

        /** Wert {@code elapsedMs} nach dem Empfang: {@code value + rate × Sekunden}, begrenzt auf 0..max. */
        public double valueAt(long elapsedMs) {
            if (rate == 0 || elapsedMs <= 0) return Math.max(0, Math.min(max, value));
            double v = value + rate * (elapsedMs / 1000.0);
            return v < 0 ? 0 : v > max ? max : v;
        }

        /** Füllstand 0..1 {@code elapsedMs} nach dem Empfang. */
        public double fractionAt(long elapsedMs) {
            if (!(max > 0)) return 0;
            double f = valueAt(elapsedMs) / max;
            return f < 0 ? 0 : f > 1 ? 1 : f;
        }

        /** Ob {@code label} Platzhalter {@code {value}}, {@code {max}} oder {@code {time}} enthält (Client ≥ 0.9.0). */
        public boolean dynamicLabel() { return hasValueTokens(label); }

        /** Ob {@code text} {@code {value}}, {@code {max}} oder {@code {time}} enthält. */
        public static boolean hasValueTokens(UiText text) {
            for (UiText.Segment s : text.segments()) {
                String t = s.text();
                if (t.contains("{value}") || t.contains("{max}") || t.contains("{time}")) return true;
            }
            return false;
        }

        /** {@code {value}}/{@code {max}}/{@code {time}} in {@code text} mit dem Wert {@code v} von {@code max} füllen. */
        public static UiText fillValueTokens(UiText text, double v, double max) {
            String value = Long.toString(Math.round(v));
            String maxText = Long.toString(Math.round(max));
            String time = clock((long) Math.ceil(v - 1e-9));
            java.util.List<UiText.Segment> out = new java.util.ArrayList<>(text.segments().size());
            for (UiText.Segment s : text.segments()) {
                String t = s.text().replace("{value}", value).replace("{max}", maxText).replace("{time}", time);
                out.add(new UiText.Segment(t, s.color(), s.bold(), s.italic(), s.underlined(), s.strikethrough()));
            }
            return new UiText(out);
        }

        /** Cache-Schlüssel der angezeigten Zahlen eines Werts. */
        public static long valueKey(double v) { return Math.round(v) * 31 + (long) Math.ceil(v - 1e-9); }

        /**
         * Beschriftung {@code elapsedMs} nach dem Empfang: {@code {value}} = aktueller Wert gerundet, {@code {max}} =
         * Maximum gerundet, {@code {time}} = aktueller Wert als Sekunden (aufgerundet) im Format {@code m:ss} bzw.
         * {@code h:mm:ss}. Ohne Platzhalter das Original.
         */
        public UiText labelAt(long elapsedMs) {
            if (!dynamicLabel()) return label;
            return fillValueTokens(label, valueAt(elapsedMs), max);
        }

        /** Schlüssel, der sich ändert, sobald sich die angezeigte Beschriftung ändern kann (Cache im Client). */
        public long labelKey(long elapsedMs) {
            return valueKey(valueAt(elapsedMs));
        }

        static String clock(long seconds) {
            long s = Math.max(0, seconds);
            if (s >= 3600) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
            return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }

        /** Ob Felder von Client 0.9.0 nötig sind, ohne die der Balken falsch aussähe (Platzhalter in {@code label}). */
        public boolean needsExtensions() { return dynamicLabel(); }
    }

    /** Reihe von Fähigkeiten-Slots mit Symbol, Taste, Anzahl und Abklingzeit. */
    record Slots(String id, Anchor anchor, int x, int y, int order, boolean stack, long ttlMs, int size, int gap, List<Slot> slots)
            implements Hud {
        public Slots { slots = List.copyOf(slots); }
        @Override public String type() { return "slots"; }
    }

    /**
     * Ein Slot. {@code cooldownMs} ist die Restzeit beim Empfang, {@code cooldownTotalMs} die volle Dauer (für den
     * Anteil); der Client zählt selbst herunter.
     */
    record Slot(Icon icon, String key, int count, long cooldownMs, long cooldownTotalMs, boolean active, UiText label) {}

    /**
     * HUD-Liste (Client ≥ 0.9.0): Kasten mit optionalem Titel und bis zu 12 Zeilen aus Symbol, Beschriftung, rechtem Text
     * und kleinem Balken – z. B. Quest-Ziele, Gruppenmitglieder, Ressourcen. PATCH ersetzt {@code rows} als Ganzes.
     */
    record HudList(String id, Anchor anchor, int x, int y, int order, boolean stack, long ttlMs, int width, int padding, int background,
                   int border, int textColor, int rightColor, UiText title, List<ListRow> rows) implements Hud {
        public HudList { rows = List.copyOf(rows); }
        @Override public String type() { return "list"; }
    }

    /**
     * Zeile einer {@link HudList}; {@code icon}, {@code right} und {@code bar} optional (null/leer). Mit Balken darf
     * {@code right} {@code {value}}, {@code {max}} und {@code {time}} enthalten (Wert des Balkens, läuft mit
     * {@code bar.rate} mit, z. B. Restzeit eines Weltereignisses); ohne Balken bleiben sie Text.
     */
    record ListRow(Icon icon, UiText label, UiText right, RowBar bar) {
        /** Ob {@code right} mit dem Balkenwert gefüllt wird. */
        public boolean dynamicRight() { return bar != null && Bar.hasValueTokens(right); }

        /** Rechter Text {@code elapsedMs} nach Empfang. */
        public UiText rightAt(long elapsedMs) {
            return dynamicRight() ? Bar.fillValueTokens(right, bar.valueAt(elapsedMs), bar.max()) : right;
        }
    }

    /**
     * Kleiner Balken unter einer Listenzeile. {@code rate} (Einheiten/s, auch negativ) lässt den Wert ab Empfang
     * weiterlaufen wie {@link Bar#rate()} (0..max); jeder PUT/PATCH der Liste setzt Wert und Start neu.
     */
    record RowBar(double value, double max, int color, double rate) {
        public RowBar(double value, double max, int color) { this(value, max, color, 0); }

        /** Wert {@code elapsedMs} nach dem Empfang, begrenzt auf 0..max. */
        public double valueAt(long elapsedMs) {
            double v = rate == 0 || elapsedMs <= 0 ? value : value + rate * (elapsedMs / 1000.0);
            return v < 0 ? 0 : v > max ? max : v;
        }

        /** Füllstand 0..1 beim Empfang. */
        public double fraction() { return fractionAt(0); }

        /** Füllstand 0..1 {@code elapsedMs} nach dem Empfang. */
        public double fractionAt(long elapsedMs) {
            if (!(max > 0)) return 0;
            double f = valueAt(elapsedMs) / max;
            return f < 0 ? 0 : f > 1 ? 1 : f;
        }
    }

    /**
     * Markierung an einer Weltposition: Beschriftung über dem Punkt, am Bildschirmrand, wenn außerhalb der Sicht.
     * {@code world} gesetzt = nur in dieser Dimension. Client ≥ 0.9.0: {@code entity} (UUID) folgt der Entity, solange
     * sie beim Client geladen ist (Oberkante + {@code yOffset}), sonst gilt {@code x,y,z} als letzte bekannte Position;
     * {@code ground} setzt die Position auf die Geländehöhe bei {@code x/z} (+ {@code yOffset}), wenn der Chunk geladen
     * ist.
     */
    record Marker(String id, long ttlMs, String world, double x, double y, double z, UiText label, int color, Icon icon,
                  double maxDistance, boolean showDistance, boolean clampToScreen, UUID entity, double yOffset, boolean ground)
            implements UiElement {
        @Override public String type() { return "marker"; }
    }

    /** Art des {@code customData}-Vergleichs einer Tooltip-Regel. */
    enum DataMatch {
        /** String-Tag gleich {@code equals} (seit Client 0.1). */
        EQUALS,
        /** Tag vorhanden, beliebiger Typ ({@code present: true}, Client ≥ 0.9.0). */
        PRESENT,
        /** String-Tag gleich der UUID des betrachtenden Spielers ({@code equalsViewer: true}, Client ≥ 0.9.0). */
        VIEWER
    }

    /** Eine {@code customData}-Bedingung: Pfad, Art und (nur bei {@link DataMatch#EQUALS}) der erwartete String. */
    record DataCondition(String path, DataMatch match, String equals) {}

    /**
     * Zusätzliche Tooltip-Zeilen für passende Items: {@code item} (Item-ID) und/oder Bedingungen an Werte in
     * {@code minecraft:custom_data} (z. B. {@code PublicBukkitValues.vystorm_items:id}); alle Bedingungen müssen passen
     * (UND; mehr als eine erst ab Client 0.9.0, höchstens {@value UiLimits#MAX_DATA_CONDITIONS}). {@code dynamic}:
     * mindestens eine Zeile enthält Platzhalter {@code {data:pfad}} (Client ≥ 0.9.0, aufgelöst mit {@link TooltipData}).
     */
    record Tooltip(String id, long ttlMs, String item, List<DataCondition> conditions, List<UiText> lines, boolean dynamic)
            implements UiElement {
        public Tooltip {
            conditions = List.copyOf(conditions);
            lines = List.copyOf(lines);
        }
        @Override public String type() { return "tooltip"; }

        /** Ob Felder von Client 0.9.0 nötig sind (Platzhalter, andere Bedingungen als {@code equals}, mehrere Bedingungen). */
        public boolean needsExtensions() {
            if (dynamic || conditions.size() > 1) return true;
            for (DataCondition c : conditions) if (c.match() != DataMatch.EQUALS) return true;
            return false;
        }
    }
}
