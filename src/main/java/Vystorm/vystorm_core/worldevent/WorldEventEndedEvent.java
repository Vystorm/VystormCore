package Vystorm.vystorm_core.worldevent;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Ein World-Event ist beendet (abgelaufen oder manuell gestoppt). Wird immer auf dem Server-Thread ausgelöst. */
public final class WorldEventEndedEvent extends Event {
    public enum Reason { EXPIRED, MANUAL }
    private static final HandlerList HANDLERS = new HandlerList();
    private final WorldEventView event;
    private final Reason reason;

    public WorldEventEndedEvent(WorldEventView event, Reason reason) { this.event = event; this.reason = reason; }

    public WorldEventView event() { return event; }
    public Reason reason() { return reason; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
