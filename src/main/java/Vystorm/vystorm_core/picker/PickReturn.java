package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.Result;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Where a player goes after a pick (0.71.0): back to the panel the pick started from with its state, to another
 * panel, to the plugin's own code, or nowhere. Core's pickers take one; it also works on its own: a panel handler
 * that hands the player over to something else ({@link PanelAction#closeAndThen}) can come back with
 * {@code back.go(player, Result.ok("Saved"))} and the panel shows the same argument and session values as before.
 * <p>
 * {@link #panel(PanelAction)} copies the session values of the action's panel; {@link #session()} gives them to the
 * pick handler, which may write into them ({@code back.session().put("payee", id)}); the reopened panel then reads
 * them through {@code ctx.session()}. When the reopen ends in the plugin's own chest, a fallback registered with
 * {@code contextFallback((player, ctx) -> ..)} gets the argument, these values and the result through
 * {@link Vystorm.vystorm_core.panel.FallbackContext}; a one-argument {@code fallback(player -> ..)} still gets only the
 * player and the result as a chat line. A chest started from such a context comes back with {@link #panel(FallbackContext)}.
 *
 * <pre>{@code
 * // in a panel handler: hand over, come back later
 * PickReturn back = PickReturn.panel(action);
 * action.closeAndThen(p -> askSomething(p, answer -> { back.session().put("answer", answer); back.go(p, Result.ok("Thanks")); }));
 * }</pre>
 *
 * Capability {@code pick-return}. All calls on the server thread.
 *
 * @since 0.71.0
 */
public sealed interface PickReturn permits PickReturns.Same, PickReturns.Named, PickReturns.Run, PickReturns.Stay {
    /**
     * Back to the panel {@code from} belongs to: same panel, same argument, the session values copied over. For a
     * {@link PanelAction} that does not come from Core (a test double) this behaves like {@link #none()}.
     */
    static PickReturn panel(PanelAction from) { return PickReturns.same(from); }

    /**
     * Back to the panel whose chest fallback {@code from} was handed to: same panel, same argument, and the context's
     * session values themselves (what the chest wrote is carried). The panel opens by the normal route then (screen if
     * it can open now, else the chest again with these values). For a context that does not come from Core (a test
     * double) this behaves like {@link #none()}. Capability {@code panel-fallback-context}.
     *
     * @since 0.71.0
     */
    static PickReturn panel(FallbackContext from) { return PickReturns.same(from); }

    /** To the panel {@code panelName} of {@code owner} with {@code arg} and a fresh session. */
    static PickReturn panel(Plugin owner, String panelName, String arg) { return PickReturns.named(owner, panelName, arg); }

    /** Runs your own code, for example to reopen a chest menu with your own state. */
    static PickReturn to(Consumer<Player> next) { return PickReturns.run(next); }

    /** Nowhere: the player stays in the world; a message goes to chat. */
    static PickReturn none() { return PickReturns.stay(); }

    /** The carried session values: those of the calling panel for {@link #panel(PanelAction)}, otherwise a fresh store. */
    PanelSession session();

    /** The plugin whose panel this goes back to; empty for {@link #to}, {@link #none} and actions from outside Core. */
    Optional<Plugin> owner();

    /**
     * Takes the player back now. {@code result} (may be null) is shown as the status line of the reopened panel on
     * screens and in dialogs, and as a chat line everywhere else ({@link #to}, {@link #none}, the client mod, a
     * plugin's chest fallback unless it shows {@code FallbackContext.result()} itself). Nothing happens for an offline player; a panel that is gone (plugin disabled,
     * unregistered) leaves only the chat line. Server thread.
     */
    void go(Player viewer, Result result);
}
