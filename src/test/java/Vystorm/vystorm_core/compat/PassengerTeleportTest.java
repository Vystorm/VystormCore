package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

class PassengerTeleportTest {
    @Test void nestedSeatsAreRestoredAfterTeleport() {
        World world = mock(World.class);
        Entity boat = mock(Entity.class), rider = mock(Entity.class), pet = mock(Entity.class);
        List<Entity> boatSeats = new ArrayList<>(List.of(rider)), riderSeats = new ArrayList<>(List.of(pet));
        when(boat.getPassengers()).thenReturn(boatSeats);
        when(rider.getPassengers()).thenReturn(riderSeats);
        when(pet.getPassengers()).thenReturn(List.of());
        for (Entity entity : List.of(boat, rider, pet)) {
            when(entity.getLocation()).thenReturn(new Location(world, 1, 64, 1));
            when(entity.teleport(any(Location.class))).thenReturn(true);
            when(entity.addPassenger(any())).thenReturn(true);
        }
        when(rider.isInsideVehicle()).thenReturn(true);
        when(pet.isInsideVehicle()).thenReturn(true);
        when(rider.leaveVehicle()).thenAnswer(call -> {boatSeats.clear(); return true;});
        when(pet.leaveVehicle()).thenAnswer(call -> {riderSeats.clear(); return true;});
        assertTrue(PassengerTeleport.teleportBukkit(boat, new Location(world, 9, 70, 9)));
        verify(boat).addPassenger(rider);
        verify(rider).addPassenger(pet);
    }
    @Test void passengerCancellationRollsVehicleBackAndRestoresSeat() {
        World world = mock(World.class);
        Entity boat = mock(Entity.class), rider = mock(Entity.class);
        List<Entity> seats = new ArrayList<>(List.of(rider));
        when(boat.getPassengers()).thenReturn(seats);
        when(rider.getPassengers()).thenReturn(List.of());
        Location origin = new Location(world, 1, 64, 1);
        when(boat.getLocation()).thenReturn(origin);
        when(rider.getLocation()).thenReturn(origin);
        when(rider.isInsideVehicle()).thenReturn(true);
        when(rider.leaveVehicle()).thenAnswer(call -> {seats.clear(); return true;});
        when(boat.teleport(any(Location.class))).thenReturn(true);
        when(rider.teleport(any(Location.class))).thenReturn(false);
        when(boat.addPassenger(rider)).thenReturn(true);
        assertFalse(PassengerTeleport.teleportBukkit(boat, new Location(world, 9, 70, 9)));
        verify(boat).teleport(origin);
        verify(boat).addPassenger(rider);
    }
}
