package Vystorm.vystorm_core.party;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.main;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link PartyServices}, reached through {@code api.parties()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link PartyServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface PartyServicesInterface {
    /** The delegating default. Plugins use {@code api.parties()}. */
    PartyServicesInterface DEFAULT = new PartyServicesInterface() {};

    /** See {@link PartyServices#register}. */
    default void register(Plugin owner, PartySource source) { PartyServices.register(owner, source); }

    /** See {@link PartyServices#unregister}. */
    default void unregister(Plugin owner) { PartyServices.unregister(owner); }

    /** See {@link PartyServices#available}. */
    default boolean available() { return PartyServices.available(); }

    /** See {@link PartyServices#partyOf}. */
    default Optional<PartyView> partyOf(UUID player) { return PartyServices.partyOf(player); }

    /** See {@link PartyServices#party}. */
    default Optional<PartyView> party(UUID partyId) { return PartyServices.party(partyId); }

    /** See {@link PartyServices#all}. */
    default Collection<PartyView> all() { return PartyServices.all(); }

    /** See {@link PartyServices#sameParty}. */
    default boolean sameParty(UUID a, UUID b) { return PartyServices.sameParty(a, b); }

    /** See {@link PartyServices#onlineMembers}. */
    default List<Player> onlineMembers(Player player) { return PartyServices.onlineMembers(player); }

    /** See {@link PartyServices#nearbyMembers}. */
    default List<Player> nearbyMembers(Player player, double radius) { return PartyServices.nearbyMembers(player, radius); }

    /** See {@link PartyServices#flag}. */
    default boolean flag(UUID player, String key, boolean fallback) { return PartyServices.flag(player, key, fallback); }

    /** See {@link PartyServices#setting}. */
    default boolean setting(UUID partyId, String key, String value) { return PartyServices.setting(partyId, key, value); }

    /** Server-thread advisory signal; consumers must recheck membership, expiry and their own entry rules. */
    default Optional<PartyReadyCheck> readyCheck(UUID partyId) { return PartyServices.readyCheck(partyId); }

    /** Records observed progress in an opted-in session; no XP or other rewards are granted here. Server thread only. */
    default boolean recordProgress(Plugin provider, UUID partyId, UUID eventId, String goal, double sharedXp) {
        return PartyServices.recordProgress(provider, partyId, eventId, goal, sharedXp);
    }

    /** See {@link PartyServices#fire}. */
    default void fire(PartyChangeEvent event) { PartyServices.fire(event); }
}
