package Vystorm.vystorm_core.playermenu;

import Vystorm.vystorm_core.playermenu.internal.PlayerMenuRegistry;
import Vystorm.vystorm_core.playermenu.internal.PlayerMenuView;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.Plugin;

/**
 * Player menu: a player sneaks and right-clicks another player with an empty main hand and gets a chest menu about
 * that player, filled by every plugin that registered an entry (party invite, city invite, stats, ...). It replaces
 * commands such as {@code /party invite NAME} with one click.
 *
 * <pre>{@code
 * try {
 *     if (CoreApi.has(CoreApi.Capability.PLAYER_MENU)) PartyMenuEntries.register(this);   // separate class, see docs/API.md
 * } catch (LinkageError olderCore) {}
 * }</pre>
 *
 * <p>Core shows a header (the target's head and name) and the visible entries sorted by
 * {@link PlayerMenuEntry#priority()}. The menu only opens when at least one entry is visible, so without plugin entries
 * shift-right-click behaves as before. Entries are removed automatically when their plugin is disabled. Exceptions of
 * an entry hide or cancel only that entry and are logged via {@code ProviderFailures}.
 *
 * <p>Server owners switch the trigger off with {@code player-menu.enabled: false} (or make sneaking optional with
 * {@code player-menu.require-sneak}); players switch it off for themselves in {@code /vsettings}. Permission
 * {@value #PERMISSION} (default: everyone). {@code /menu player <name>} opens the same menu.
 *
 * <p>Registration is thread-safe; {@link #open}, {@link #hasEntries} and all entry callbacks run on the server thread.
 *
 * <p>Preferred access: {@code api.gui().playerMenu()}.
 *
 * @since 0.43.0
 */
public final class PlayerMenu {
    /** Needed to open the menu by shift-right-click or {@code /menu player}; default true. */
    public static final String PERMISSION = "vystorm_core.playermenu";
    /** At most this many entries are shown (five rows below the header); more are cut off by priority. */
    public static final int MAX_ENTRIES = 45;

    /** What happens with the menu after a click. */
    public enum Result {
        /** Close the menu (only if it is still the viewer's open inventory, so a menu you opened stays open). */
        CLOSE,
        /** Draw all entries again (icons and visibility re-evaluated), e.g. after "Invite" turned into "Invited". */
        REFRESH,
        /** Leave the menu as it is. */
        KEEP
    }

    /** Click on an entry. Runs on the server thread one tick after the click; {@code null} counts as {@link Result#KEEP}. */
    @FunctionalInterface
    public interface ClickHandler {
        Result onClick(Player viewer, Player target, ClickType click);
    }

    private PlayerMenu() {}

    /**
     * Adds an entry (or replaces your own entry with the same id).
     *
     * @return the full key {@code <plugin name lowercase>:<id>}
     * @throws IllegalStateException if {@code owner} is not enabled
     */
    public static String register(Plugin owner, PlayerMenuEntry entry) { return PlayerMenuRegistry.get().register(owner, entry); }

    /** Removes one of your entries; {@code false} if it was not registered. */
    public static boolean unregister(Plugin owner, String id) { return PlayerMenuRegistry.get().unregister(owner, id); }

    /** Removes all entries of the plugin; Core does this automatically when the plugin is disabled. */
    public static void unregister(Plugin owner) { PlayerMenuRegistry.get().release(owner); }

    /** Keys of all registered entries ({@code plugin:id}), in menu order (ignores visibility). */
    public static List<String> registered() { return PlayerMenuRegistry.get().keys(); }

    /** Whether at least one entry would be shown to {@code viewer} for {@code target}. Server thread. */
    public static boolean hasEntries(Player viewer, Player target) {
        return !PlayerMenuRegistry.get().render(viewer, target).isEmpty();
    }

    /**
     * Opens the menu about {@code target} for {@code viewer} directly (no sneak, hand, permission or settings check; your
     * own command or NPC can use this). Server thread.
     *
     * @return {@code false} if Core is not running, a player is offline, viewer and target are the same, or no entry is visible
     */
    public static boolean open(Player viewer, Player target) {
        if (viewer == null || target == null || viewer.equals(target) || !viewer.isOnline() || !target.isOnline()) return false;
        var entries = PlayerMenuRegistry.get().render(viewer, target);
        return !entries.isEmpty() && PlayerMenuView.open(viewer, target, entries);
    }
}
