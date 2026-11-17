package Vystorm.vystorm_core.web;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Der Spieler hat den optionalen Vystorm-Client-Mod: Handshake angenommen (HELLO → WELCOME). Guter Ort für die ersten
 * nativen Elemente ({@link ClientUi#put}) und um Vanilla-Ersatzanzeigen (z. B. eine Bossbar) für diesen Spieler
 * abzuschalten. Kommt höchstens einmal pro Verbindung, meist kurz nach dem Join (Daten des eigenen Plugins sind dann
 * eventuell noch nicht geladen – später nachziehen). Synchron, Server-Thread.
 *
 * @since 0.20.0
 */
public final class VystormClientReadyEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final int capabilities;
    private final String modVersion;
    private final String loader;

    public VystormClientReadyEvent(Player player, int capabilities, String modVersion, String loader) {
        super(player);
        this.capabilities = capabilities;
        this.modVersion = modVersion == null ? "" : modVersion;
        this.loader = loader == null ? "" : loader;
    }

    /** Fähigkeiten aus dem Handshake ({@code ClientServices.CAP_*}). */
    public int capabilities() { return capabilities; }

    /** Ob alle Bits von {@code capability} gemeldet wurden. */
    public boolean has(int capability) { return (capabilities & capability) == capability; }

    /** Version des Mods (nur zur Anzeige/Statistik, nie für Rechte). */
    public String modVersion() { return modVersion; }

    /** {@code fabric} oder {@code neoforge}. */
    public String loader() { return loader; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
