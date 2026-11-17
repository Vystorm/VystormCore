package Vystorm.vystorm_core.web;

import Vystorm.vystorm_core.web.internal.ClientChannel;
import Vystorm.vystorm_core.web.internal.ClientPlatform;
import org.bukkit.entity.Player;

/**
 * Manager view of {@link ClientServices}, reached through {@code api.web().client()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClientServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ClientServicesInterface {
    /** The delegating default. Plugins use {@code api.web().client()}. */
    ClientServicesInterface DEFAULT = new ClientServicesInterface() {};

    /** See {@link ClientServices#hasClient}. */
    default boolean hasClient(Player player) { return ClientServices.hasClient(player); }

    /** See {@link ClientServices#capabilities}. */
    default int capabilities(Player player) { return ClientServices.capabilities(player); }

    /** See {@link ClientServices#clientState}. */
    default int clientState(Player player) { return ClientServices.clientState(player); }

    /** See {@link ClientServices#originTrusted}. */
    default boolean originTrusted(Player player) { return ClientServices.originTrusted(player); }

    /** See {@link ClientServices#uiHidden}. */
    default boolean uiHidden(Player player) { return ClientServices.uiHidden(player); }

    /** See {@link ClientServices#uiAvailable}. */
    default boolean uiAvailable(Player player, int capability) { return ClientServices.uiAvailable(player, capability); }

    /** See {@link ClientServices#overlayAvailable}. */
    default boolean overlayAvailable(Player player) { return ClientServices.overlayAvailable(player); }

    /** See {@link ClientServices#show}. */
    default boolean show(Player player, String moduleId, String page) { return ClientServices.show(player, moduleId, page); }

    /** See {@link ClientServices#close}. */
    default boolean close(Player player, String moduleId) { return ClientServices.close(player, moduleId); }

    /** See {@link ClientServices#enabled}. */
    default boolean enabled() { return ClientServices.enabled(); }
}
