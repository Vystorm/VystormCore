package Vystorm.vystorm_core.worldevent;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Ein World-Event hat begonnen. Wird immer auf dem Server-Thread ausgelöst. */
public final class WorldEventStartedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final WorldEventView event;

    public WorldEventStartedEvent(WorldEventView event) { this.event = event; }

    public WorldEventView event() { return event; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
