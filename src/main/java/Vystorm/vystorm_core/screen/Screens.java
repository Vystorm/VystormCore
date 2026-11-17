package Vystorm.vystorm_core.screen;

import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Vystorm Screens (since 0.57.0, capability {@code screens}): clickable screens in the player's view without a client
 * mod. PINNED screens stay fixed on the monitor and show a mouse pointer that follows the head (needs Core's resource
 * pack); FLOATING screens float in front of the player and use the crosshair as cursor. Free for every plugin.
 * All methods on the server thread.
 *
 * <pre>{@code
 * Screens screens = Screens.of(this);
 * ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(ScreenWidget.button("ok", 0.3, 0.4, 0.3, 0.08, "OK")));
 * screens.open(player, ScreenSpec.builder("myplugin:hello", layout)
 *     .onInput((session, input) -> { if (input instanceof ScreenInput.Click c && "ok".equals(c.widgetId())) session.close(); })
 *     .fallback(p -> p.sendMessage("Hello"))
 *     .build());
 * }</pre>
 * <p>Preferred access: {@code api.gui().screens()}.
 */
public final class Screens {
    private final Plugin owner;

    private Screens(Plugin owner) { this.owner = Objects.requireNonNull(owner, "owner"); }

    public static Screens of(Plugin owner) { return new Screens(owner); }

    public ScreenOpenResult open(Player player, ScreenSpec spec) { return ScreenRuntime.get().open(owner, player, spec); }

    /**
     * Opens a screen the player can return to (since 0.61.0). When a screen is closed by code and another opens right
     * after it, the closed one becomes the parent: Core shows a Back button on the new screen and {@code reopen} is
     * called to show the parent again. {@code key} names the place; opening the same key again shortens the history.
     * Closing a screen by hand ends the history.
     */
    public ScreenOpenResult open(Player player, ScreenSpec spec, String key, java.util.function.Consumer<Player> reopen) {
        return ScreenRuntime.get().open(owner, player, spec, new ScreenRuntime.Origin(key, reopen, true));
    }

    /**
     * Keeps a menu as a real chest (since 0.61.0): Core shows chest menus of Vystorm plugins as screens, which have no
     * way to put an item into an empty slot. Call this before opening a menu in which players move items (trade
     * windows, send slots, crafting inputs). Guard with {@code CoreApi.atLeast("0.61.0")}.
     */
    public static void keepAsChest(org.bukkit.inventory.Inventory inventory) { Vystorm.vystorm_core.screen.internal.InventoryScreens.keepAsChest(inventory); }

    /** Goes back to the screen the player came from; false when there is none (since 0.61.0). */
    public boolean back(Player player) { return ScreenRuntime.get().back(player); }

    /** True when {@link #back} would lead somewhere (since 0.61.0). */
    public boolean hasBack(Player player) { return ScreenRuntime.get().hasBack(player); }

    public void close(Player player) { ScreenRuntime.get().close(player, ScreenInput.Reason.PLUGIN); }

    public Optional<ScreenSession> session(Player player) { return ScreenRuntime.get().session(player); }

    /** The player's display settings ({@code /screen settings}); size your layouts from {@link ScreenProfile#viewWidth()}. */
    public ScreenProfile profile(Player player) { return Vystorm.vystorm_core.screen.internal.ScreenProfiles.load(player); }

    /** Stores new display settings for the player. */
    public void saveProfile(Player player, ScreenProfile profile) { Vystorm.vystorm_core.screen.internal.ScreenProfiles.save(player, profile); }
}
