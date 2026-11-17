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
 * Clickable, context-based chat actions. Build a message from legacy text and append actions:
 * <pre>
 * player.sendMessage(ChatActions.text("§aHauptstadt gebaut bei ")
 *         .append(ChatActions.location(loc))
 *         .append(Component.space()).append(ChatActions.menu("Fraktionen", "vystorm-factions:factions")));
 * </pre>
 * Callbacks run on the server thread when clicked and check permissions at click time; the server keeps
 * them for {@link #LIFETIME}. Everything else (menus, settings) re-checks access when it opens.
 * <p>Preferred access: {@code api.message().chat()}.
 */
public final class ChatActions {
    /** Clicking coordinates teleports only players with this permission (default: op); others get the coordinates. */
    public static final String TELEPORT_PERMISSION = "vystorm_core.chat.teleport";
    public static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private ChatActions() {}

    private static Vystorm.vystorm_core.lang.Lang lang() { return Vystorm.vystorm_core.lang.Localization.get().core(); }

    private static Component hover(String key) { return lang().translatable(key).color(NamedTextColor.GRAY); }

    /** Converts a legacy {@code §}-formatted text so actions can be appended. */
    public static TextComponent text(String legacy) { return LEGACY.deserialize(legacy == null ? "" : legacy); }

    /** Coordinates "[welt x y z]"; click teleports (with {@link #TELEPORT_PERMISSION}). */
    public static Component location(Location location) { return location(location, TELEPORT_PERMISSION); }

    public static Component location(World world, double x, double y, double z) {
        return location(new Location(Objects.requireNonNull(world, "world"), x, y, z));
    }

    /** Coordinates teleporting players with {@code permission}; an empty permission allows everyone. */
    public static Component location(Location location, String permission) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        String coords = location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
        String label = "[" + world.getName() + " " + coords + "]";
        Location target = location.clone();
        String node = permission == null ? "" : permission;
        return Component.text(label, NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(hover("chat.teleport-hover")
                        .append(Component.newline()).append(Component.text(world.getName() + " " + coords, NamedTextColor.DARK_GRAY))))
                .clickEvent(callback(player -> {
                    if (!node.isEmpty() && !player.hasPermission(node)) {
                        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, lang().component(player, "chat.coordinates", "world", world.getName(), "coords", coords)
                                .hoverEvent(HoverEvent.showText(lang().component(player, "chat.copy-clipboard-hover")))
                                .clickEvent(ClickEvent.copyToClipboard(coords)));
                        return;
                    }
                    teleport(player, target);
                }));
    }

    /**
     * A "[Label]" button that runs {@code action} for the clicking player, as often as it is clicked and without a
     * permission check. For rewards/confirmations use {@link #buttonOnce}; for restricted actions
     * {@link #button(String, String, String, Consumer)}.
     */
    public static Component button(String label, String hover, Consumer<Player> action) {
        Objects.requireNonNull(action, "action");
        return styled(label, NamedTextColor.GREEN, hover).clickEvent(callback(action));
    }

    /**
     * A "[Label]" button that runs {@code action} only for players with {@code permission} (checked at click time;
     * {@code null}/empty allows everyone).
     *
     * @since 0.11.2
     */
    public static Component button(String label, String hover, String permission, Consumer<Player> action) {
        Objects.requireNonNull(action, "action");
        return styled(label, NamedTextColor.GREEN, hover).clickEvent(callback(guarded(permission, false, action), ClickCallback.UNLIMITED_USES));
    }

    /**
     * A "[Label]" button that runs {@code action} at most once in total (first click wins, later clicks get a notice).
     * Use it for rewards and confirmations so they cannot be triggered repeatedly.
     *
     * @since 0.11.2
     */
    public static Component buttonOnce(String label, String hover, Consumer<Player> action) {
        return buttonOnce(label, hover, null, action);
    }

    /** Like {@link #buttonOnce(String, String, Consumer)} with a click-time permission check. @since 0.11.2 */
    public static Component buttonOnce(String label, String hover, String permission, Consumer<Player> action) {
        Objects.requireNonNull(action, "action");
        return styled(label, NamedTextColor.GREEN, hover).clickEvent(callback(guarded(permission, true, action), 1));
    }

    /** Wraps {@code action} with the click-time permission check and, if {@code once}, a server-side single-use guard. */
    static Consumer<Player> guarded(String permission, boolean once, Consumer<Player> action) {
        String node = permission == null ? "" : permission.trim();
        java.util.concurrent.atomic.AtomicBoolean used = new java.util.concurrent.atomic.AtomicBoolean();
        return player -> {
            if (!node.isEmpty() && !player.hasPermission(node)) {
                lang().send(player, "chat.no-permission");
                return;
            }
            if (once && !used.compareAndSet(false, true)) {
                lang().send(player, "chat.already-used");
                return;
            }
            action.accept(player);
        };
    }

    /** Opens a Core menu entry ({@code plugin:key}, see /vmenu list); access is checked on click. */
    public static Component menu(String label, String menuKey) {
        return styled(label, NamedTextColor.GREEN, hover("chat.menu-hover")).clickEvent(callback(player -> {
            if (!Vystorm.vystorm_core.main.getApi().gui().openMenu(player, menuKey))
                lang().send(player, "common.menu-unavailable");
        }));
    }

    /** Opens a Core settings section ({@code plugin:section}); access is checked on click. */
    public static Component settings(String label, String sectionKey) {
        return styled(label, NamedTextColor.GOLD, hover("chat.settings-hover")).clickEvent(callback(player -> {
            if (!Vystorm.vystorm_core.main.getApi().settings().openSection(player, sectionKey))
                lang().send(player, "common.settings-unavailable");
        }));
    }

    /** Runs a command as the clicking player (normal permission checks apply). Include the leading slash. */
    public static Component command(String label, String hover, String command) {
        return styled(label, NamedTextColor.GREEN, hover == null ? command : hover).clickEvent(ClickEvent.runCommand(command));
    }

    /** Puts a command into the chat input for the player to complete. */
    public static Component suggest(String label, String hover, String command) {
        return styled(label, NamedTextColor.YELLOW, hover == null ? command : hover).clickEvent(ClickEvent.suggestCommand(command));
    }

    public static Component copy(String label, String value) {
        return styled(label, NamedTextColor.GRAY, hover("chat.copy-hover")).clickEvent(ClickEvent.copyToClipboard(value));
    }

    private static Component styled(String label, NamedTextColor color, String hover) {
        return styled(label, color, hover == null || hover.isBlank() ? null
                : Vystorm.vystorm_core.lang.Localization.get().deferred(hover).colorIfAbsent(NamedTextColor.GRAY));
    }

    /** Labels and hovers may be {@code Lang.ref} references; they are rendered in the reader's language. */
    private static Component styled(String label, NamedTextColor color, Component hover) {
        String text = label == null ? "" : label;
        Component c = Vystorm.vystorm_core.lang.Localization.isRef(text)
                ? Component.text("[", color).append(Vystorm.vystorm_core.lang.Localization.get().deferred(text)).append(Component.text("]"))
                : Component.text("[" + text + "]", color);
        c = c.decoration(TextDecoration.BOLD, false);
        return hover == null ? c : c.hoverEvent(HoverEvent.showText(hover));
    }

    @SuppressWarnings("rawtypes") // Adventure 4 has a non-generic ClickEvent; Adventure 5 adds payload types.
    private static ClickEvent callback(Consumer<Player> action) { return callback(action, ClickCallback.UNLIMITED_USES); }

    @SuppressWarnings("rawtypes")
    private static ClickEvent callback(Consumer<Player> action, int uses) {
        if (!Audience.class.isAssignableFrom(Player.class)) return Vystorm.vystorm_core.compat.ClickActions.callback(action, uses, LIFETIME);
        return ClickEvent.callback((Audience audience) -> {
            if (audience instanceof Player player && player.isOnline()) action.accept(player);
        }, options -> options.uses(uses).lifetime(LIFETIME));
    }

    /** Teleports to the block's centre; if that spot is obstructed, to the highest safe block above it. */
    static void teleport(Player player, Location target) {
        Location to = target.clone();
        if (to.getX() == Math.floor(to.getX()) && to.getZ() == Math.floor(to.getZ())) to.add(0.5, 0, 0.5);
        World world = to.getWorld();
        // Load the target chunk asynchronously instead of blocking the server thread on the safety check.
        Vystorm.vystorm_core.compat.PlatformCompat.getChunkAtAsync(world, to).thenAccept(chunk -> {
            if (!player.isOnline()) return;
            var feet = world.getBlockAt(to.getBlockX(), to.getBlockY(), to.getBlockZ());
            if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable())
                to.setY(world.getHighestBlockYAt(to.getBlockX(), to.getBlockZ()) + 1);
            to.setYaw(player.getLocation().getYaw()); to.setPitch(player.getLocation().getPitch());
            Vystorm.vystorm_core.compat.PlatformCompat.teleportAsync(player, to, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (ok) lang().send(player, "chat.teleported", "world", world.getName(),
                        "x", to.getBlockX(), "y", to.getBlockY(), "z", to.getBlockZ());
            });
        });
    }
}
