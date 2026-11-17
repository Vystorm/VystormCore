package Vystorm.vystorm_core.panel;

/** Was {@code Panels.open} getan hat (Spec §7). @since 0.25.0 */
public enum PanelOpenResult {
    /** Natives Panel im Client-Mod. */
    NATIVE,
    /** Automatischer Paper-Dialog aus der Panel-Beschreibung. */
    DIALOG,
    /** Web-Link im Chat bzw. Systembrowser ({@code WebServices.openOrLink}). */
    WEB,
    /** Das eigene {@code fallback} des Plugins (Kisten-/Dialogmenü). */
    FALLBACK,
    /** Nichts möglich (kein Recht, kein Weg); der Spieler bekam Cores Hinweis. */
    NONE
}
