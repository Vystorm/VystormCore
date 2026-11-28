package Vystorm.vystorm_core.knownplayers;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A player who joined this server at least once (or was in the server's {@code usercache.json} when Core first
 * started): the current name, when Core first and last saw the player (epoch milliseconds) and the address of the skin
 * texture the server got with the player's profile. Old names are not kept. A value; reading it never touches the
 * network. @since 0.71.0
 *
 * @param id        the player's UUID
 * @param name      the newest known name
 * @param firstSeen first join Core saw, epoch ms (for players seeded from {@code usercache.json}: an estimate)
 * @param lastSeen  last join or quit Core saw, epoch ms (for seeded players: an estimate from the cache entry)
 * @param skinUrl   the skin texture address from the player's profile, if the server had one
 */
public record KnownPlayer(UUID id, String name, long firstSeen, long lastSeen, Optional<String> skinUrl) {
    public KnownPlayer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        skinUrl = skinUrl == null ? Optional.empty() : skinUrl;
    }
}
