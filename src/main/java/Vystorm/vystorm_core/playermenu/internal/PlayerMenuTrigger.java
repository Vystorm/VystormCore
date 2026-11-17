package Vystorm.vystorm_core.playermenu.internal;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Opens the player menu on sneak + right-click on another real player with an empty main hand. The event is only
 * cancelled when the menu actually opens (at least one visible entry), so vanilla and other plugins keep every other
 * interaction. Runs at {@code HIGH} and ignores events another plugin already cancelled. Not public API.
 */
public final class PlayerMenuTrigger implements Listener {
    /** Player preference id ({@code /vsettings}) and section id. */
    public static final String PREF = "player-menu";
    static final long COOLDOWN_MILLIS = 750L;

    /** Why the trigger did or did not open the menu (tests and debug). */
    public enum Decision {
        OPEN, DISABLED, OFF_HAND, NOT_A_PLAYER, NPC, SELF, NOT_SNEAKING, HAND_NOT_EMPTY, SPECTATOR, NO_PERMISSION,
        OPTED_OUT, COOLDOWN, NO_ENTRIES, OPEN_FAILED
    }

    /** Opens the rendered menu; {@code PlayerMenuView::open} in production. */
    @FunctionalInterface
    public interface Opener {
        boolean open(Player viewer, Player target, List<PlayerMenuRegistry.Rendered> entries);
    }

    private final Plugin core;
    private final PlayerMenuRegistry registry;
    private final Predicate<Player> optedIn;
    private final LongSupplier clock;
    private final Opener opener;
    private final Map<UUID, Long> lastOpen = new ConcurrentHashMap<>();

    public PlayerMenuTrigger(Plugin core, PlayerMenuRegistry registry, Predicate<Player> optedIn, LongSupplier clock, Opener opener) {
        this.core = core;
        this.registry = registry;
        this.optedIn = optedIn;
        this.clock = clock;
        this.opener = opener;
    }

    /** Registers the listener and the personal toggle in {@code /vsettings}. Call once in Core's {@code onEnable}. */
    public static void start(Plugin core, API api) {
        PlayerMenuTrigger trigger = new PlayerMenuTrigger(core, PlayerMenuRegistry.get(),
                player -> PlayerPrefs.bool(core, player, PREF, true), System::currentTimeMillis, PlayerMenuView::open);
        core.getServer().getPluginManager().registerEvents(trigger, core);
        try {
            var lang = Localization.get().core();
            api.settings().register(core, SettingsSection.player(PREF, lang.ref("player-menu.settings.title"),
                    lang.ref("player-menu.settings.description"),
                    List.of(PlayerPrefs.toggle(core, PREF, lang.ref("player-menu.settings.toggle"),
                            lang.ref("player-menu.settings.toggle-hint"), true))));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[PlayerMenu] Player setting not registered", failure);
        }
        DebugMessages.mid("[PlayerMenu] Shift-right-click player menu ready");
    }

    /** Whether the server switched the menu on ({@code player-menu.enabled}). */
    public static boolean enabled(Plugin core) { return core.getConfig().getBoolean("player-menu.enabled", true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) { handle(event); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { lastOpen.remove(event.getPlayer().getUniqueId()); }

    Decision handle(PlayerInteractEntityEvent event) {
        Decision decision = decide(event);
        if (decision != Decision.OPEN) return decision;
        Player viewer = event.getPlayer();
        Player target = (Player) event.getRightClicked();
        List<PlayerMenuRegistry.Rendered> entries = registry.render(viewer, target);
        if (entries.isEmpty()) return Decision.NO_ENTRIES;
        event.setCancelled(true);
        lastOpen.put(viewer.getUniqueId(), clock.getAsLong());
        try {
            return opener.open(viewer, target, entries) ? Decision.OPEN : Decision.OPEN_FAILED;
        } catch (RuntimeException failure) {
            DebugMessages.high("[PlayerMenu] Opening the player menu failed", failure);
            return Decision.OPEN_FAILED;
        }
    }

    /** All cheap checks in order; {@link Decision#OPEN} means "render the entries and open if any is visible". */
    Decision decide(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return Decision.OFF_HAND;
        if (!(event.getRightClicked() instanceof Player target)) return Decision.NOT_A_PLAYER;
        Player viewer = event.getPlayer();
        if (!enabled(core)) return Decision.DISABLED;
        if (viewer.getUniqueId().equals(target.getUniqueId())) return Decision.SELF;
        if (npc(target)) return Decision.NPC;
        if (core.getConfig().getBoolean("player-menu.require-sneak", true) && !viewer.isSneaking()) return Decision.NOT_SNEAKING;
        ItemStack hand = viewer.getInventory().getItemInMainHand();
        if (hand != null && !Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(hand)) return Decision.HAND_NOT_EMPTY;
        if (viewer.getGameMode() == GameMode.SPECTATOR) return Decision.SPECTATOR;
        if (!viewer.hasPermission(PlayerMenu.PERMISSION)) return Decision.NO_PERMISSION;
        if (!optedIn.test(viewer)) return Decision.OPTED_OUT;
        Long last = lastOpen.get(viewer.getUniqueId());
        if (last != null && clock.getAsLong() - last < COOLDOWN_MILLIS) return Decision.COOLDOWN;
        return Decision.OPEN;
    }

    /** Citizens-style NPCs carry "NPC" metadata or are not among the online players. */
    static boolean npc(Player target) {
        if (target.hasMetadata("NPC")) return true;
        return Bukkit.getPlayer(target.getUniqueId()) != target;
    }
}
