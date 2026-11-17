package Vystorm.vystorm_core.player;

import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Lightweight shared player-activity state; event-driven with no repeating scan task. */
public final class PlayerActivityService implements Listener, RuntimeContextHub.Provider {
    public enum Activity {
        IDLE, TRAVELLING, EXPLORING, MINING, FARMING, BUILDING, CRAFTING, TRADING,
        FISHING, CITY, MARKET, HOME, DUNGEON_EXPLORING, DUNGEON_COMBAT, DUNGEON_BOSS,
        ARENA, PVP, COMBAT, LOOTING, ESCAPING, RESTING
    }

    public record State(Activity primary, Set<String> flags, long since, long expiresAt, long revision) {
        public State {
            Objects.requireNonNull(primary);
            flags = Set.copyOf(Objects.requireNonNull(flags));
        }
        public boolean expired(long now) { return expiresAt > 0 && now >= expiresAt; }
    }

    private final Plugin owner;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public PlayerActivityService(Plugin owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    public State state(UUID playerId) {
        long now = System.currentTimeMillis();
        State current = states.get(playerId);
        if (current == null || current.expired(now)) return new State(Activity.IDLE, Set.of(), now, 0L, current == null ? 0L : current.revision() + 1L);
        return current;
    }

    public State signal(UUID playerId, Activity activity, long holdMillis, String... flags) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(activity);
        long now = System.currentTimeMillis();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (flags != null) for (String flag : flags) if (flag != null && !flag.isBlank()) normalized.add(flag.trim().toUpperCase(Locale.ROOT));
        State previous = states.get(playerId);
        long revision = previous == null ? 1L : previous.revision() + 1L;
        State next = new State(activity, normalized, now, holdMillis <= 0 ? 0L : now + holdMillis, revision);
        states.put(playerId, next);
        return next;
    }

    public void addFlag(UUID playerId, String flag, long holdMillis) {
        State current = state(playerId);
        LinkedHashSet<String> flags = new LinkedHashSet<>(current.flags());
        if (flag != null && !flag.isBlank()) flags.add(flag.trim().toUpperCase(Locale.ROOT));
        signal(playerId, current.primary(), holdMillis, flags.toArray(String[]::new));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Material material = event.getBlock().getType();
        String name = material.name();
        if (isCrop(material)) signal(event.getPlayer().getUniqueId(), Activity.FARMING, 20_000L, "BLOCK_BREAK");
        else if (name.contains("ORE") || name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("NETHERRACK"))
            signal(event.getPlayer().getUniqueId(), Activity.MINING, 20_000L, "BLOCK_BREAK", "RESOURCE_ACTIVITY");
        else signal(event.getPlayer().getUniqueId(), Activity.BUILDING, 12_000L, "BLOCK_BREAK");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        signal(event.getPlayer().getUniqueId(), Activity.BUILDING, 15_000L, "BLOCK_PLACE");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        if (dx * dx + dz * dz < 0.04D) return;
        State current = state(event.getPlayer().getUniqueId());
        long now = System.currentTimeMillis();
        if (current.primary() == Activity.COMBAT || current.primary() == Activity.PVP || current.primary() == Activity.DUNGEON_COMBAT
                || (current.expiresAt() > now + 3_000L && current.primary() != Activity.IDLE && current.primary() != Activity.TRAVELLING)) return;
        // Beim Reisen höchstens etwa einmal pro Sekunde verlängern statt bei jedem Move-Paket.
        if (current.primary() == Activity.TRAVELLING && current.expiresAt() > now + 4_000L) return;
        signal(event.getPlayer().getUniqueId(), Activity.TRAVELLING, 5_000L, "MOVING");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim) {
            signal(victim.getUniqueId(), Activity.COMBAT, 12_000L, "RECENT_COMBAT", "DEFENDING");
        }
        if (event.getDamager() instanceof Player attacker) {
            signal(attacker.getUniqueId(), Activity.COMBAT, 12_000L, "RECENT_COMBAT", "ATTACKING");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory() instanceof CraftingInventory) signal(player.getUniqueId(), Activity.CRAFTING, 8_000L, "INVENTORY_ACTIVITY");
        else if (event.getInventory() instanceof MerchantInventory) signal(player.getUniqueId(), Activity.TRADING, 8_000L, "INVENTORY_ACTIVITY");
        else signal(player.getUniqueId(), Activity.LOOTING, 5_000L, "INVENTORY_ACTIVITY");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof org.bukkit.inventory.Merchant) {
            signal(event.getPlayer().getUniqueId(), Activity.TRADING, 8_000L, "NPC_INTERACTION");
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { states.remove(event.getPlayer().getUniqueId()); }

    @Override public String id() { return "core-player-activity"; }
    @Override public Set<RuntimeContextHub.Channel> channels() { return Set.of(RuntimeContextHub.Channel.PLAYER); }
    @Override public int priority() { return 1000; }
    @Override public long revision() { return states.values().stream().mapToLong(State::revision).sum(); }
    @Override public boolean supports(RuntimeContextHub.Channel channel, RuntimeContextHub.Query query) { return query.playerId().isPresent(); }

    @Override
    public RuntimeContextHub.Snapshot snapshot(RuntimeContextHub.Channel channel, RuntimeContextHub.Query query) {
        State state = state(query.playerId().orElseThrow());
        LinkedHashSet<String> tags = new LinkedHashSet<>(state.flags());
        tags.add("ACTIVITY:" + state.primary().name());
        return new RuntimeContextHub.Snapshot(id(), channel, priority(), state.revision(), tags,
                Map.of("activity", state.primary().name(), "activitySince", Long.toString(state.since())), Map.of());
    }

    public void shutdown() { states.clear(); }

    private static boolean isCrop(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, SWEET_BERRY_BUSH, PUMPKIN, MELON -> true;
            default -> false;
        };
    }
}
