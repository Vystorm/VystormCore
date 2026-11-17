package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Event-Listener für BlockPlaceEvent.
 * Leitet Block-Place-Events an das Prioritätssystem für NBT-Verarbeitung weiter.
 */
public class BlockPlaceListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        main.getApi().priority().blockPlaceEvent(event);
    }
}

