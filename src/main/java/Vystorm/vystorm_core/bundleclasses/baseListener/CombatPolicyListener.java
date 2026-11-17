package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.combat.CombatRegistry;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Applies registered Core combat policies around the normal Vystorm protection layer.
 * Events already cancelled before NORMAL are never revived by an ALLOW policy.
 */
public final class CombatPolicyListener implements Listener {
    private final Map<EntityDamageByEntityEvent, Boolean> initiallyCancelled = new IdentityHashMap<>();

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void snapshot(EntityDamageByEntityEvent event) {
        if (players(event.getDamager(), event.getEntity()) != null) {
            initiallyCancelled.put(event, event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void apply(EntityDamageByEntityEvent event) {
        Player[] pair = players(event.getDamager(), event.getEntity());
        if (pair == null) return;
        boolean preCancelled = initiallyCancelled.remove(event) == Boolean.TRUE;
        CombatRegistry.Decision decision = CombatRegistry.evaluate(pair[0], pair[1]);
        if (decision == CombatRegistry.Decision.DENY) {
            event.setCancelled(true);
        } else if (decision == CombatRegistry.Decision.ALLOW && !preCancelled) {
            event.setCancelled(false);
        }
    }

    private static Player[] players(Entity attacker, Entity victim) {
        if (!(victim instanceof Player target)) return null;
        Player source = playerSource(attacker);
        if (source == null || source.getUniqueId().equals(target.getUniqueId())) return null;
        return new Player[] {source, target};
    }

    private static Player playerSource(Entity entity) {
        Entity source = entity;
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
        else if (source instanceof TNTPrimed tnt && tnt.getSource() != null) source = tnt.getSource();
        else if (source instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity owner) source = owner;
        if (source instanceof Player player) return player;
        if (source instanceof Tameable tame && tame.getOwner() instanceof Player player) return player;
        return null;
    }
}
