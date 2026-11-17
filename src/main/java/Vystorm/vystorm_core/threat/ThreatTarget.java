package Vystorm.vystorm_core.threat;

import java.util.Objects;
import java.util.UUID;

/** Stable identifier used by Core to aggregate threat modifiers. */
public record ThreatTarget(ThreatScope scope, String key) {
    public ThreatTarget {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    public static ThreatTarget player(UUID playerId) {
        return new ThreatTarget(ThreatScope.PLAYER, Objects.requireNonNull(playerId, "playerId").toString());
    }

    public static ThreatTarget cluster(String clusterId) {
        return new ThreatTarget(ThreatScope.CLUSTER, clusterId);
    }

    public static ThreatTarget region(String regionId) {
        return new ThreatTarget(ThreatScope.REGION, regionId);
    }

    public static ThreatTarget world(UUID worldId) {
        return new ThreatTarget(ThreatScope.WORLD, Objects.requireNonNull(worldId, "worldId").toString());
    }
}
