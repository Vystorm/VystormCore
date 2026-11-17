package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;

public class AdvancedListener implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND && event.getRightClicked() instanceof Interaction) {
            if (main.getApi().interactionGenerator().handle(event.getRightClicked().getUniqueId(), event.getPlayer(), false)) {
                event.setCancelled(true);
            }
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void interactAt(PlayerInteractAtEntityEvent event) { interact(event); }
    @EventHandler(ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction && event.getDamager() instanceof Player player) {
            if (main.getApi().interactionGenerator().handle(event.getEntity().getUniqueId(), player, true)) event.setCancelled(true);
        }
    }
    @EventHandler
    public void disable(PluginDisableEvent event) {
        main.getApi().releaseAdvancedResources(event.getPlugin());
        main.getApi().admin().unregister(event.getPlugin());
    }
}
