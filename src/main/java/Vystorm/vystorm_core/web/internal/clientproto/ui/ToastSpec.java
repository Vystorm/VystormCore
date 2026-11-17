// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, ToastSpec.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.util.Locale;

/**
 * Einmalige Benachrichtigung oben rechts. {@code action} (Client ≥ 0.9.0, sonst null): eine Aktion, die der Spieler mit der
 * Taste „Benachrichtigung annehmen“ auslöst, solange der Toast sichtbar ist (höchstens {@link #actionWindowMs()} ab Empfang).
 */
public record ToastSpec(UiText title, UiText text, Icon icon, int durationMs, Style style, Action action) {

    /** Toast ohne Aktion (wie vor 0.9.0). */
    public ToastSpec(UiText title, UiText text, Icon icon, int durationMs, Style style) {
        this(title, text, icon, durationMs, style, null);
    }

    /** Aktion eines Toasts: vom Server vergebene ID (Regeln wie Element-IDs) und Beschriftung (leer = Client-Standard). */
    public record Action(String id, UiText label) {}

    /** Zusätzliche Zeit nach {@code 3 × durationMs}, in der eine Aktion noch angenommen wird (Warteschlange, Netz). */
    public static final long ACTION_GRACE_MS = 10_000L;

    /**
     * Wie lange nach dem Empfang der Client die Aktion anbietet: {@code 3 × durationMs + 10 s} (deckt die
     * Vanilla-Einstellung „Benachrichtigungsdauer“ bis 3× und Wartezeit in der Toast-Schlange ab). Der Server nimmt
     * sie bis {@link #serverActionWindowMs(int)} an.
     */
    public long actionWindowMs() { return 3L * durationMs + ACTION_GRACE_MS; }

    /** Annahmefenster des Servers ab Senden: Client-Fenster + 5 s für Netz und Bündelung. */
    public static long serverActionWindowMs(int durationMs) { return 3L * durationMs + ACTION_GRACE_MS + 5_000L; }

    public enum Style {
        INFO(0xFF5B8DEF), SUCCESS(0xFF55C46A), WARNING(0xFFE3B341), ERROR(0xFFE05555);

        private final int accent;

        Style(int accent) { this.accent = accent; }

        public int accent() { return accent; }

        public static Style parse(String name) {
            if (name == null) return INFO;
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }
}
