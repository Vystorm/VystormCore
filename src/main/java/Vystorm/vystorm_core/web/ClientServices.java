package Vystorm.vystorm_core.web;

import Vystorm.vystorm_core.web.internal.ClientChannel;
import Vystorm.vystorm_core.web.internal.ClientPlatform;
import org.bukkit.entity.Player;

/**
 * Optionaler Vystorm-Client-Mod (Fabric/NeoForge, Repo Vystorm/vystorm-client): Weboberflächen als Overlay im Spiel.
 * Alles hier ist Zusatz – jede Funktion muss auch ohne Mod gehen (Chat-Link über {@link WebServices#sendLink},
 * Kisten-/Dialogmenüs). Der Mod bekommt Login-Tokens nur mit denselben Prüfungen wie {@code /web}.
 *
 * <pre>{@code
 * // statt eines Kistenmenüs
 * if (ClientServices.show(player, "skills", "")) return;   // Mod: Overlay
 * openChestMenu(player);                                   // Vanilla: wie bisher
 * }</pre>
 *
 * Shortcut (0.24.0): {@link WebServices#openOrElse} (overlay, else your menu) and {@link WebServices#openOrLink}
 * (overlay, else chat link, else fallback) wrap this pattern.
 *
 * Native HUD-Elemente, Toasts, Marker und Tooltip-Zeilen: {@link ClientUi} (seit 0.20.0), Events
 * {@link VystormClientReadyEvent} und {@link VystormClientStateEvent}.
 *
 * Nur Server-Thread.
 *
 * <p>Preferred access: {@code api.web().client()}.
 *
 * @since 0.19.0
 */
public final class ClientServices {
    /** Fähigkeit: eingebetteter Browser (Overlay). */
    public static final int CAP_BROWSER_OVERLAY = 1;
    /** Fähigkeit: Login-Link im Systembrowser öffnen. */
    public static final int CAP_EXTERNAL_BROWSER = 1 << 1;
    /** Fähigkeit: HUD-Elemente (panel, bar, slots). */
    public static final int CAP_HUD = 1 << 2;
    /** Fähigkeit: Toasts. */
    public static final int CAP_TOASTS = 1 << 3;
    /** Fähigkeit: Weltmarker. */
    public static final int CAP_MARKERS = 1 << 4;
    /** Fähigkeit: Tooltip-Regeln. */
    public static final int CAP_TOOLTIPS = 1 << 5;
    /** Fähigkeit: JS-Brücke {@code window.vystormQuery} im Overlay. @since 0.20.0 */
    public static final int CAP_JS_BRIDGE = 1 << 6;
    /**
     * Fähigkeit (Client ≥ 0.9.0): Erweiterungen der nativen Elemente – Tooltip-Platzhalter {@code {data:…}} und
     * Bedingungen ({@code present}, {@code equalsViewer}, mehrere), Marker an Entities/auf dem Boden, HUD-Liste,
     * laufende Balken ({@code rate}, Label-Platzhalter), {@code stack} und Toasts mit Aktion. {@link ClientUi} schickt
     * solche Elemente nur mit diesem Bit (sonst {@code false}) und lässt rein verbessernde Felder für ältere Clients weg.
     * @since 0.26.4
     */
    public static final int CAP_UI_EXTENSIONS = 1 << 8;
    /** Client-Zustand: Spieler hat die nativen Elemente ausgeblendet. */
    public static final int STATE_UI_HIDDEN = 1;
    /** Client-Zustand: Overlay ist offen. */
    public static final int STATE_OVERLAY_OPEN = 1 << 1;
    /**
     * Client-Zustand (ab Client 0.4.0): der Spieler hat dem Web-Ursprung zugestimmt, der Client würde ein SHOW befolgen.
     * Nur ein Hinweis – der Client prüft die Zustimmung selbst. @since 0.21.0
     */
    public static final int STATE_ORIGIN_TRUSTED = 1 << 2;

    private ClientServices() {}

    /** Ob der Spieler den Mod hat (Handshake abgeschlossen). */
    public static boolean hasClient(Player player) {
        return player != null && ClientPlatform.channel().map(c -> c.hasClient(player.getUniqueId())).orElse(false);
    }

    /** Fähigkeiten aus dem Handshake ({@code CAP_*}), 0 ohne Mod. */
    public static int capabilities(Player player) {
        return player == null ? 0 : ClientPlatform.channel().map(c -> c.capabilities(player.getUniqueId())).orElse(0);
    }

    /** Zuletzt gemeldeter Client-Zustand ({@code STATE_*}), 0 ohne Mod. */
    public static int clientState(Player player) {
        return player == null ? 0 : ClientPlatform.channel().map(c -> c.clientState(player.getUniqueId())).orElse(0);
    }

    /**
     * Ob der Client meldet, dass der Spieler dem Web-Ursprung zugestimmt hat ({@link #STATE_ORIGIN_TRUSTED}; Client ≥ 0.4.0).
     * Nur ein Hinweis, keine Berechtigung. @since 0.21.0
     */
    public static boolean originTrusted(Player player) { return (clientState(player) & STATE_ORIGIN_TRUSTED) != 0; }

    /** Ob der Spieler die nativen Elemente gerade ausgeblendet hat (Taste H). @since 0.20.0 */
    public static boolean uiHidden(Player player) { return (clientState(player) & STATE_UI_HIDDEN) != 0; }

    /**
     * Ob der Spieler native Elemente mit dieser Fähigkeit annimmt ({@code CAP_HUD}, {@code CAP_TOASTS}, …; Mod,
     * {@code web.client.ui} an). Senden über {@link ClientUi#of(org.bukkit.plugin.Plugin)}.
     *
     * @since 0.20.0
     */
    public static boolean uiAvailable(Player player, int capability) {
        return player != null && Vystorm.vystorm_core.web.internal.ClientPlatform.uiHub().map(h -> h.supports(player.getUniqueId(), capability)).orElse(false);
    }

    /**
     * Ob ein Overlay möglich ist (Mod mit eingebettetem Browser, Web-Plattform läuft, {@code web.client.overlay}) und der
     * Client ein {@link #show} befolgen würde, weil der Spieler dem Web-Ursprung zugestimmt hat. Seit 0.21.0 meldet der
     * Client (ab 0.4.0) das direkt nach dem Join ({@link #STATE_ORIGIN_TRUSTED}), auch ohne vorgewärmten Browser; die
     * Anmeldung passiert dann beim ersten {@link #show} mit einem frischen Einmal-Token. Ältere Clients: erst nachdem sie
     * in dieser Verbindung ein Token geholt haben (0.20.0).
     */
    public static boolean overlayAvailable(Player player) {
        return player != null && ClientPlatform.channel().map(c -> c.overlayAvailable(player.getUniqueId())).orElse(false);
    }

    /**
     * Zeigt ein Web-Modul im Overlay (statt eines Kisten-/Dialogmenüs). Gibt nichts frei: der Client meldet sich nur
     * mit einem Token an, das dieselben Rechte prüft wie {@code /web}, und jede API-Anfrage wird wie im Browser geprüft.
     *
     * @param moduleId Modul-ID oder "" (Übersicht)
     * @param page     relative Seite auf der Web-Plattform, z. B. {@code "/app/skills#node/12"}; "" = Ziel der Anmeldung
     * @return false, wenn nicht möglich (kein Mod, kein Overlay, Ratenlimit) – dann das normale Menü öffnen
     */
    public static boolean show(Player player, String moduleId, String page) {
        return ClientPlatform.channel().map(c -> c.show(player, moduleId, page)).orElse(false);
    }

    /** Schließt das Overlay des Spielers (leer/null = egal welches Modul). */
    public static boolean close(Player player, String moduleId) {
        return ClientPlatform.channel().map(c -> c.close(player, moduleId)).orElse(false);
    }

    /** Ob der Client-Kanal läuft ({@code web.client.enabled}). */
    public static boolean enabled() { return ClientPlatform.channel().map(ClientChannel::settings).map(ClientChannel.Settings::enabled).orElse(false); }
}
