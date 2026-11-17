package Vystorm.vystorm_core.web;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

/**
 * Der Client-Mod hat ein natives Element abgelehnt (REJECT, Hilfe für Plugin-Entwickler). Core prüft Elemente vorher
 * mit demselben Schema, daher selten (z. B. ältere Mod-Version, Limit durch andere Plugins, abgelaufenes Element).
 * {@link #owner()} ist das Plugin, dessen Namensraum die ID trägt ({@link ClientUi}). Gedrosselt (≤ 5/s pro Spieler),
 * synchron, Server-Thread. Nach Code 2/3/4 gilt das Element als nicht vorhanden ({@link ClientUi#has} = false).
 *
 * @since 0.20.0
 */
public final class VystormClientUiRejectEvent extends PlayerEvent {
    /** Ungültig. */
    public static final int INVALID = 1;
    /** Limit erreicht (32 HUD, 64 Marker, 128 Tooltip-Regeln). */
    public static final int LIMIT = 2;
    /** Unbekannter Typ (Mod zu alt). */
    public static final int UNKNOWN_TYPE = 3;
    /** PATCH auf unbekannte ID. */
    public static final int UNKNOWN_ID = 4;
    /** Ratenlimit des Clients. */
    public static final int RATE = 5;

    private static final HandlerList HANDLERS = new HandlerList();
    private final Plugin owner;
    private final String elementId;
    private final int code;
    private final String detail;

    public VystormClientUiRejectEvent(Player player, Plugin owner, String elementId, int code, String detail) {
        super(player);
        this.owner = owner;
        this.elementId = elementId == null ? "" : elementId;
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    /** Plugin des Namensraums oder null (unbekannte ID, z. B. leer bei Ratenlimit). */
    public Plugin owner() { return owner; }

    /** Vollständige Element-ID ({@code ns:name}), leer wenn unbekannt. */
    public String elementId() { return elementId; }

    /** Element-ID ohne Namensraum. */
    public String localId() { return ClientUi.localId(elementId); }

    public int code() { return code; }

    /** Kurze Beschreibung vom Client (nur Feldnamen, keine Inhalte). */
    public String detail() { return detail; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
