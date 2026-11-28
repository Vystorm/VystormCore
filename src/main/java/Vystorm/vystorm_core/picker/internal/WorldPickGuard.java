package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.screen.internal.ScreenDispatch;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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

/**
 * Keeps a picking player's clicks out of the world (design 4.4). While a world pick runs, these actions of that player
 * are cancelled at stage {@link EventStage#PREPARE}, before any other stage sees them (and again at FINISH, so no
 * later stage undoes it): block break, start of mining,
 * place, use of a block or the held item (pressure plates left alone), use of entities (also armor stands), hits on
 * entities by the player or the player's projectiles, breaking paintings and frames, damage to vehicles, buckets. The
 * click events (interact, entity use, melee hit) are also handed to the pick as triggers. Events fired while Core runs
 * a screen or dialog handler ({@link ScreenDispatch#active()}) pass: plugin code made them (protection probes), not
 * the player. Core-internal.
 */
public final class WorldPickGuard {
    private WorldPickGuard() {}

    /**
     * @param picking  whether this player is in a world pick
     * @param trigger  a click of the player (the boolean is true for an arm swing; always false here)
     */
    public static void register(EventStages stages, Plugin core, Predicate<Player> picking, BiConsumer<Player, Boolean> trigger) {
        at(stages, core, EventStage.PREPARE, picking, trigger);
        // again at FINISH: a later stage (a region "allow" flag) must not undo the cancel while the player picks
        at(stages, core, EventStage.FINISH, picking, (p, swing) -> { });
    }

    private static void at(EventStages stages, Plugin core, EventStage stage, Predicate<Player> picking, BiConsumer<Player, Boolean> trigger) {
        stages.handle(core, BlockBreakEvent.class, stage, e -> {
            if (stops(picking, e.getPlayer())) e.setCancelled(true);
        });
        stages.handle(core, BlockDamageEvent.class, stage, e -> {
            if (stops(picking, e.getPlayer())) e.setCancelled(true);
        });
        stages.handle(core, BlockPlaceEvent.class, stage, e -> {
            if (stops(picking, e.getPlayer())) e.setCancelled(true);
        });
        // clicks into the air count as cancelled from the start but would still use the held item: see them too
        stages.handle(core, PlayerInteractEvent.class, stage, true, e -> {
            if (e.getAction() == Action.PHYSICAL || !stops(picking, e.getPlayer())) return;
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
            trigger.accept(e.getPlayer(), false);
        });
        stages.handle(core, PlayerInteractEntityEvent.class, stage, true, e -> {
            if (!stops(picking, e.getPlayer())) return;
            e.setCancelled(true);
            trigger.accept(e.getPlayer(), false);
        });
        stages.handle(core, PlayerInteractAtEntityEvent.class, stage, true, e -> {
            if (!stops(picking, e.getPlayer())) return;
            e.setCancelled(true);
            trigger.accept(e.getPlayer(), false);
        });
        stages.handle(core, PlayerArmorStandManipulateEvent.class, stage, e -> {
            if (stops(picking, e.getPlayer())) e.setCancelled(true);
        });
        stages.handle(core, EntityDamageByEntityEvent.class, stage, true, e -> {
            if (e.getDamager() instanceof Player player) {
                if (!stops(picking, player)) return;
                e.setCancelled(true);
                trigger.accept(player, false);
            } else if (e.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player player && stops(picking, player)) {
                e.setCancelled(true);
            }
        });
        stages.handle(core, HangingBreakByEntityEvent.class, stage, e -> {
            if (e.getRemover() instanceof Player player && stops(picking, player)) e.setCancelled(true);
        });
        stages.handle(core, VehicleDamageEvent.class, stage, e -> {
            if (e.getAttacker() instanceof Player player && stops(picking, player)) e.setCancelled(true);
        });
        stages.handle(core, PlayerBucketEmptyEvent.class, stage, e -> {
            if (stops(picking, e.getPlayer())) e.setCancelled(true);
        });
        stages.handle(core, PlayerBucketFillEvent.class, stage, e -> {
            if (stops(picking, e.getPlayer())) e.setCancelled(true);
        });
    }

    static boolean stops(Predicate<Player> picking, Player player) {
        if (player == null || ScreenDispatch.active()) return false;
        try { return picking.test(player); } catch (RuntimeException notRunning) { return false; }
    }
}
