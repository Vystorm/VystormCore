package Vystorm.vystorm_core.web;

/**
 * Web manager, reached through {@code api.web()}: the web platform directly ({@link WebServices}) and the optional
 * client mod.
 *
 * @since 0.64.0
 */
public interface WebInterface extends WebServicesInterface {
    /** The client mod of a player: handshake, capabilities, overlay ({@link ClientServices}). */
    default ClientServicesInterface client() { return ClientServicesInterface.DEFAULT; }

    /** Native elements of the client mod per plugin ({@link ClientUi}). */
    default ClientUiInterface clientUi() { return ClientUiInterface.DEFAULT; }
}
