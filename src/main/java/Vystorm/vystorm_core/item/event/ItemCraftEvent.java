package Vystorm.vystorm_core.item.event;

import Vystorm.vystorm_core.item.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Crafting/Schmieden: Stage START/ACTION (cancellable) und COMPLETE/FAILED (informativ). */
public class ItemCraftEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String recipeId;
    private final String station;
    private final String stage;
    private final String action;
    private final double quality;
    private final ItemStack result;
    private boolean cancelled;

    public ItemCraftEvent(Player player, String recipeId, String station, String stage, String action, double quality, ItemStack result) {
        this.player = player;
        this.recipeId = recipeId;
        this.station = station;
        this.stage = stage;
        this.action = action;
        this.quality = quality;
        this.result = result;
    }

    public Player player() { return player; }

    public String recipeId() { return recipeId; }

    public String station() { return station; }

    public String stage() { return stage; }

    public String action() { return action; }

    public double quality() { return quality; }

    public ItemStack result() { return result; }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
