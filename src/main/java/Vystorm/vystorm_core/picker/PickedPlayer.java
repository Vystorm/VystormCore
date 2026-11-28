package Vystorm.vystorm_core.picker;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * One player in a picker (0.71.0): a row while choosing, and an entry of {@link PlayerPicks#chosen()} afterwards.
 *
 * @param id       the player's UUID
 * @param name     the current name
 * @param online   whether the player is online and visible to the viewer when the row was built
 * @param lastSeen last seen, epoch ms (now for online players; 0 when unknown)
 * @since 0.71.0
 */
public record PickedPlayer(UUID id, String name, boolean online, long lastSeen) {
    public PickedPlayer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    /** The online player, if online now. Server thread. */
    public Optional<Player> player() { return Optional.ofNullable(Bukkit.getPlayer(id)).filter(Player::isOnline); }
}
