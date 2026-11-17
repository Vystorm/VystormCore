package Vystorm.vystorm_core.worlds.internal;

import java.util.List;
import java.util.UUID;

/**
 * Gesicherter Spielerzustand eines Besuchs (Format siehe {@link VisitSnapshotStore}). Items liegen als Base64 von
 * {@code ItemStack.serializeItemsAsBytes} vor: {@code items} = Inventar 0–40 (inkl. Rüstung, Nebenhand),
 * {@code extraItems} = Cursor und 2×2-Handwerksfeld (werden beim Zurückspielen ins Inventar gelegt, Überlauf fällt vor
 * die Füße). {@code air}/{@code walkSpeed} &lt; 0 = nicht gesichert (Format 1).
 */
public record VisitSnapshot(int format, UUID player, String worldId, String worldName, String owner, long createdAt,
                            String returnWorld, double x, double y, double z, float yaw, float pitch,
                            String gameMode, String items, String extraItems, int heldSlot, List<Effect> effects,
                            double health, int food, float saturation, float exhaustion, int fireTicks, int freezeTicks, int air,
                            float fallDistance, boolean allowFlight, boolean flying, float flySpeed, float walkSpeed,
                            boolean collidable, boolean invulnerable, String legacyToolKey, int experienceLevel, int experienceTotal, float experienceProgress) {
    public static final int FORMAT = 3;


    /** Compatibility constructor for snapshots without isolated XP. */
    public VisitSnapshot(int format, UUID player, String worldId, String worldName, String owner, long createdAt,
                            String returnWorld, double x, double y, double z, float yaw, float pitch,
                            String gameMode, String items, String extraItems, int heldSlot, List<Effect> effects,
                            double health, int food, float saturation, float exhaustion, int fireTicks, int freezeTicks, int air,
                            float fallDistance, boolean allowFlight, boolean flying, float flySpeed, float walkSpeed,
                            boolean collidable, boolean invulnerable, String legacyToolKey) {
        this(format, player, worldId, worldName, owner, createdAt, returnWorld, x, y, z, yaw, pitch, gameMode, items, extraItems, heldSlot, effects, health, food, saturation, exhaustion, fireTicks, freezeTicks, air, fallDistance, allowFlight, flying, flySpeed, walkSpeed, collidable, invulnerable, legacyToolKey, -1, -1, -1f);
    }

    public VisitSnapshot withExperience(int level, int total, float progress) {
        return new VisitSnapshot(format, player, worldId, worldName, owner, createdAt, returnWorld, x, y, z, yaw, pitch, gameMode, items, extraItems, heldSlot, effects, health, food, saturation, exhaustion, fireTicks, freezeTicks, air, fallDistance, allowFlight, flying, flySpeed, walkSpeed, collidable, invulnerable, legacyToolKey, level, total, progress);
    }

    public record Effect(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}

    public VisitSnapshot {
        effects = effects == null ? List.of() : List.copyOf(effects);
        items = items == null ? "" : items;
        extraItems = extraItems == null ? "" : extraItems;
        worldId = worldId == null ? "" : worldId;
        worldName = worldName == null ? "" : worldName;
        owner = owner == null ? "" : owner;
    }

    /** Derselbe Zustand mit einem anderen Rückkehrort. */
    public VisitSnapshot returnTo(String world, double nx, double ny, double nz, float nyaw, float npitch) {
        return new VisitSnapshot(format, player, worldId, worldName, owner, createdAt, world, nx, ny, nz, nyaw, npitch, gameMode, items,
                extraItems, heldSlot, effects, health, food, saturation, exhaustion, fireTicks, freezeTicks, air, fallDistance, allowFlight,
                flying, flySpeed, walkSpeed, collidable, invulnerable, legacyToolKey, experienceLevel, experienceTotal, experienceProgress);
    }
}
