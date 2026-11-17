package Vystorm.vystorm_core.pack;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Pack-Status eines Spielers hat sich geändert. Server-Thread. */
public class PackStateChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final PackState previous;
    private final PackState state;

    public PackStateChangeEvent(Player player, PackState previous, PackState state) {
        this.player = player;
        this.previous = previous;
        this.state = state;
    }

    public Player player() { return player; }
    public PackState previous() { return previous; }
    public PackState state() { return state; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
