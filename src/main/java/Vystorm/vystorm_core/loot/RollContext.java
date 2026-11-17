package Vystorm.vystorm_core.loot;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Immutable context of a loot roll: who, where, how hard, how lucky, which plugin and which own table.
 * Every value is optional; entry conditions with no matching value pass. Build it on the server thread
 * (or from values you already have, e.g. in a world populator) and roll anywhere. @since 0.44.0
 *
 * @param environment {@code normal}, {@code nether}, {@code the_end} or {@code custom}
 * @param biome namespaced biome key, e.g. {@code minecraft:lush_caves}
 * @param difficulty plugin-defined difficulty (dungeon level, threat, sky layer ...), 0 when unknown
 * @param luck luck points; with a player built by {@link #of(Player)} it already contains the Luck attribute
 *             and every {@link LootLuck} source
 * @param sourcePlugin name of the rolling plugin (diagnostics only)
 * @param self table id that {@code table:self} stands for in categories
 */
public record RollContext(UUID player, String world, String environment, Integer y, String biome, double difficulty,
                          double luck, String sourcePlugin, String self) {
    public static final RollContext NONE = new RollContext(null, null, null, null, null, 0, 0, "", "");

    public RollContext {
        environment = environment == null ? null : environment.toLowerCase(Locale.ROOT);
        biome = biome == null ? null : biome.toLowerCase(Locale.ROOT);
        if (!Double.isFinite(difficulty)) difficulty = 0;
        if (!Double.isFinite(luck)) luck = 0;
        sourcePlugin = sourcePlugin == null ? "" : sourcePlugin;
        self = self == null ? "" : self.trim();
    }

    /** Location context (world, environment, block y, biome). Reads the biome: server thread or a loaded chunk. */
    public static RollContext of(Location location) {
        if (location == null || location.getWorld() == null) return NONE;
        var world = location.getWorld();
        String biome = null;
        try { biome = location.getBlock().getBiome().getKey().toString(); } catch (RuntimeException | LinkageError ignored) { }
        return new RollContext(null, world.getName(), world.getEnvironment().name(), location.getBlockY(), biome, 0, 0, "", "");
    }

    /** Player context: location, uuid and luck (Luck attribute + {@link LootLuck} sources). Server thread. */
    public static RollContext of(Player player) {
        if (player == null) return NONE;
        RollContext at = of(player.getLocation());
        return new RollContext(player.getUniqueId(), at.world, at.environment, at.y, at.biome, 0, LootLuck.of(player), "", "");
    }

    public RollContext withDifficulty(double value) { return new RollContext(player, world, environment, y, biome, value, luck, sourcePlugin, self); }
    public RollContext withLuck(double value) { return new RollContext(player, world, environment, y, biome, difficulty, value, sourcePlugin, self); }
    public RollContext withY(Integer value) { return new RollContext(player, world, environment, value, biome, difficulty, luck, sourcePlugin, self); }
    public RollContext withBiome(String value) { return new RollContext(player, world, environment, y, value, difficulty, luck, sourcePlugin, self); }
    public RollContext withEnvironment(String value) { return new RollContext(player, world, value, y, biome, difficulty, luck, sourcePlugin, self); }
    public RollContext withSource(String plugin) { return new RollContext(player, world, environment, y, biome, difficulty, luck, plugin, self); }
    /** Sets the table that {@code table:self} in a category stands for. */
    public RollContext withSelf(String tableId) { return new RollContext(player, world, environment, y, biome, difficulty, luck, sourcePlugin, tableId); }
}
