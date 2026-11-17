package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.Visit;
import java.util.UUID;
import org.bukkit.Location;

/** Laufender Besuch (Server-Thread; {@link #phase} volatile für Abfragen von außen). */
final class VisitImpl implements Visit {
    private final UUID player;
    private final String worldId;
    private final long startedAt;
    private final Location returnLocation;
    volatile Phase phase = Phase.ENTERING;

    VisitImpl(UUID player, String worldId, long startedAt, Location returnLocation) {
        this.player = player;this.worldId = worldId;this.startedAt = startedAt;this.returnLocation = returnLocation;
    }

    @Override public UUID player() { return player; }
    @Override public String worldId() { return worldId; }
    @Override public long startedAt() { return startedAt; }
    @Override public Phase phase() { return phase; }
    @Override public Location returnLocation() { return returnLocation == null ? null : returnLocation.clone(); }
}
