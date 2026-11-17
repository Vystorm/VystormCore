package Vystorm.vystorm_core.worlds;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Besuche in Plugin-Welten: Core sichert den vollen Spielerzustand auf der Platte, bevor er etwas ändert, und gibt ihn
 * beim Verlassen, beim Disconnect, nach einem Absturz und nach fremden Teleports zurück. Ablauf beim Verlassen: erst
 * Teleport hinaus, dann Wiederherstellung; ein freiwilliger fehlgeschlagener Austritt bleibt aktiv. Erzwungene
 * Austritte neutralisieren bei Fehlern Online-Besucher und behalten Marker/Schnappschuss. Items, die ein Spieler
 * während des Besuchs bekommen hat (Admin-{@code /give}, Zustellungen), behält er.
 *
 * <p>Server-Thread, außer {@link #inVisit} und {@link #hasSnapshot} (threadsicher).</p>
 *
 * @since 0.26.0
 */
public interface VisitService {
    /**
     * Requests entry: closes the inventory normally, captures/serializes on the server thread, then writes and
     * fsyncs on one background writer. {@link VisitResult#OK} means accepted, not admitted: no visit exists until
     * the durable write completes and server-thread validation confirms unchanged items (including armor/offhand),
     * empty cursor/crafting, total XP/level/progress and gamemode (world/position also checked).
     * Changes to these values, quit/death, or a 2.5-second deadline abort without applying visit state. Normal inventory-close
     * handling may return cursor/crafting items or drop overflow. Duplicate requests while writing return ALREADY.
     * Only after validation does Core set the marker, prepare inventory, call entering, and teleport.
     * Since 0.26.3, ticking scalars may drift: the durable snapshot keeps submit-time effects, health, food, air, etc.,
     * so restoring can bring back values/effect durations a few seconds older than admission.
     * Since 0.26.2; discover with the async-durable-visits capability.
     */
    VisitResult enter(Player player, String worldId, org.bukkit.Location arrival);

    /** Beendet den Besuch; {@code false}, wenn keiner läuft. Bei {@link ExitReason#QUIT}/{@code SHUTDOWN} synchron. */
    boolean exit(Player player, ExitReason reason);

    boolean inVisit(UUID player);

    Optional<Visit> visit(UUID player);

    Collection<Visit> visits(String worldId);

    /** Ob ein Schnappschuss auf der Platte liegt (Wiederherstellung offen oder Besuch läuft). */
    boolean hasSnapshot(UUID player);

    /** Ob das Item ein Besuchs-Werkzeug ist (egal welcher Welt). */
    boolean isTool(ItemStack stack);

    /** Gibt die Werkzeuge der Welt neu (z. B. nach Sprachwechsel). */
    void regiveTools(Player player);

    /** Sichtbarkeit der Besucher einer Welt neu berechnen. */
    void refreshVisibility(String worldId);

    /**
     * Admin-Ausweg: beendet einen laufenden Besuch ({@link ExitReason#ADMIN}) oder spielt einen offenen Schnappschuss
     * zurück. Rückgabecodes: {@code visit-ended}, {@code restored}, {@code discarded}, {@code evicted},
     * {@code nothing}; {@code pending}, wenn Teleport, Dekodierung oder dauerhaftes Speichern noch nicht gelang.
     */
    @Deprecated(since = "0.26.1")
    String restore(Player player);

    /** Typed recovery result; default preserves existing implementations. Server thread. @since 0.26.1 */
    default RestoreOutcome restoreOutcome(Player player) { return RestoreOutcome.fromCode(restore(player)); }
}
