package Vystorm.vystorm_core._coreMethods.eventRegulator.defaultEntitys;

import Vystorm.vystorm_core._coreMethods.eventRegulator.NBTEntity;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public class JoinLeaveEntity implements NBTEntity {
    @Override
    public boolean match(String tag) {
        return "JoinLeave".equals(tag);
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public boolean isBlock() {
        return false;
    }

    @Override
    public boolean isEntity() {
        return false;
    }

    @Override
    public boolean requiresEvent() {
        return false;
    }

    @Override
    public boolean blocksEvent() {
        return false;
    }

    @Override
    public boolean cancelsEvent() {
        return false;
    }

    @Override
    public void setPriority(int priority) {
    }

    @Override
    public void runPlayer(Player player) {
    }

    @Override
    public void runEntity(Entity entity) {
    }

    @Override
    public void runBlock(Block block) {
    }

    @Override
    public void runItem(ItemStack itemStack) {
    }

    @Override
    public void blockBreakEvent(BlockBreakEvent event) {
    }

    @Override
    public void blockPlaceEvent(BlockPlaceEvent event) {
    }

    @Override
    public void entityDamageListener(EntityDamageEvent event) {
    }

    @Override
    public void entityDeathListener(EntityDeathEvent event) {
    }

    @Override
    public void itemDropEvent(PlayerDropItemEvent event) {
    }

    @Override
    public void itemPickupEvent(PlayerPickupItemEvent event) {
    }

    public void inventoryEvent(InventoryEvent event) {
    }

    public void inventoryOpenEvent(InventoryOpenEvent event) {
    }

    public void inventoryCloseEvent(InventoryCloseEvent event) {
    }

    public void inventoryClickEvent(InventoryClickEvent event) {
    }

    public void InventoryDragEvent(InventoryDragEvent event) {
    }

    public void InventoryMoveItemEvent(InventoryMoveItemEvent event) {
    }

    @Override
    public void itemUseEvent(PlayerInteractEvent event) {
    }

    @Override
    public void playerHarvestBlockEvent(PlayerHarvestBlockEvent event) {
    }

    @Override
    public void interactAtEntityEvent(PlayerInteractAtEntityEvent event) {
    }

    @Override
    public void itemDamageEvent(PlayerItemDamageEvent event) {
    }

    @Override
    public void changeItemEvent(PlayerItemHeldEvent event) {
    }
}
