package Vystorm.vystorm_core.compat;

import org.bukkit.event.*;

/** Loaded only when Paper's client chunk events exist. */
final class PaperClientEvents implements Listener {
    @EventHandler public void loaded(io.papermc.paper.event.packet.PlayerChunkLoadEvent e){ClientEvents.loaded(e.getPlayer(),e.getChunk());}
    @EventHandler public void unloaded(io.papermc.paper.event.packet.PlayerChunkUnloadEvent e){ClientEvents.unloaded(e.getPlayer(),e.getChunk());}
    @EventHandler public void interact(com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent e){ClientEvents.interact(e.getPlayer(),e.getEntityId(),e.isAttack(),e.getHand());}
}
