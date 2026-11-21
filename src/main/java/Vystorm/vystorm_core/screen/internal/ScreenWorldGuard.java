package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.plugin.Plugin;

/**
 * Keeps screen clicks out of the world (since 0.65.0). A screen click is handled when the mouse button goes down. When
 * it closes the screen or opens another one, the click box in front of the player is gone while the button is still
 * down, and the client then breaks, mines, uses or hits whatever is behind the screen. While a screen is open and for
 * a short guard time after such a click ({@link ScreenRuntime#stopsWorldAction}), these actions of the player are
 * cancelled: block break, start of mining, use of a block or item, use of and hits on entities.
 * <p>
 * Since 0.66.1 events that fire while Core runs a screen or dialog input handler ({@link ScreenDispatch#active()}) are
 * never stopped: they come from plugin code, for example a synthetic interact or break event a plugin fires to ask the
 * protection plugins, not from the player's input.
 * <p>
 * Registered through Core's event stages at {@link EventStage#PROTECT}, so plugins that act on these events in later
 * stages never see the stray click. Clicks on the screen's own click box are handled by {@link ScreenListener}.
 */
public final class ScreenWorldGuard {
    private ScreenWorldGuard() {}

    public static void register(EventStages stages, Plugin core, Supplier<ScreenRuntime> runtime) {
        stages.handle(core, BlockBreakEvent.class, EventStage.PROTECT, e -> {
            if (stops(runtime, e.getPlayer(), true)) e.setCancelled(true);
        });
        stages.handle(core, BlockDamageEvent.class, EventStage.PROTECT, e -> {
            if (stops(runtime, e.getPlayer(), true)) e.setCancelled(true);
        });
        // clicks into the air count as cancelled from the start but would still use the item in hand: see them too
        stages.handle(core, PlayerInteractEvent.class, EventStage.PROTECT, true, e -> {
            if (e.getAction() == Action.PHYSICAL) return;
            // a click into the air is also what the server makes of a bare arm swing: no sign of a held button
            if (stops(runtime, e.getPlayer(), e.getAction() != Action.LEFT_CLICK_AIR)) e.setCancelled(true);
        });
        stages.handle(core, PlayerInteractEntityEvent.class, EventStage.PROTECT, e -> {
            if (stops(runtime, e.getPlayer(), true)) e.setCancelled(true);
        });
        stages.handle(core, EntityDamageByEntityEvent.class, EventStage.PROTECT, e -> {
            if (e.getDamager() instanceof Player player && stops(runtime, player, true)) e.setCancelled(true);
        });
        stages.handle(core, HangingBreakByEntityEvent.class, EventStage.PROTECT, e -> {
            if (e.getRemover() instanceof Player player && stops(runtime, player, true)) e.setCancelled(true);
        });
        stages.handle(core, VehicleDamageEvent.class, EventStage.PROTECT, e -> {
            if (e.getAttacker() instanceof Player player && stops(runtime, player, true)) e.setCancelled(true);
        });
        // The client tells the server when mining stops (button let go, or another block aimed at): the guard may end.
        try {
            stages.handle(core, org.bukkit.event.block.BlockDamageAbortEvent.class, EventStage.OBSERVE, e -> {
                ScreenRuntime screens = running(runtime);
                if (screens != null) screens.onButtonReleased(e.getPlayer());
            });
        } catch (LinkageError | RuntimeException olderServer) { /* the guard then ends by time only */ }
    }

    private static boolean stops(Supplier<ScreenRuntime> runtime, Player player, boolean buttonDown) {
        // 0.66.1: an event fired while Core runs a screen or dialog handler was made by a plugin (a protection probe
        // such as Gallery's ProtectionProbe), never by the player's own input: let it through
        if (ScreenDispatch.active()) return false;
        ScreenRuntime screens = running(runtime);
        return screens != null && player != null && screens.stopsWorldAction(player, buttonDown);
    }

    private static ScreenRuntime running(Supplier<ScreenRuntime> runtime) {
        try { return runtime.get(); } catch (IllegalStateException notRunning) { return null; }
    }
}
