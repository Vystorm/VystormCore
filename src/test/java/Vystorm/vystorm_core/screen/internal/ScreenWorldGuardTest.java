package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The world guard of screens (0.65.0): what a guarded player does to the world is cancelled at the PROTECT stage. */
class ScreenWorldGuardTest {
    private final EventStageTesting stages = EventStageTesting.detached();
    private final Plugin core = EventStageTesting.plugin("vystorm_core");
    private final ScreenRuntime runtime = mock(ScreenRuntime.class);
    private final Player guarded = player(), free = player();
    private boolean running = true;

    private static Player player() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        return p;
    }

    @BeforeEach void setUp() {
        when(runtime.stopsWorldAction(eq(guarded), anyBoolean())).thenReturn(true);
        ScreenWorldGuard.register(stages.stages(), core, () -> {
            if (!running) throw new IllegalStateException("Vystorm Screens are not running");
            return runtime;
        });
    }

    private BlockBreakEvent breaking(Player p) { BlockBreakEvent e = mock(BlockBreakEvent.class); when(e.getPlayer()).thenReturn(p); return e; }
    private BlockDamageEvent mining(Player p) { BlockDamageEvent e = mock(BlockDamageEvent.class); when(e.getPlayer()).thenReturn(p); return e; }
    private PlayerInteractEvent using(Player p, Action action) {
        PlayerInteractEvent e = mock(PlayerInteractEvent.class);
        when(e.getPlayer()).thenReturn(p);
        when(e.getAction()).thenReturn(action);
        return e;
    }
    private PlayerInteractEntityEvent usingEntity(Player p) { PlayerInteractEntityEvent e = mock(PlayerInteractEntityEvent.class); when(e.getPlayer()).thenReturn(p); return e; }
    private EntityDamageByEntityEvent hitting(org.bukkit.entity.Entity damager) {
        EntityDamageByEntityEvent e = mock(EntityDamageByEntityEvent.class);
        when(e.getDamager()).thenReturn(damager);
        return e;
    }

    @Test void givenGuardedPlayer_whenBreakMineUseOrHit_thenEveryActionIsCancelled() {
        BlockBreakEvent broken = stages.fire(breaking(guarded));
        BlockDamageEvent mined = stages.fire(mining(guarded));
        PlayerInteractEvent leftBlock = stages.fire(using(guarded, Action.LEFT_CLICK_BLOCK));
        PlayerInteractEvent rightBlock = stages.fire(using(guarded, Action.RIGHT_CLICK_BLOCK));
        PlayerInteractEntityEvent villager = stages.fire(usingEntity(guarded));
        EntityDamageByEntityEvent hit = stages.fire(hitting(guarded));
        HangingBreakByEntityEvent frame = mock(HangingBreakByEntityEvent.class);
        when(frame.getRemover()).thenReturn(guarded);
        stages.fire(frame);
        VehicleDamageEvent boat = mock(VehicleDamageEvent.class);
        when(boat.getAttacker()).thenReturn(guarded);
        stages.fire(boat);
        for (org.bukkit.event.Cancellable e : List.of(broken, mined, leftBlock, rightBlock, villager, hit, frame, boat))
            verify(e, description(e.getClass().getSimpleName())).setCancelled(true);
        verify(runtime, atLeast(6)).stopsWorldAction(guarded, true);
    }

    @Test void givenPlayerWithoutScreenOrGuard_whenPlaying_thenNothingIsTouched() {
        BlockBreakEvent broken = stages.fire(breaking(free));
        BlockDamageEvent mined = stages.fire(mining(free));
        PlayerInteractEvent used = stages.fire(using(free, Action.RIGHT_CLICK_BLOCK));
        PlayerInteractEntityEvent villager = stages.fire(usingEntity(free));
        EntityDamageByEntityEvent hit = stages.fire(hitting(free));
        EntityDamageByEntityEvent zombieHit = stages.fire(hitting(mock(Zombie.class)));
        for (org.bukkit.event.Cancellable e : List.of(broken, mined, used, villager, hit, zombieHit)) verify(e, never()).setCancelled(anyBoolean());
    }

    @Test void givenGuardedPlayer_whenRightClickIntoTheAir_thenTheItemInHandIsNotUsed() {
        // the server reports air clicks as cancelled already; uncancelled they would still throw the snowball in hand
        PlayerInteractEvent air = using(guarded, Action.RIGHT_CLICK_AIR);
        when(air.isCancelled()).thenReturn(true);
        stages.fire(air);
        verify(air).setCancelled(true);
        verify(runtime).stopsWorldAction(guarded, true);
    }

    @Test void givenArmSwingOrPressurePlate_whenGuarded_thenNoSignOfAHeldButton() {
        PlayerInteractEvent swing = using(guarded, Action.LEFT_CLICK_AIR);
        when(swing.isCancelled()).thenReturn(true);
        stages.fire(swing);
        verify(runtime).stopsWorldAction(guarded, false);
        verify(runtime, never()).stopsWorldAction(guarded, true);
        PlayerInteractEvent plate = stages.fire(using(guarded, Action.PHYSICAL));
        verify(plate, never()).setCancelled(anyBoolean());
    }

    @Test void givenMiningAborted_whenReported_thenTheGuardLearnsTheButtonWasReleased() {
        BlockDamageAbortEvent abort = mock(BlockDamageAbortEvent.class);
        when(abort.getPlayer()).thenReturn(guarded);
        stages.fire(abort);
        verify(runtime).onButtonReleased(guarded);
    }

    @Test void givenScreensNotRunning_whenEventsArrive_thenNothingIsCancelledAndNothingFails() {
        running = false;
        BlockBreakEvent broken = stages.fire(breaking(guarded));
        verify(broken, never()).setCancelled(anyBoolean());
    }

    @Test void givenTheGuard_whenRegistered_thenItDeniesAtTheProtectStage() {
        for (Class<? extends Event> type : List.of(BlockBreakEvent.class, BlockDamageEvent.class, PlayerInteractEvent.class, PlayerInteractEntityEvent.class,
                EntityDamageByEntityEvent.class, HangingBreakByEntityEvent.class, VehicleDamageEvent.class)) {
            List<EventStages.Registration> own = stages.handlers(type).stream().filter(r -> r.owner() == core && r.type() == type).toList();
            assertEquals(1, own.size(), type.getSimpleName());
            assertEquals(EventStage.PROTECT, own.get(0).stage(), type.getSimpleName());
        }
        assertTrue(stages.handlers(PlayerInteractEvent.class).stream().anyMatch(EventStages.Registration::receivesCancelled), "air clicks are seen");
    }
}
