package Vystorm.vystorm_core.worlds.internal;

/**
 * Was beim Join (und bei {@code /vworlds restore}) mit einem Spieler ohne laufenden Besuch passiert. Grundlage ist,
 * <em>welchen</em> Stand die geladenen Spielerdaten haben: Der Besuchs-Marker steckt im PDC des Spielers und wird im
 * selben Tick gesetzt/entfernt wie das Inventar, also mit derselben Speicherung der Spielerdaten auf die Platte.
 *
 * <ul>
 *   <li>{@link #RESTORE}: Schnappschuss liegt vor und die Spielerdaten sind im Besuchsstand (Marker, Werkzeug, in einer
 *       Plugin-Welt oder beim Login aus einer umgeleitet) → erst hinaus, dann zurückspielen.</li>
 *   <li>{@link #DISCARD}: Schnappschuss liegt vor, die Spielerdaten sind aber schon draußen (Rückgabe gelang, nur das
 *       Löschen fehlte; oder Absturz vor der ersten Speicherung im Besuch) → Schnappschuss löschen, nichts zurückspielen
 *       (sonst doppelte Items, Review C2).</li>
 *   <li>{@link #EVICT}: kein Schnappschuss, aber Besuchsstand → Werkzeug weg, Marker weg, hinaus (Schnappschuss
 *       verloren/kaputt; wird laut geloggt).</li>
 *   <li>{@link #NOTHING}: normaler Spieler.</li>
 * </ul>
 */
public enum RecoveryDecision {
    RESTORE, DISCARD, EVICT, NOTHING,
    /** Wiederherstellung noch offen: Teleport, Dekodierung oder dauerhaftes Speichern fehlgeschlagen. */
    PENDING;

    public static RecoveryDecision decide(boolean snapshot, boolean marker, boolean tools, boolean inManagedWorld, boolean redirected) {
        boolean visitState = marker || tools || inManagedWorld || redirected;
        if (snapshot) return visitState ? RESTORE : DISCARD;
        return marker || tools || inManagedWorld ? EVICT : NOTHING;
    }
}
