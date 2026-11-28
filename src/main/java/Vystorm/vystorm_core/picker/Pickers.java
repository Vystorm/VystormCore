package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.internal.PickerRuntime;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Core's shared pickers (0.71.0): a plugin opens one from its own panel, chest menu or command and gets the choice
 * back, instead of asking for a typed name. The player picker shows tabs Online / Nearby / Recent / Search with heads,
 * paging, single or multiple choice, the plugin's filter (hidden rows) and rule (greyed rows with a reason). It is a
 * Core panel, so it shows in the client mod, as a Vystorm Screen, or as Core's own 54-slot chest when no screen opens
 * (Spigot, Paper 1.20.6, falling, no pack); the search asks through a Paper dialog or a chat form. Offline names come
 * from the known-player store ({@code api.data().knownPlayers()}).
 * <p>
 * Ends (design rule, owner decision 6): after a choice or Back/ESC the player returns to the calling menu
 * ({@link PickReturn}); after a timeout, damage, a teleport, death or another menu they stay where they are and the
 * plugin hears {@link PlayerPick.EndHandler} with the {@link PickEnd}. One flow per player; a new one replaces the old.
 * <p>
 * World pick (0.71.0, capability {@code world-pick}): {@link #world(PanelAction, WorldPick)} closes the menu and puts
 * the player into a short pick mode without any item. The target under the crosshair (a position, block, wall face,
 * container or entity) is outlined with particles only that player sees, green when the plugin's check allows it and
 * red with the reason in the action bar; a left-click chooses it, sneak opens a small options menu (use where I stand,
 * type coordinates, cancel). While picking, Core cancels the player's block breaking, placing, item use and entity
 * hits at the earliest event stage. Damage and teleports within the world keep the pick; death, a world change,
 * another menu, a timeout or a quit end it and leave the player in the world (with an "Open again" chat button).
 * <p>
 * All calls on the server thread ({@link IllegalStateException} otherwise). Preferred access:
 * {@code api.gui().pickers()}. Capability {@code player-picker}. Keep picker code in a class that is only loaded after
 * {@code CoreApi.has("player-picker")}, so the plugin still runs on older Cores.
 *
 * @since 0.71.0
 */
public final class Pickers {
    private Pickers() {}

    private static PickerRuntime runtime() {
        PickerRuntime r = PickerRuntime.get();
        if (r == null) throw new IllegalStateException("Pickers are not running (Core not enabled)");
        return r;
    }

    /**
     * From a panel handler: closes the panel, opens the player picker two ticks later and comes back to the same panel
     * (same argument, the session values carried over, the handler's message as the status line). Returns
     * {@code Result.ok()}, or an error when pickers are switched off; write {@code return pickers.players(action, pick);}.
     */
    public static Result players(PanelAction from, PlayerPick pick) { return runtime().players(from, pick); }

    /** From a chest menu, a command or anything else: {@code back} says where the player goes afterwards. */
    public static PickFlow players(Plugin owner, Player viewer, PlayerPick pick, PickReturn back) { return runtime().players(owner, viewer, pick, back); }

    /**
     * From a panel handler: closes the panel and puts the player into pick mode two ticks later (no item: the target
     * under the crosshair is outlined, left-click chooses it, sneak opens the options). A choice or Cancel comes back to
     * the same panel (same argument, the session values carried over, the handler's message as the status line).
     * Returns {@code Result.ok()}, or an error when world picks are switched off. Capability {@code world-pick}.
     */
    public static Result world(PanelAction from, WorldPick pick) { return runtime().world(from, pick); }

    /** From a chest menu, a command or anything else: {@code back} says where the player goes afterwards. */
    public static PickFlow world(Plugin owner, Player viewer, WorldPick pick, PickReturn back) { return runtime().world(owner, viewer, pick, back); }

    /** The flow this player is in, if any (a player picker or a world pick). */
    public static Optional<PickFlow> active(Player viewer) {
        PickerRuntime r = PickerRuntime.get();
        return r == null ? Optional.empty() : r.active(viewer);
    }

    /** Ends the player's flow with {@link PickEnd#CANCELLED_BY_PLUGIN}; false if there is none. */
    public static boolean cancel(Player viewer) {
        PickerRuntime r = PickerRuntime.get();
        return r != null && r.cancel(viewer);
    }

    /** Whether pickers can open: Core runs and {@code pickers.enabled} is on. */
    public static boolean available() {
        PickerRuntime r = PickerRuntime.get();
        return r != null && r.available();
    }

    /** Core lifecycle: registers the picker panel and its listeners. Not for plugins. */
    public static void start(Plugin core) { PickerRuntime.start(core); }

    /** Core lifecycle: ends every flow. Not for plugins. */
    public static void shutdown() { PickerRuntime.shutdown(); }

    /** Core lifecycle: ends the flows of a disabled plugin. Not for plugins. */
    public static void release(Plugin owner) { PickerRuntime.release(owner); }
}
