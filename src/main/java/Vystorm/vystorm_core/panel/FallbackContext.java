package Vystorm.vystorm_core.panel;

import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * What a plugin's own chest {@code fallback} gets since 0.71.0 ({@link PanelSpec.Builder#contextFallback(java.util.function.BiConsumer)}):
 * the same argument and session values the screen or client-mod route of the panel would get, and the result of a
 * pick that came back ({@code picker.PickReturn}). A chest menu that reads and writes {@link #session()} needs no
 * per-player state of its own.
 *
 * <pre>{@code
 * panels.register("area", PanelSpec.builder("Area").render(this::render).contextFallback(this::openChest).build());
 *
 * void openChest(Player player, FallbackContext ctx) {
 *     String area = ctx.arg();
 *     String tab = ctx.session().get("tab");                  // the same key the panel's render reads
 *     draw(player, ctx);                                       // opens the chest from arg and session
 *     ctx.result().ifPresent(r -> { showStatusItem(r); ctx.resultShown(); });   // in this call, after the chest opened
 *     // a button that starts a pick comes back to this panel (screen if it opens now, else this chest):
 *     //   core.gui().pickers().players(plugin, player, pick, PickReturn.panel(ctx));
 *     // a button that changes a value: ctx.session().put("tab", "members"); draw(player, ctx);   (no result again)
 * }
 * }</pre>
 *
 * <b>Session lifetime.</b> Core keeps no table of these sessions: the values live as long as something that shows or
 * carries them does. A fresh open ({@code Panels.open}, menu, command, navigate) always starts with an empty session,
 * on every route. The values go on (into a chest as the same map) through {@link #reopen()}, through
 * {@code PickReturn.panel(ctx)} and through the return of a pick, whichever route shows the panel then (client mod,
 * screen, this chest, dialog). They are gone when the panel is closed without a return (the player closes the chest
 * or screen), when the player quits (a return to an offline player does nothing), when the pick ends without a return
 * (timeout, damage, death, another menu; a timeout's "Open again" chat button keeps them until it expires after 30
 * minutes), and when the owning plugin is disabled. Keep a context only inside the chest's own click handlers, never in a per-player map.
 * <p>
 * Server thread only. Capability {@code panel-fallback-context}.
 *
 * @since 0.71.0
 */
public interface FallbackContext {
    /** The player the chest is for. */
    Player player();

    /** The panel's argument ({@code ctx.arg()} on the other routes); never null, empty when none was given. */
    String arg();

    /**
     * The panel's session values: empty on a fresh open, the carried values when a pick came back. Writes are seen by
     * {@link #reopen()}, by {@code PickReturn.panel(ctx)} and by the panel the player comes back to.
     */
    PanelSession session();

    /** The pick handler's result when this open is a return ({@code PickReturn.go(player, result)}); empty otherwise. */
    Optional<Result> result();

    /** True when this open is a return after a hand-over ({@code PickReturn}), false for a fresh open. */
    boolean returning();

    /**
     * Tells Core the chest shows {@link #result()} itself, so Core sends no chat line for it. Without this call the
     * result also goes to chat, as for one-argument fallbacks. Counts only while the fallback call runs (Core sends
     * the chat line right after it returns): call it there, after the chest opened, not a tick later or from a click
     * handler. A fallback that throws keeps the chat line.
     */
    void resultShown();

    /**
     * Opens the panel again by the normal route (client mod, then screen, then this fallback ...) with the same
     * argument and session: a chest shown for a moment (in water, right after damage) gives way to the screen once it
     * can open. {@link PanelOpenResult#NONE} while a fallback of this panel runs for this player (no loop), for an
     * offline player, and when the plugin that registered the panel was disabled since (also after a re-enable).
     */
    PanelOpenResult reopen();
}
