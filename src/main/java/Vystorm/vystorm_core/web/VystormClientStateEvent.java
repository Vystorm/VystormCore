package Vystorm.vystorm_core.web;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Der Client-Mod meldet einen neuen Zustand (CLIENT_STATE): native Elemente ausgeblendet (Taste H), Overlay
 * offen/zu oder (seit 0.21.0) Zustimmung zum Web-Ursprung. Z. B. zeigt ein Plugin bei {@link #uiHidden()} wieder
 * seine Vanilla-Anzeige. Nur bei Änderung (höchstens 2/s, Burst 6; der Zustand selbst über
 * {@link ClientServices#clientState} ist immer aktuell), synchron, Server-Thread.
 *
 * @since 0.20.0
 */
public final class VystormClientStateEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final int previous;
    private final int state;

    public VystormClientStateEvent(Player player, int previous, int state) {
        super(player);
        this.previous = previous;
        this.state = state;
    }

    /** Neuer Zustand ({@code ClientServices.STATE_*}). */
    public int state() { return state; }

    /** Vorheriger Zustand. */
    public int previous() { return previous; }

    public boolean uiHidden() { return (state & ClientServices.STATE_UI_HIDDEN) != 0; }

    public boolean overlayOpen() { return (state & ClientServices.STATE_OVERLAY_OPEN) != 0; }

    /** Ob der Spieler dem Web-Ursprung zugestimmt hat (Client ≥ 0.4.0, siehe {@link ClientServices#STATE_ORIGIN_TRUSTED}). @since 0.21.0 */
    public boolean originTrusted() { return (state & ClientServices.STATE_ORIGIN_TRUSTED) != 0; }

    /** Ob sich {@link #originTrusted()} geändert hat. @since 0.21.0 */
    public boolean originTrustedChanged() { return ((state ^ previous) & ClientServices.STATE_ORIGIN_TRUSTED) != 0; }

    /** Ob sich {@link #uiHidden()} geändert hat. */
    public boolean uiHiddenChanged() { return ((state ^ previous) & ClientServices.STATE_UI_HIDDEN) != 0; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
