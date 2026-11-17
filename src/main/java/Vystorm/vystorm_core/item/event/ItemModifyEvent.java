package Vystorm.vystorm_core.item.event;

import Vystorm.vystorm_core.item.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Cancellable: UPGRADE, REFORGE, REPAIR, SALVAGE, SOCKET_INSERT, SOCKET_REMOVE, IDENTIFY, BIND an einem Item. */
public class ItemModifyEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ItemView item;
    private final String kind;
    private final String detail;
    private boolean cancelled;

    public ItemModifyEvent(Player player, ItemView item, String kind, String detail) {
        this.player = player;
        this.item = item;
        this.kind = kind;
        this.detail = detail;
    }

    public Player player() { return player; }

    public ItemView item() { return item; }

    public String kind() { return kind; }

    public String detail() { return detail; }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
