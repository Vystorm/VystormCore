package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Event-Listener für BlockBreakEvent.
 * Leitet Block-Break-Events an das Prioritätssystem für NBT-Verarbeitung weiter.
 */
public class BlockBreakListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        main.getApi().priority().blockBreakEvent(event);
    }
}

