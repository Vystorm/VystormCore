package Vystorm.vystorm_core.picker.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.screen.internal.ScreenDispatch;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The world pick guard (0.71.0): a picking player's world actions are cancelled at PREPARE; clicks become triggers. */
class WorldPickGuardTest {
    private final EventStageTesting stages = EventStageTesting.detached();
    private final Plugin core = EventStageTesting.plugin("vystorm_core");
    private final Player picking = player(), free = player();
    private final List<Player> triggers = new ArrayList<>();
    private boolean running = true;

    private static Player player() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        return p;
    }

    @BeforeEach void setUp() {
        WorldPickGuard.register(stages.stages(), core, p -> {
            if (!running) throw new IllegalStateException("not running");
            return p == picking;
        }, (p, swing) -> triggers.add(p));
    }

    private static <E extends Event> E event(Class<E> type) { return mock(type); }

    private BlockBreakEvent breaking(Player p) { BlockBreakEvent e = event(BlockBreakEvent.class); when(e.getPlayer()).thenReturn(p); return e; }
    private PlayerInteractEvent using(Player p, Action action) {
        PlayerInteractEvent e = event(PlayerInteractEvent.class);
        when(e.getPlayer()).thenReturn(p);
        when(e.getAction()).thenReturn(action);
        return e;
    }

    private List<Cancellable> everything(Player p) {
        List<Cancellable> out = new ArrayList<>();
        out.add(stages.fire(breaking(p)));
        BlockDamageEvent mined = event(BlockDamageEvent.class); when(mined.getPlayer()).thenReturn(p); out.add(stages.fire(mined));
        BlockPlaceEvent placed = event(BlockPlaceEvent.class); when(placed.getPlayer()).thenReturn(p); out.add(stages.fire(placed));
        out.add(stages.fire(using(p, Action.LEFT_CLICK_BLOCK)));
        out.add(stages.fire(using(p, Action.RIGHT_CLICK_BLOCK)));
        PlayerInteractEntityEvent entity = event(PlayerInteractEntityEvent.class); when(entity.getPlayer()).thenReturn(p); out.add(stages.fire(entity));
        PlayerInteractAtEntityEvent at = event(PlayerInteractAtEntityEvent.class); when(at.getPlayer()).thenReturn(p); out.add(stages.fire(at));
        PlayerArmorStandManipulateEvent stand = event(PlayerArmorStandManipulateEvent.class); when(stand.getPlayer()).thenReturn(p); out.add(stages.fire(stand));
        EntityDamageByEntityEvent hit = event(EntityDamageByEntityEvent.class); when(hit.getDamager()).thenReturn(p); out.add(stages.fire(hit));
        Arrow arrow = mock(Arrow.class); when(arrow.getShooter()).thenReturn(p);
        EntityDamageByEntityEvent shot = event(EntityDamageByEntityEvent.class); when(shot.getDamager()).thenReturn(arrow); out.add(stages.fire(shot));
        HangingBreakByEntityEvent frame = event(HangingBreakByEntityEvent.class); when(frame.getRemover()).thenReturn(p); out.add(stages.fire(frame));
        VehicleDamageEvent boat = event(VehicleDamageEvent.class); when(boat.getAttacker()).thenReturn(p); out.add(stages.fire(boat));
        PlayerBucketEmptyEvent empty = event(PlayerBucketEmptyEvent.class); when(empty.getPlayer()).thenReturn(p); out.add(stages.fire(empty));
        PlayerBucketFillEvent fill = event(PlayerBucketFillEvent.class); when(fill.getPlayer()).thenReturn(p); out.add(stages.fire(fill));
        return out;
    }

    @Test void givenPickingPlayer_whenActingOnTheWorld_thenEveryActionIsCancelled_andClicksAreTriggers() {
        for (Cancellable e : everything(picking)) verify(e, atLeastOnce().description(e.getClass().getSimpleName())).setCancelled(true);
        // interact (left, right), entity use, at-entity use, melee hit: five triggers; break, place, buckets are none
        assertEquals(5, triggers.size());
        assertTrue(triggers.stream().allMatch(p -> p == picking));
    }

    @Test void givenOtherPlayer_whenActingOnTheWorld_thenNothingIsTouched() {
        for (Cancellable e : everything(free)) verify(e, never()).setCancelled(anyBoolean());
        EntityDamageByEntityEvent zombie = event(EntityDamageByEntityEvent.class);
        when(zombie.getDamager()).thenReturn(mock(Zombie.class));
        verify(stages.fire(zombie), never()).setCancelled(anyBoolean());
        assertTrue(triggers.isEmpty());
    }

    @Test void givenPickingPlayer_whenClickingIntoTheAir_thenTheHeldItemIsNotUsed() {
        // the server reports air clicks as cancelled already; the handler still sees them and denies the item use
        PlayerInteractEvent air = using(picking, Action.RIGHT_CLICK_AIR);
        when(air.isCancelled()).thenReturn(true);
        stages.fire(air);
        verify(air, atLeastOnce()).setUseItemInHand(Event.Result.DENY);
        verify(air, atLeastOnce()).setUseInteractedBlock(Event.Result.DENY);
        verify(air, atLeastOnce()).setCancelled(true);
        assertEquals(List.of(picking), triggers);
    }

    @Test void givenPressurePlate_whenStepped_thenLeftAlone() {
        PlayerInteractEvent plate = stages.fire(using(picking, Action.PHYSICAL));
        verify(plate, never()).setCancelled(anyBoolean());
        verify(plate, never()).setUseItemInHand(any());
        assertTrue(triggers.isEmpty());
    }

    @Test void givenEventFiredFromACoreHandler_whenPicking_thenItPasses() {
        BlockBreakEvent[] probe = new BlockBreakEvent[1];
        ScreenDispatch.run(() -> probe[0] = stages.fire(breaking(picking)));
        verify(probe[0], never()).setCancelled(anyBoolean());
    }

    @Test void givenRuntimeGone_whenEventsArrive_thenNothingIsCancelledAndNothingFails() {
        running = false;
        verify(stages.fire(breaking(picking)), never()).setCancelled(anyBoolean());
    }

    @Test void givenTheGuard_whenRegistered_thenEveryHandlerRunsAtPrepareAndAgainAtFinish() {
        for (Class<? extends Event> type : List.of(BlockBreakEvent.class, BlockDamageEvent.class, BlockPlaceEvent.class, PlayerInteractEvent.class,
                PlayerInteractEntityEvent.class, PlayerInteractAtEntityEvent.class, PlayerArmorStandManipulateEvent.class, EntityDamageByEntityEvent.class,
                HangingBreakByEntityEvent.class, VehicleDamageEvent.class, PlayerBucketEmptyEvent.class, PlayerBucketFillEvent.class)) {
            List<EventStages.Registration> own = stages.handlers(type).stream().filter(r -> r.owner() == core && r.type() == type).toList();
            assertEquals(List.of(EventStage.PREPARE, EventStage.FINISH), own.stream().map(EventStages.Registration::stage).sorted().toList(), type.getSimpleName());
        }
        assertTrue(stages.handlers(PlayerInteractEvent.class).stream().anyMatch(EventStages.Registration::receivesCancelled), "air clicks are seen");
    }

    @Test void givenKinds_whenOptionsLaidOut_thenStandAndTypeOnlyWhereTheyMakeSense() {
        assertTrue(actions(PickKind.POSITION).containsAll(List.of(WorldOptionsLayout.Action.STAND, WorldOptionsLayout.Action.TYPE)));
        assertTrue(actions(PickKind.BLOCK).containsAll(List.of(WorldOptionsLayout.Action.STAND, WorldOptionsLayout.Action.TYPE)));
        assertFalse(actions(PickKind.FACE).contains(WorldOptionsLayout.Action.STAND));
        assertTrue(actions(PickKind.FACE).contains(WorldOptionsLayout.Action.TYPE));
        assertFalse(actions(PickKind.CONTAINER).contains(WorldOptionsLayout.Action.STAND));
        assertEquals(List.of(WorldOptionsLayout.Action.INFO, WorldOptionsLayout.Action.RESUME, WorldOptionsLayout.Action.CANCEL), actions(PickKind.ENTITY));
        for (PickKind k : List.of(PickKind.POSITION, PickKind.BLOCK, PickKind.FACE, PickKind.CONTAINER, PickKind.ENTITY))
            for (WorldOptionsLayout.Slot s : WorldOptionsLayout.layout(k)) assertTrue(s.slot() >= 0 && s.slot() < WorldOptionsLayout.SIZE);
    }

    private static List<WorldOptionsLayout.Action> actions(PickKind k) { return WorldOptionsLayout.layout(k).stream().map(WorldOptionsLayout.Slot::action).toList(); }
}
