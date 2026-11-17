package Vystorm.vystorm_core.screen;

import Vystorm.vystorm_core.screen.internal.HudRuntime;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The HUD corner window (since 0.59.0, capability {@code hud-window}): one small panel per player at the top right of
 * the screen, for example the current skill with level and XP. It is built from boss bar titles in the GUI layer and
 * placed by the Core resource pack, so it works with shader packs; without the pack the rows show as plain boss bar
 * titles at the top centre. One window per player: the last {@link #show} wins. Server thread only.
 *
 * <pre>{@code
 * Huds.of(this).show(player, HudCard.of(Material.IRON_PICKAXE, "Mining", "Lv 42", 0.63, "340 / 540 XP")
 *     .withFooter("+12 XP"), 60);
 * }</pre>
 * <p>Preferred access: {@code api.gui().huds()}.
 */
public final class Huds {
    private final Plugin owner;

    private Huds(Plugin owner) { this.owner = Objects.requireNonNull(owner, "owner"); }

    public static Huds of(Plugin owner) { return new Huds(owner); }

    /** Shows the window for {@code ticks} ticks (0 = until {@link #hide}). */
    public void show(Player player, HudCard card, int ticks) { HudRuntime.get().show(owner, player, card, ticks); }

    /** Hides the window if this plugin shows it. */
    public void hide(Player player) { HudRuntime.get().hide(owner, player); }
}
