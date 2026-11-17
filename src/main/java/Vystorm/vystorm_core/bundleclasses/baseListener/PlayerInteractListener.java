package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

/**
 * Event-Listener für PlayerInteractEvent.
 * Leitet Spielerinteraktionen an das Prioritätssystem für NBT-Verarbeitung weiter.
 */
public class PlayerInteractListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        main.getApi().priority().itemUseEvent(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        main.getApi().priority().changeItemEvent(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        main.getApi().priority().itemDamageEvent(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        main.getApi().priority().interactAtEntityEvent(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        main.getApi().priority().playerHarvestBlockEvent(event);
    }
}
