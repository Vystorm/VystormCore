package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.ExitReason;
import Vystorm.vystorm_core.worlds.Protection;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.TimeSkipEvent;

/**
 * Immer registriert (auch ohne Plugin-Welten, Review I2): Erholung beim Login, Quit, Befehls-Erlaubnisliste,
 * Teleports, Portale, Tod/Respawn, Sperren für Besucher und der Weltschutz der Plugin-Welten.
 */
final class WorldsListener implements Listener {
    private final VisitEngine visits;
    private final WorldLookup worlds;
    private final VisitEnv env;
    private final java.util.Set<PlayerCommandPreprocessEvent> quietCommands = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    WorldsListener(VisitEngine visits, WorldLookup worlds, VisitEnv env) {
        this.visits = visits;this.worlds = worlds;this.env = env;
    }

    // ---- Erholung und Lebenszyklus ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) { visits.recover(event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) { visits.exit(event.getPlayer(), ExitReason.QUIT); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommandPreCancel(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) { quietCommands.add(event);return; }
        if (visits.commandAllowed(event.getPlayer(), event.getMessage())) return;
        event.setCancelled(true);
        quietCommands.add(event);
        env.message(event.getPlayer(), "worlds.visit.command-blocked");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        boolean quiet = quietCommands.remove(event);
        if (visits.commandAllowed(event.getPlayer(), event.getMessage())) return;
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (!quiet && !alreadyCancelled) env.message(event.getPlayer(), "worlds.visit.command-blocked");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_GATEWAY && protects(event.getFrom().getWorld(), Protection.PORTALS)) {
            event.setCancelled(true);
            visits.portal(player);
            return;
        }
        if (visits.teleportAllowed(player, event.getFrom(), event.getTo())) return;
        event.setCancelled(true);
        env.message(player, "worlds.visit.teleport-blocked");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntryTeleport(PlayerTeleportEvent event) { visits.invalidateEntry(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) { visits.changedWorld(event.getPlayer(), event.getFrom()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(PlayerPortalEvent event) {
        if (!protects(event.getFrom().getWorld(), Protection.PORTALS)) return;
        event.setCancelled(true);
        visits.portal(event.getPlayer());
    }

    @EventHandler
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (event.getEntity() instanceof Player player && protects(player.getWorld(), Protection.PORTALS)) visits.portal(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        var location = visits.respawn(event.getPlayer());
        if (location != null) event.setRespawnLocation(location);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        visits.invalidateEntry(event.getEntity());
        if (!visits.inVisit(event.getEntity().getUniqueId())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onParty(Vystorm.vystorm_core.party.PartyChangeEvent event) { env.nextTick(visits::refreshAllVisibility); }

    // ---- Sperren für Besucher und Weltschutz ----

    private boolean protects(World world, Protection protection) {
        ManagedWorldImpl managed = worlds.byWorld(world);
        return managed != null && managed.spec().protects(protection);
    }

    /** Besucher mit gesperrtem Inventar. */
    private boolean locked(Player player) {
        ManagedWorldImpl world = visits.visitWorld(player);
        return world != null && world.spec().policy().lockInventory();
    }

    /** Schutz der Welt greift für diesen Spieler (Admin-Ausnahme beachtet). */
    private boolean guarded(Player player, World world, Protection protection) {
        ManagedWorldImpl managed = worlds.byWorld(world);
        return managed != null && managed.spec().protects(protection) && !visits.bypass(player, managed);
    }

    private static void cancel(Cancellable event) { event.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (locked(player) || guarded(player, player.getWorld(), Protection.EDITS)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) { if (locked(event.getPlayer()) || guarded(event.getPlayer(), event.getBlock().getWorld(), Protection.EDITS)) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) { if (locked(event.getPlayer()) || guarded(event.getPlayer(), event.getBlock().getWorld(), Protection.EDITS)) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucket(PlayerBucketEmptyEvent event) { if (locked(event.getPlayer()) || guarded(event.getPlayer(), event.getBlock().getWorld(), Protection.EDITS)) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) { if (locked(event.getPlayer()) || guarded(event.getPlayer(), event.getBlock().getWorld(), Protection.EDITS)) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHanging(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null ? locked(player) || guarded(player, event.getEntity().getWorld(), Protection.EDITS) : protects(event.getEntity().getWorld(), Protection.EDITS)) cancel(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (locked(event.getPlayer()) || guarded(event.getPlayer(), event.getPlayer().getWorld(), Protection.ENTITY_INTERACT)) cancel(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (locked(event.getPlayer()) || guarded(event.getPlayer(), event.getPlayer().getWorld(), Protection.ENTITY_INTERACT)) cancel(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (locked(event.getPlayer()) || guarded(event.getPlayer(), event.getPlayer().getWorld(), Protection.ENTITY_INTERACT)) cancel(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) { if (locked(event.getPlayer())) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && locked(player)) { cancel(event);return; }
        if (protects(entity.getWorld(), Protection.ITEM_PICKUP) && !(entity instanceof Player p && visits.bypass(p, worlds.byWorld(entity.getWorld())))) cancel(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player player && locked(player)) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) { if (event.getWhoClicked() instanceof Player player && locked(player)) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) { if (locked(event.getPlayer())) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) { if (locked(event.getPlayer())) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && locked(player)) { cancel(event);return; }
        if (protects(event.getEntity().getWorld(), Protection.PROJECTILES)) cancel(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) { if (protects(event.getEntity().getWorld(), Protection.DAMAGE)) cancel(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent event) { if (protects(event.getEntity().getWorld(), Protection.HUNGER)) cancel(event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent event) { if (protects(event.getEntity().getWorld(), Protection.MOB_SPAWNS)) cancel(event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWeather(WeatherChangeEvent event) { if (event.toWeatherState() && protects(event.getWorld(), Protection.WEATHER)) cancel(event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onThunder(ThunderChangeEvent event) { if (event.toThunderState() && protects(event.getWorld(), Protection.WEATHER)) cancel(event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTimeSkip(TimeSkipEvent event) { if (protects(event.getWorld(), Protection.TIME_SKIP)) cancel(event); }
}
