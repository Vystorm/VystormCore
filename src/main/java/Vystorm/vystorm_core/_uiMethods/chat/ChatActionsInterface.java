package Vystorm.vystorm_core._uiMethods.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Manager view of {@link ChatActions}, reached through {@code api.message().chat()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ChatActions}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ChatActionsInterface {
    /** The delegating default. Plugins use {@code api.message().chat()}. */
    ChatActionsInterface DEFAULT = new ChatActionsInterface() {};

    /** Converts a legacy {@code §}-formatted text so actions can be appended. */
    default TextComponent text(String legacy) { return ChatActions.text(legacy); }

    /** Coordinates "[welt x y z]"; click teleports (with {@link ChatActions#TELEPORT_PERMISSION}). */
    default Component location(Location location) { return ChatActions.location(location); }

    /** See {@link ChatActions#location}. */
    default Component location(World world, double x, double y, double z) { return ChatActions.location(world, x, y, z); }

    /** Coordinates teleporting players with {@code permission}; an empty permission allows everyone. */
    default Component location(Location location, String permission) { return ChatActions.location(location, permission); }

    /**
     * A "[Label]" button that runs {@code action} for the clicking player, as often as it is clicked and without a
     * permission check. For rewards/confirmations use {@link #buttonOnce}; for restricted actions
     * {@link #button(String, String, String, Consumer)}.
     */
    default Component button(String label, String hover, Consumer<Player> action) { return ChatActions.button(label, hover, action); }

    /**
     * A "[Label]" button that runs {@code action} only for players with {@code permission} (checked at click time;
     * {@code null}/empty allows everyone).
     *
     * @since 0.11.2
     */
    default Component button(String label, String hover, String permission, Consumer<Player> action) {
        return ChatActions.button(label, hover, permission, action);
    }

    /**
     * A "[Label]" button that runs {@code action} at most once in total (first click wins, later clicks get a notice).
     * Use it for rewards and confirmations so they cannot be triggered repeatedly.
     *
     * @since 0.11.2
     */
    default Component buttonOnce(String label, String hover, Consumer<Player> action) { return ChatActions.buttonOnce(label, hover, action); }

    /** Like {@link #buttonOnce(String, String, Consumer)} with a click-time permission check. @since 0.11.2 */
    default Component buttonOnce(String label, String hover, String permission, Consumer<Player> action) {
        return ChatActions.buttonOnce(label, hover, permission, action);
    }

    /** Opens a Core menu entry ({@code plugin:key}, see /vmenu list); access is checked on click. */
    default Component menu(String label, String menuKey) { return ChatActions.menu(label, menuKey); }

    /** Opens a Core settings section ({@code plugin:section}); access is checked on click. */
    default Component settings(String label, String sectionKey) { return ChatActions.settings(label, sectionKey); }

    /** Runs a command as the clicking player (normal permission checks apply). Include the leading slash. */
    default Component command(String label, String hover, String command) { return ChatActions.command(label, hover, command); }

    /** Puts a command into the chat input for the player to complete. */
    default Component suggest(String label, String hover, String command) { return ChatActions.suggest(label, hover, command); }

    /** See {@link ChatActions#copy}. */
    default Component copy(String label, String value) { return ChatActions.copy(label, value); }
}
