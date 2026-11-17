package Vystorm.vystorm_core.party;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Nach jeder Party-Änderung; {@code player} ist der betroffene Spieler (bei SETTING/DISBANDED ggf. null). */
public final class PartyChangeEvent extends Event {
    public enum Kind { CREATED, JOINED, LEFT, KICKED, LEADER, RENAMED, SETTING, DISBANDED }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Kind kind;private final PartyView party;private final UUID player;private final String detail;

    public PartyChangeEvent(Kind kind, PartyView party, UUID player, String detail) {
        this.kind = kind;this.party = party;this.player = player;this.detail = detail;
    }

    public Kind kind() { return kind; }
    /** Zustand nach der Änderung (bei DISBANDED der letzte Zustand). */
    public PartyView party() { return party; }
    public UUID player() { return player; }
    /** Zusatz, z. B. der Einstellungsschlüssel bei SETTING. */
    public String detail() { return detail; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
