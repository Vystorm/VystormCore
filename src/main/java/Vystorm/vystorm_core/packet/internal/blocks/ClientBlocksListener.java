package Vystorm.vystorm_core.packet.internal.blocks;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.blocks.BlocksEditedEvent;
import Vystorm.vystorm_core.blocks.EditedChunk;
import Vystorm.vystorm_core.compat.ClientEvents;
import Vystorm.vystorm_core.packet.ClientBlockInteract;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;

/**
 * Block events that can overwrite a client block, fed into {@link ClientBlocksRuntime}. Shared block and player events
 * go through Core's event stages at OBSERVE: they only react after the change really happened (cancelled clicks and
 * breaks are received too, because the server then sends the clicking player the real block). Block physics fires for
 * every neighbour update, so it is a plain MONITOR listener that is registered only while at least one set is open.
 * Quit, world change, respawn and the client chunk events are Core-internal bookkeeping (plain listeners).
 *
 * <p>Every handler probes the per-world section index first (one lookup, no allocation) and returns when no client
 * block is shown in that section.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class ClientBlocksListener implements Listener {
    private final ClientBlocksRuntime rt;
    private final List<EventStages.Registration> stages = new ArrayList<>();
    private final Listener physics = new Listener() {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPhysics(BlockPhysicsEvent e) { changed(e.getBlock()); }
    };
    private Plugin core;
    private boolean physicsOn;

    ClientBlocksListener(ClientBlocksRuntime rt) { this.rt = rt; }

    void register(Plugin core) {
        this.core = core;
        EventStages s = EventStages.shared();
        stages.add(s.handle(core, BlockBreakEvent.class, EventStage.OBSERVE, true, this::onBreak));
        stages.add(s.handle(core, BlockPlaceEvent.class, EventStage.OBSERVE, true, this::onPlace));
        stages.add(s.handle(core, PlayerInteractEvent.class, EventStage.OBSERVE, true, this::onInteract));
        stages.add(s.handle(core, BlockDamageEvent.class, EventStage.OBSERVE, true, this::onDamage));
        stages.add(s.handle(core, BlockExplodeEvent.class, EventStage.OBSERVE, e -> blocks(e.blockList())));
        stages.add(s.handle(core, EntityExplodeEvent.class, EventStage.OBSERVE, e -> blocks(e.blockList())));
        stages.add(s.handle(core, BlockPistonExtendEvent.class, EventStage.OBSERVE, e -> piston(e.getBlock(), e.getBlocks(), e.getDirection())));
        stages.add(s.handle(core, BlockPistonRetractEvent.class, EventStage.OBSERVE, e -> piston(e.getBlock(), e.getBlocks(), e.getDirection())));
        stages.add(s.handle(core, BlockBurnEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, BlockIgniteEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, BlockFromToEvent.class, EventStage.OBSERVE, e -> changed(e.getToBlock())));
        stages.add(s.handle(core, EntityChangeBlockEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, BlockFormEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, BlockFadeEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, BlockSpreadEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, BlockGrowEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, LeavesDecayEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, FluidLevelChangeEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, MoistureChangeEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, CauldronLevelChangeEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, BlockRedstoneEvent.class, EventStage.OBSERVE, e -> changed(e.getBlock())));
        stages.add(s.handle(core, StructureGrowEvent.class, EventStage.OBSERVE, e -> states(e.getBlocks())));
        stages.add(s.handle(core, BlockFertilizeEvent.class, EventStage.OBSERVE, e -> states(e.getBlocks())));
        stages.add(s.handle(core, SpongeAbsorbEvent.class, EventStage.OBSERVE, e -> states(e.getBlocks())));
        stages.add(s.handle(core, BlocksEditedEvent.class, EventStage.OBSERVE, e -> edited(e.chunk())));
        core.getServer().getPluginManager().registerEvents(this, core);
    }

    void unregister() {
        for (EventStages.Registration r : stages) EventStages.shared().remove(r);
        stages.clear();
        HandlerList.unregisterAll(this);
        physics(false);
    }

    /** Registers the block physics listener while sets exist. */
    void physics(boolean on) {
        if (on == physicsOn) return;
        physicsOn = on;
        if (on && core != null && core.isEnabled()) core.getServer().getPluginManager().registerEvents(physics, core);
        else HandlerList.unregisterAll(physics);
    }

    // ------------------------------------------------------------------ player actions

    private void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!rt.watched(b.getWorld(), b.getX(), b.getY(), b.getZ())) return;
        boolean shown = rt.interact(e.getPlayer(), b, ClientBlockInteract.Action.BREAK);
        if (e.isCancelled()) {
            // The server sends the breaker the real block back at once; nobody else saw a change.
            if (!shown) rt.repairFor(e.getPlayer(), b.getX(), b.getY(), b.getZ());
        } else {
            rt.changed(b.getWorld(), b.getX(), b.getY(), b.getZ(), shown ? e.getPlayer().getUniqueId() : null);
        }
    }

    private void onPlace(BlockPlaceEvent e) {
        Block placed = e.getBlockPlaced();
        Block against = e.getBlockAgainst();
        if (e.isCancelled()) {
            // The placer predicted the block; the server sends him the real states of both cells.
            rt.repairFor(e.getPlayer(), placed.getX(), placed.getY(), placed.getZ());
            if (against != null) rt.repairFor(e.getPlayer(), against.getX(), against.getY(), against.getZ());
            return;
        }
        changed(placed);
        if (e instanceof BlockMultiPlaceEvent multi) states(multi.getReplacedBlockStates());
    }

    private void onInteract(PlayerInteractEvent e) {
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        Action action = e.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        // The server fires a right click again for the off hand; the main hand's event already handled the cell.
        if (e.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;
        World world = clicked.getWorld();
        BlockFace face = e.getBlockFace();
        boolean near = rt.watched(world, clicked.getX(), clicked.getY(), clicked.getZ());
        Block next = action == Action.RIGHT_CLICK_BLOCK && face != null ? clicked.getRelative(face) : null;
        if (!near && (next == null || !rt.watched(world, next.getX(), next.getY(), next.getZ()))) return;
        boolean shown = rt.interact(e.getPlayer(), clicked, action == Action.LEFT_CLICK_BLOCK
                ? ClientBlockInteract.Action.LEFT_CLICK : ClientBlockInteract.Action.RIGHT_CLICK);
        // A right click makes the server send the real clicked cell and the cell in front of the face.
        if (!shown && action == Action.RIGHT_CLICK_BLOCK) rt.repairFor(e.getPlayer(), clicked.getX(), clicked.getY(), clicked.getZ());
        if (next != null) rt.repairFor(e.getPlayer(), next.getX(), next.getY(), next.getZ());
    }

    private void onDamage(BlockDamageEvent e) {
        Block b = e.getBlock();
        if (rt.watched(b.getWorld(), b.getX(), b.getY(), b.getZ()))
            rt.interact(e.getPlayer(), b, ClientBlockInteract.Action.START_BREAK);
    }

    // ------------------------------------------------------------------ world changes

    private void changed(Block b) {
        World world = b.getWorld();
        int x = b.getX(), y = b.getY(), z = b.getZ();
        if (rt.watched(world, x, y, z)) rt.changed(world, x, y, z, null);
    }

    private void blocks(List<Block> blocks) { for (Block b : blocks) changed(b); }

    private void states(List<BlockState> states) {
        for (BlockState state : states) {
            Location l = state.getLocation();
            World world = l.getWorld();
            if (world == null) continue;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            if (rt.watched(world, x, y, z)) rt.changed(world, x, y, z, null);
        }
    }

    private void piston(Block piston, List<Block> moved, BlockFace direction) {
        changed(piston.getRelative(direction));
        for (Block b : moved) {
            changed(b);
            changed(b.getRelative(direction));
            changed(b.getRelative(direction.getOppositeFace()));
        }
    }

    private void edited(EditedChunk chunk) {
        World world = chunk.world();
        if (!rt.watchedChunk(world, chunk.chunkX(), chunk.chunkZ())) return;
        for (int i = 0; i < chunk.size(); i++) {
            int x = chunk.x(i), y = chunk.y(i), z = chunk.z(i);
            if (rt.watched(world, x, y, z)) rt.changed(world, x, y, z, null);
        }
    }

    // ------------------------------------------------------------------ Core bookkeeping

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { rt.forget(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { rt.forget(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { rt.forget(e.getPlayer()); }

    @EventHandler
    public void onChunkSent(ClientEvents.Loaded e) {
        rt.chunkLoaded(e.getPlayer(), e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
    }

    @EventHandler
    public void onChunkForgotten(ClientEvents.Unloaded e) {
        rt.chunkUnloaded(e.getPlayer(), e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
    }

}
