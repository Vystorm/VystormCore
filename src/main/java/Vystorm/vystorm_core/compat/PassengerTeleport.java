package Vystorm.vystorm_core.compat;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Bukkit vehicle teleport with an explicit snapshot of the complete passenger tree.
 * <p>Preferred access: {@code api.compat().passengers()}.
 */
public final class PassengerTeleport {
    private PassengerTeleport() {}
    private record Seat(Entity entity, Entity parent, Location origin) {}

    public static boolean teleportRetainingPassengers(Entity vehicle, Location target) {
        try {
            Class<?> flag = Class.forName("io.papermc.paper.entity.TeleportFlag");
            Class<?> state = Class.forName("io.papermc.paper.entity.TeleportFlag$EntityState");
            Object flags = java.lang.reflect.Array.newInstance(flag, 1);
            java.lang.reflect.Array.set(flags, 0, state.getField("RETAIN_PASSENGERS").get(null));
            return (Boolean) Entity.class.getMethod("teleport", Location.class, flags.getClass()).invoke(vehicle, target, flags);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return teleportBukkit(vehicle, target);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Passenger teleport failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Passenger teleport inaccessible", failure);
        }
    }

    static boolean teleportBukkit(Entity vehicle, Location target) {
        if (target == null || target.getWorld() == null) return false;
        List<Seat> seats = new ArrayList<>();
        snapshot(vehicle, null, seats);
        // Bukkit refuses teleporting vehicles with passengers. Restore all seats if an event vetoes any move.
        for (int i = seats.size() - 1; i >= 0; i--) {
            Entity passenger = seats.get(i).entity();
            if (passenger.isInsideVehicle() && !passenger.leaveVehicle()) { restoreSeats(seats); return false; }
        }
        List<Seat> moved = new ArrayList<>();
        for (Seat seat : seats) {
            Location destination = target.clone();
            if (seat.parent() != null) {
                destination.setYaw(seat.origin().getYaw());
                destination.setPitch(seat.origin().getPitch());
            }
            if (!seat.entity().teleport(destination)) {
                for (int i = moved.size() - 1; i >= 0; i--) moved.get(i).entity().teleport(moved.get(i).origin());
                restoreSeats(seats);
                return false;
            }
            moved.add(seat);
        }
        return restoreSeats(seats);
    }

    private static void snapshot(Entity entity, Entity parent, List<Seat> seats) {
        seats.add(new Seat(entity, parent, entity.getLocation().clone()));
        for (Entity passenger : entity.getPassengers()) snapshot(passenger, entity, seats);
    }

    private static boolean restoreSeats(List<Seat> seats) {
        boolean restored = true;
        for (Seat seat : seats) if (seat.parent() != null
                && !seat.parent().getPassengers().contains(seat.entity()))
            restored &= seat.parent().addPassenger(seat.entity());
        return restored;
    }
}
