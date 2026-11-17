package Vystorm.vystorm_core.region;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Löst {@link RegionEnterEvent}/{@link RegionLeaveEvent} aus, wenn ein Spieler den Block wechselt. */
final class RegionTracker implements Listener {
    private final RegionService service;
    private final Map<UUID, Map<String, Region>> inside = new HashMap<>();

    RegionTracker(RegionService service) { this.service = service; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) return;
        update(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { update(event.getPlayer(), event.getTo()); }

    @EventHandler public void onJoin(PlayerJoinEvent event) { update(event.getPlayer(), event.getPlayer().getLocation()); }

    @EventHandler public void onQuit(PlayerQuitEvent event) { inside.remove(event.getPlayer().getUniqueId()); }

    void update(Player player, Location location) {
        var now = new LinkedHashMap<String, Region>();
        for (Region region : service.at(location)) now.put(region.key(), region);
        var before = inside.getOrDefault(player.getUniqueId(), Map.of());
        if (before.keySet().equals(now.keySet())) { if (!now.isEmpty()) inside.put(player.getUniqueId(), now); return; }
        for (var entry : before.entrySet()) if (!now.containsKey(entry.getKey())) Bukkit.getPluginManager().callEvent(new RegionLeaveEvent(player, entry.getValue()));
        for (var entry : now.entrySet()) if (!before.containsKey(entry.getKey())) Bukkit.getPluginManager().callEvent(new RegionEnterEvent(player, entry.getValue()));
        if (now.isEmpty()) inside.remove(player.getUniqueId()); else inside.put(player.getUniqueId(), now);
    }
}
