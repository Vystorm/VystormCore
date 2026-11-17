package Vystorm.vystorm_core.panel;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Ein natives Panel wurde geschlossen. {@link #reason()}: 0 Spieler, 1 ersetzt, 2 Fehler, 3 Server
 * ({@code PanelLimits.CLOSED_*}). Synchron, Server-Thread. @since 0.25.0
 */
public final class VystormPanelCloseEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String panelId;
    private final int session;
    private final int reason;

    public VystormPanelCloseEvent(Player player, String panelId, int session, int reason) {
        super(player);
        this.panelId = panelId;
        this.session = session;
        this.reason = reason;
    }

    public String panelId() { return panelId; }
    public int session() { return session; }
    public int reason() { return reason; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
}
