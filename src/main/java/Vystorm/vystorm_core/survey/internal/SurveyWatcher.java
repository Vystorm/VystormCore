package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.blocks.BlocksEditedEvent;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * The block events the survey watches. Shared events (break, place, explosions, pistons, fire) are handled in
 * Core's OBSERVE stage, so the survey only sees changes that really happened; the others are plain listeners at
 * MONITOR. Every handler only notes the position: the next tick reads the block back and counts it only if an
 * opaque cell turned open or the other way round. Physics updates name the block that changed (also for
 * {@code /setblock} and fills), which catches changes no other event reports.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class SurveyWatcher implements Listener {
    private final SurveyRuntime runtime;
    private final List<EventStages.Registration> stages = new ArrayList<>();

    SurveyWatcher(SurveyRuntime runtime) { this.runtime = runtime; }

    void register() {
        EventStages s = EventStages.shared();
        stages.add(s.handle(runtime.core, BlockBreakEvent.class, EventStage.OBSERVE, e -> touch(e.getBlock())));
        stages.add(s.handle(runtime.core, BlockPlaceEvent.class, EventStage.OBSERVE, e -> {
            touch(e.getBlockPlaced());
            if (e instanceof BlockMultiPlaceEvent multi) for (BlockState state : multi.getReplacedBlockStates()) touch(state.getLocation());
        }));
        stages.add(s.handle(runtime.core, BlockExplodeEvent.class, EventStage.OBSERVE, e -> { for (Block b : e.blockList()) touch(b); }));
        stages.add(s.handle(runtime.core, EntityExplodeEvent.class, EventStage.OBSERVE, e -> { for (Block b : e.blockList()) touch(b); }));
        stages.add(s.handle(runtime.core, BlockPistonExtendEvent.class, EventStage.OBSERVE, e -> piston(e.getBlock(), e.getBlocks(), e.getDirection())));
        stages.add(s.handle(runtime.core, BlockPistonRetractEvent.class, EventStage.OBSERVE, e -> piston(e.getBlock(), e.getBlocks(), e.getDirection())));
        stages.add(s.handle(runtime.core, BlockBurnEvent.class, EventStage.OBSERVE, e -> touch(e.getBlock())));
        runtime.core.getServer().getPluginManager().registerEvents(this, runtime.core);
    }

    void unregister() {
        for (EventStages.Registration r : stages) EventStages.shared().remove(r);
        stages.clear();
        HandlerList.unregisterAll(this);
    }

    private void piston(Block piston, List<Block> moved, BlockFace direction) {
        touch(piston.getRelative(direction));
        for (Block b : moved) {
            touch(b);
            touch(b.getRelative(direction));
        }
    }

    private void touch(Block b) { runtime.touch(b.getWorld(), b.getX(), b.getY(), b.getZ()); }

    private void touch(Location l) {
        World w = l.getWorld();
        if (w != null) runtime.touch(w, l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) { touch(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) { for (BlockState state : e.getBlocks()) touch(state.getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { touch(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { touch(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { touch(e.getBlock()); }

    /** Physics: only for chunks the survey tracks, so the firehose costs one lookup per update elsewhere. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        Block source = e.getSourceBlock();
        if (runtime.tracks(source.getWorld(), source.getX() >> 4, source.getZ() >> 4)) touch(source);
    }

    /**
     * Bulk edits through {@code api.blocks()} write without block and physics events; the edit announces each chunk
     * once with its written cells and the states before and after.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlocksEdited(BlocksEditedEvent e) { runtime.edited(e.chunk()); }

    /** LOWEST: other plugins' own load handlers can already ask {@code cached()} for the chunk. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent e) { runtime.chunkLoaded(e.getWorld(), e.getChunk()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) { runtime.chunkUnloaded(e.getWorld(), e.getChunk()); }
}
