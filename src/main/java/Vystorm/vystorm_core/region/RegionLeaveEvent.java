package Vystorm.vystorm_core.region;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Ein Spieler hat eine Region verlassen (nach Bewegung, Teleport oder weil die Region entfernt wurde). */
public final class RegionLeaveEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Region region;
    public RegionLeaveEvent(Player player, Region region) { super(player); this.region = region; }
    public Region region() { return region; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
