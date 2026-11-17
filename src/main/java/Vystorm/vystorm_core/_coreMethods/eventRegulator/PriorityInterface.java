package Vystorm.vystorm_core._coreMethods.eventRegulator;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;

import java.util.ArrayList;

public interface PriorityInterface {
    public void blockBreakEvent(BlockBreakEvent event);
    public void blockPlaceEvent(BlockPlaceEvent event);
    public void entityDamageListener(EntityDamageEvent event);
    public void entityDeathListener(EntityDeathEvent event);
   // Handeled in Inventory Handler
    // public void itemDropEvent(PlayerDropItemEvent event);
   // public void itemPickupEvent(PlayerPickupItemEvent event);
    //public void inventoryEvent(InventoryEvent event);
   // public void inventoryOpenEvent(InventoryOpenEvent event);
   // public void inventoryCloseEvent(InventoryCloseEvent event);
   // public void inventoryClickEvent(InventoryClickEvent event);
  //  public void inventoryDragEvent(InventoryDragEvent event);
  //  public void inventoryMoveItemEvent(InventoryMoveItemEvent event);
    public void itemUseEvent(PlayerInteractEvent event);
    public void changeItemEvent(PlayerItemHeldEvent event);
    public void itemDamageEvent(PlayerItemDamageEvent event);
    public void interactAtEntityEvent(PlayerInteractAtEntityEvent event);
    public void playerHarvestBlockEvent(PlayerHarvestBlockEvent event);



    public void run(ArrayList<String> keys, Player player);
    public void run(ArrayList<String> keys, Block block);
    public void run(ArrayList<String> keys, org.bukkit.entity.Entity entity);

    /**
     * The order in which Vystorm plugins handle a shared Bukkit event: plugins register a handler with a named
     * {@link EventStage} instead of choosing a Bukkit priority, and Core dispatches them in one configured order.
     * See {@link EventStages}.
     * @since 0.64.0
     */
    default EventStages stages() { return EventStages.shared(); }
}
