package Vystorm.vystorm_core.compat;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Manager view of {@link PassengerTeleport}, reached through {@code api.compat().passengers()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link PassengerTeleport}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface PassengerTeleportInterface {
    /** The delegating default. Plugins use {@code api.compat().passengers()}. */
    PassengerTeleportInterface DEFAULT = new PassengerTeleportInterface() {};

    /** See {@link PassengerTeleport#teleportRetainingPassengers}. */
    default boolean teleportRetainingPassengers(Entity vehicle, Location target) { return PassengerTeleport.teleportRetainingPassengers(vehicle, target); }
}
