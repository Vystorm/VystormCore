package Vystorm.vystorm_core.screen;

import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link Screens}, reached through {@code api.gui().screens()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link Screens}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ScreensInterface {
    /** The delegating default. Plugins use {@code api.gui().screens()}. */
    ScreensInterface DEFAULT = new ScreensInterface() {};

    /** See {@link Screens#of}. */
    default Screens of(Plugin owner) { return Screens.of(owner); }

    /**
     * Keeps a menu as a real chest (since 0.61.0): Core shows chest menus of Vystorm plugins as screens, which have no
     * way to put an item into an empty slot. Call this before opening a menu in which players move items (trade
     * windows, send slots, crafting inputs). Guard with {@code CoreApi.atLeast("0.61.0")}.
     */
    default void keepAsChest(org.bukkit.inventory.Inventory inventory) { Screens.keepAsChest(inventory); }

    /**
     * The map colour table (since 0.72.0, capability {@code map-palette}): RGB per map colour byte, the nearest byte for an RGB
     * colour and the 244 visible colours in picker order, the same on every server. Replaces
     * {@code MapPalette.getColor}/{@code matchColor}. Any thread.
     */
    default MapColors mapColors() { return Screens.mapColors(); }
}
