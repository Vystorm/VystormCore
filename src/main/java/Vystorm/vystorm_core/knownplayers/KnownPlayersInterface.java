package Vystorm.vystorm_core.knownplayers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manager view of {@link KnownPlayers}, reached through {@code api.data().knownPlayers()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread rules
 * and the privacy rules are the ones documented on {@link KnownPlayers}. An own implementation of the data manager may
 * return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.71.0
 */
public interface KnownPlayersInterface {
    /** The delegating default. Plugins use {@code api.data().knownPlayers()}. */
    KnownPlayersInterface DEFAULT = new KnownPlayersInterface() {};

    /** See {@link KnownPlayers#enabled}. */
    default boolean enabled() { return KnownPlayers.enabled(); }

    /** See {@link KnownPlayers#loaded}. */
    default boolean loaded() { return KnownPlayers.loaded(); }

    /** See {@link KnownPlayers#size}. */
    default int size() { return KnownPlayers.size(); }

    /** See {@link KnownPlayers#get}. */
    default Optional<KnownPlayer> get(UUID id) { return KnownPlayers.get(id); }

    /** See {@link KnownPlayers#byName}. */
    default Optional<KnownPlayer> byName(String name) { return KnownPlayers.byName(name); }

    /** See {@link KnownPlayers#search}. */
    default List<KnownPlayer> search(String prefix, int limit) { return KnownPlayers.search(prefix, limit); }

    /** See {@link KnownPlayers#recent}. */
    default List<KnownPlayer> recent(Duration within, int limit) { return KnownPlayers.recent(within, limit); }

    /** See {@link KnownPlayers#findable}. */
    default boolean findable(UUID id) { return KnownPlayers.findable(id); }

    /** See {@link KnownPlayers#setFindable}. */
    default void setFindable(UUID id, boolean findable) { KnownPlayers.setFindable(id, findable); }

    /** See {@link KnownPlayers#forget}. */
    default boolean forget(UUID id) { return KnownPlayers.forget(id); }
}
