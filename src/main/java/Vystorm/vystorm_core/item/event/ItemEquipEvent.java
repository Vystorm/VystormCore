package Vystorm.vystorm_core.item.event;

import Vystorm.vystorm_core.item.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Item wurde angelegt ({@code equipped}) oder abgelegt; Stat-Snapshot ist bereits aktualisiert. */
public class ItemEquipEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ItemView item;
    private final String slot;
    private final boolean equipped;

    public ItemEquipEvent(Player player, ItemView item, String slot, boolean equipped) {
        this.player = player;
        this.item = item;
        this.slot = slot;
        this.equipped = equipped;
    }

    public Player player() { return player; }

    public ItemView item() { return item; }

    public String slot() { return slot; }

    public boolean equipped() { return equipped; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
