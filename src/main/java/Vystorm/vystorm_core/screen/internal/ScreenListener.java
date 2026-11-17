package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.screen.ScreenInput;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Bukkit events → {@link ScreenRuntime} (since 0.57.0). Does nothing for players without an open screen. */
public final class ScreenListener implements Listener {
    private ScreenRuntime runtime() { return ScreenRuntime.get(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getTo() == null || !runtime().hasSession(e.getPlayer())) return;
        if (e.getFrom().getYaw() == e.getTo().getYaw() && e.getFrom().getPitch() == e.getTo().getPitch()) return;
        runtime().onRotate(e.getPlayer(), e.getTo().getYaw(), e.getTo().getPitch());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void interact(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) { if (runtime().hasSession(e.getPlayer())) e.setCancelled(true); return; }
        if (runtime().onClick(e.getPlayer(), e.getRightClicked(), true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void attack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && runtime().hasSession(p)) {
            e.setCancelled(true);
            runtime().onClick(p, e.getEntity(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) runtime().onSwing(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void scroll(PlayerItemHeldEvent e) {
        if (!runtime().hasSession(e.getPlayer())) return;
        int delta = e.getNewSlot() - e.getPreviousSlot();
        if (delta > 4) delta -= 9;
        if (delta < -4) delta += 9;
        e.setCancelled(true);
        runtime().onScroll(e.getPlayer(), Integer.signum(delta));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void sneak(PlayerToggleSneakEvent e) {
        if (runtime().onSneak(e.getPlayer(), e.isSneaking())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void dismount(EntityDismountEvent e) {
        if (e.getEntity() instanceof Player p && runtime().holdsMount(p, e.getDismounted())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) runtime().onDamage(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) { runtime().close(e.getEntity(), ScreenInput.Reason.DEATH); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        if (runtime().hasSession(e.getPlayer()) && e.getCause() != PlayerTeleportEvent.TeleportCause.DISMOUNT)
            runtime().close(e.getPlayer(), ScreenInput.Reason.TELEPORT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent e) { runtime().close(e.getPlayer(), ScreenInput.Reason.WORLD); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { runtime().close(e.getPlayer(), ScreenInput.Reason.QUIT); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void load(EntitiesLoadEvent e) { runtime().sweep(e.getEntities()); }
}
