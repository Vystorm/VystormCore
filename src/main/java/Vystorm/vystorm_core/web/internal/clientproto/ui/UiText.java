// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, UiText.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.util.List;

/**
 * Formatierter Text ohne Klick-/Hover-Aktionen: eine Liste von Abschnitten mit Farbe und Stil. Steuerzeichen,
 * Bidi-Steuerzeichen und {@code §}-Codes werden beim Einlesen entfernt.
 */
public record UiText(List<Segment> segments) {

    /** Ein Abschnitt; {@code color} null = Standardfarbe des Elements. */
    public record Segment(String text, Integer color, boolean bold, boolean italic, boolean underlined, boolean strikethrough) {}

    public static final UiText EMPTY = new UiText(List.of());

    public UiText {
        segments = List.copyOf(segments);
    }

    public static UiText plain(String text) {
        return new UiText(List.of(new Segment(text, null, false, false, false, false)));
    }

    public boolean isEmpty() {
        for (Segment s : segments) if (!s.text().isEmpty()) return false;
        return true;
    }

    /** Reiner Text ohne Formatierung. */
    public String plainText() {
        StringBuilder out = new StringBuilder();
        for (Segment s : segments) out.append(s.text());
        return out.toString();
    }

    /**
     * Entfernt Steuerzeichen (C0/C1), {@code §}, alle Unicode-Formatzeichen (Kategorie Cf: Bidi-Steuerung,
     * Nullbreite, BOM, weiche Trennung …) sowie Zeilen-/Absatztrenner. So lassen sich keine unsichtbaren Zeichen oder
     * Richtungswechsel in Texte schmuggeln.
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || (c >= 0x7F && c < 0xA0)) continue;
            if (c == '§') continue;
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) continue;
            out.append(c);
        }
        return out.toString();
    }
}
