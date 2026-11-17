package Vystorm.vystorm_core.item.event;

import Vystorm.vystorm_core.item.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: eine Vystorm-Iteminstanz wurde erzeugt (Loot, Crafting, Admin, Migration …). */
public class ItemCreateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ItemView item;
    private final ItemCreateSpec spec;
    private final Player player;

    public ItemCreateEvent(ItemView item, ItemCreateSpec spec, Player player) {
        this.item = item;
        this.spec = spec;
        this.player = player;
    }

    public ItemView item() { return item; }

    public ItemCreateSpec spec() { return spec; }

    public Player player() { return player; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
