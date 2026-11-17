package Vystorm.vystorm_core.panel;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

/**
 * Der Client hat ein Panel oder ein Element abgelehnt (P_ERROR). {@link #code()} = {@code PanelLimits.ERR_*},
 * {@link #detail()} ist bereits bereinigt. {@link #owner()} ist das Plugin des Panels oder null. Synchron, Server-Thread.
 * @since 0.25.0
 */
public final class VystormPanelErrorEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Plugin owner;
    private final String panelId;
    private final String elementId;
    private final int code;
    private final String detail;

    public VystormPanelErrorEvent(Player player, Plugin owner, String panelId, String elementId, int code, String detail) {
        super(player);
        this.owner = owner;
        this.panelId = panelId;
        this.elementId = elementId;
        this.code = code;
        this.detail = detail;
    }

    public Plugin owner() { return owner; }
    public String panelId() { return panelId; }
    public String elementId() { return elementId; }
    public int code() { return code; }
    public String detail() { return detail; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
}
