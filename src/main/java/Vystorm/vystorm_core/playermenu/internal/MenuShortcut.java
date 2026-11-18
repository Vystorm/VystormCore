package Vystorm.vystorm_core.playermenu.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Core menu shortcut (since 0.57.0): hold sneak and left-click twice into the air within {@link #DOUBLE_MILLIS} → {@code /menu}.
 * Not while a Vystorm screen is open; config {@code menu.sneak-click-shortcut} (off by default since 0.65.1).
 * <p>0.65.1: clicks on blocks no longer count. A held button that mines or breaks block after block while sneaking
 * (sneak-building, creative breaking every 6 ticks, instant-break blocks in survival) sent a block click every 250 to
 * 300 ms and opened the menu in the middle of building. One click that reaches the server twice (arm swing plus
 * another signal) counts once.
 */
public final class MenuShortcut implements Listener {
    static final long DOUBLE_MILLIS = 250;
    /** Two signals closer than this are one click (0.65.1); a human double click is slower. */
    static final long SAME_CLICK_MILLIS = 80;

    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    private final Predicate<Player> blocked;
    private final Map<UUID, Long> lastLeft = new HashMap<>();

    public MenuShortcut(LongSupplier clock, BooleanSupplier enabled, Predicate<Player> blocked) {
        this.clock = clock;
        this.enabled = enabled;
        this.blocked = blocked;
    }

    /** A left click while sneaking; returns true for the second click of a fast pair (consumes the pair). */
    boolean leftClick(UUID player) {
        long now = clock.getAsLong();
        Long before = lastLeft.get(player);
        if (before != null && now - before < SAME_CLICK_MILLIS) return false;
        lastLeft.put(player, now);
        if (before != null && now - before <= DOUBLE_MILLIS) {
            lastLeft.remove(player);
            return true;
        }
        return false;
    }

    void forget(UUID player) { lastLeft.remove(player); }

    /** Only clicks into the air count (0.65.1): a click on a block is building or mining. */
    static boolean counts(Action action) { return action == Action.LEFT_CLICK_AIR; }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!counts(event.getAction())) return;
        Player player = event.getPlayer();
        if (!player.isSneaking() || !enabled.getAsBoolean() || blocked.test(player)) return;
        if (!leftClick(player.getUniqueId())) return;
        event.setCancelled(true);
        player.performCommand("menu");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }
}
