package Vystorm.vystorm_core.item.event;

import Vystorm.vystorm_core.item.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Loot aus einem Profil wurde generiert (Liste veränderbar vor Auslieferung). */
public class ItemLootEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String profileId;
    private final LootContext context;
    private final List<ItemStack> items;

    public ItemLootEvent(String profileId, LootContext context, List<ItemStack> items) {
        this.profileId = profileId;
        this.context = context;
        this.items = items;
    }

    public String profileId() { return profileId; }

    public LootContext context() { return context; }

    public List<ItemStack> items() { return items; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
