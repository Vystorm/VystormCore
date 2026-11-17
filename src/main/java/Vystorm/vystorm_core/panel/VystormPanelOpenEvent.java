package Vystorm.vystorm_core.panel;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Ein natives Panel wurde an den Client geschickt. Synchron, Server-Thread. @since 0.25.0 */
public final class VystormPanelOpenEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String panelId;
    private final int session;

    public VystormPanelOpenEvent(Player player, String panelId, int session) {
        super(player);
        this.panelId = panelId;
        this.session = session;
    }

    public String panelId() { return panelId; }
    public int session() { return session; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
}
