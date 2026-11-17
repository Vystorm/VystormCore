package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Event-Listener für PlayerDropItemEvent.
 * Leitet Item-Drop-Events an das EventRegister-System für NBT-Verarbeitung weiter.
 */
public class PlayerDropItemListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        main.getApi().inventoryInteractor().handleEvent(event);

    }
}

