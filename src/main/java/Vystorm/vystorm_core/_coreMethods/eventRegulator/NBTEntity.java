package Vystorm.vystorm_core._coreMethods.eventRegulator;


import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

public interface NBTEntity {
    //fast check if it's the same
    public boolean match(String tag);
    // can be all or none depending on the type and State Priority should be 10 as default if something should cancel another it's easy to set higher, and it will cancel the other nbt that can happen.

    /**
     * Priority is used to determine the order of execution when multiple NBTEntities are triggered by the same event. Higher priority NBTEntities will be executed before lower priority ones. This allows for better control over the flow of events and can be used to ensure that certain actions are performed before others, especially when some NBTEntities may cancel events or modify data that other NBTEntities rely on.
     * Higher Priority can Cancel lower priority if cancelsEvent is true and the event is not already cancelled by a higher priority NBTEntity. This allows for a hierarchical control over event handling, where more critical or specific NBTEntities can take precedence over more general ones, ensuring that the most important actions are executed first and can prevent unnecessary processing of lower priority NBTEntities if the event has already been handled or cancelled.
     * Standard priority is 10, but it can be set higher or lower depending on the needs of the specific NBTEntity and its intended interactions with other NBTEntities. For example, an NBTEntity that is designed to log information might have a lower priority than one that modifies game mechanics, allowing the logging to occur after all modifications have been made.
     * Negative values are allowed as well but remember it stays an int
     * @return
     */
    public int priority();
    // self-explanatory
    public boolean isPlayer();
    public boolean isBlock();
    public boolean isEntity();

   //this will require if it's just run for player block or entity or if its handeled under event
    public boolean requiresEvent();
    public boolean blocksEvent();
    public boolean cancelsEvent();

    public void runPlayer(Player player);
    public void runEntity(Entity entity);
    public void runBlock(Block block);
    public void runItem(ItemStack itemStack);

   public void blockBreakEvent(BlockBreakEvent event);
   public void blockPlaceEvent(BlockPlaceEvent event);
   public void entityDamageListener(EntityDamageEvent event);
   public void entityDeathListener(EntityDeathEvent event);
   public void itemDropEvent(PlayerDropItemEvent event);
   public void itemPickupEvent(PlayerPickupItemEvent event);

   public void setPriority(int priority);

   void itemUseEvent(PlayerInteractEvent event);

    void playerHarvestBlockEvent(PlayerHarvestBlockEvent event);

    void interactAtEntityEvent(PlayerInteractAtEntityEvent event);

    void itemDamageEvent(PlayerItemDamageEvent event);

    void changeItemEvent(PlayerItemHeldEvent event);
}
