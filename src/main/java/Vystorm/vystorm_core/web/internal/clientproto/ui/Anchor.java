// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, Anchor.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.util.Locale;

/** Bezugspunkt eines HUD-Elements auf dem Bildschirm; {@code x}/{@code y} verschieben davon ausgehend. */
public enum Anchor {
    TOP_LEFT(0, 0), TOP(1, 0), TOP_RIGHT(2, 0),
    LEFT(0, 1), CENTER(1, 1), RIGHT(2, 1),
    BOTTOM_LEFT(0, 2), BOTTOM(1, 2), BOTTOM_RIGHT(2, 2);

    private final int col;
    private final int row;

    Anchor(int col, int row) {
        this.col = col;
        this.row = row;
    }

    /** {@code top_left}, {@code bottom} usw.; null bei unbekanntem Namen. */
    public static Anchor parse(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Linke Kante bei Bildschirmbreite {@code screen} und Elementbreite {@code size}. */
    public int left(int screen, int size, int offset) {
        return switch (col) {
            case 0 -> offset;
            case 1 -> (screen - size) / 2 + offset;
            default -> screen - size - offset;
        };
    }

    /** Obere Kante bei Bildschirmhöhe {@code screen} und Elementhöhe {@code size}. */
    public int top(int screen, int size, int offset) {
        return switch (row) {
            case 0 -> offset;
            case 1 -> (screen - size) / 2 + offset;
            default -> screen - size - offset;
        };
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }
}
