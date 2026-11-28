package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.Result;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link Pickers}, reached through {@code api.gui().pickers()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread rules
 * and the end rules are the ones documented on {@link Pickers}. An own implementation of the GUI manager may return a
 * replacement that overrides single methods (tests, special servers).
 *
 * @since 0.71.0
 */
public interface PickersInterface {
    /** The delegating default. Plugins use {@code api.gui().pickers()}. */
    PickersInterface DEFAULT = new PickersInterface() {};

    /** See {@link Pickers#players(PanelAction, PlayerPick)}. */
    default Result players(PanelAction from, PlayerPick pick) { return Pickers.players(from, pick); }

    /** See {@link Pickers#players(Plugin, Player, PlayerPick, PickReturn)}. */
    default PickFlow players(Plugin owner, Player viewer, PlayerPick pick, PickReturn back) { return Pickers.players(owner, viewer, pick, back); }

    /** See {@link Pickers#world(PanelAction, WorldPick)}. @since 0.71.0 */
    default Result world(PanelAction from, WorldPick pick) { return Pickers.world(from, pick); }

    /** See {@link Pickers#world(Plugin, Player, WorldPick, PickReturn)}. @since 0.71.0 */
    default PickFlow world(Plugin owner, Player viewer, WorldPick pick, PickReturn back) { return Pickers.world(owner, viewer, pick, back); }

    /** See {@link Pickers#active}. */
    default Optional<PickFlow> active(Player viewer) { return Pickers.active(viewer); }

    /** See {@link Pickers#cancel}. */
    default boolean cancel(Player viewer) { return Pickers.cancel(viewer); }

    /** See {@link Pickers#available}. */
    default boolean available() { return Pickers.available(); }
}
