package Vystorm.vystorm_core.playermenu;

import Vystorm.vystorm_core.playermenu.internal.PlayerMenuRegistry;
import Vystorm.vystorm_core.playermenu.internal.PlayerMenuView;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.playermenu.PlayerMenu.*;

/**
 * Manager view of {@link PlayerMenu}, reached through {@code api.gui().playerMenu()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link PlayerMenu}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface PlayerMenuInterface {
    /** The delegating default. Plugins use {@code api.gui().playerMenu()}. */
    PlayerMenuInterface DEFAULT = new PlayerMenuInterface() {};

    /**
     * Adds an entry (or replaces your own entry with the same id).
     *
     * @return the full key {@code <plugin name lowercase>:<id>}
     * @throws IllegalStateException if {@code owner} is not enabled
     */
    default String register(Plugin owner, PlayerMenuEntry entry) { return PlayerMenu.register(owner, entry); }

    /** Removes one of your entries; {@code false} if it was not registered. */
    default boolean unregister(Plugin owner, String id) { return PlayerMenu.unregister(owner, id); }

    /** Removes all entries of the plugin; Core does this automatically when the plugin is disabled. */
    default void unregister(Plugin owner) { PlayerMenu.unregister(owner); }

    /** Keys of all registered entries ({@code plugin:id}), in menu order (ignores visibility). */
    default List<String> registered() { return PlayerMenu.registered(); }

    /** Whether at least one entry would be shown to {@code viewer} for {@code target}. Server thread. */
    default boolean hasEntries(Player viewer, Player target) { return PlayerMenu.hasEntries(viewer, target); }

    /**
     * Opens the menu about {@code target} for {@code viewer} directly (no sneak, hand, permission or settings check; your
     * own command or NPC can use this). Server thread.
     *
     * @return {@code false} if Core is not running, a player is offline, viewer and target are the same, or no entry is visible
     */
    default boolean open(Player viewer, Player target) { return PlayerMenu.open(viewer, target); }
}
