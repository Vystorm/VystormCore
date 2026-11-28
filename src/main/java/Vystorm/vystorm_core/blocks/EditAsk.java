package Vystorm.vystorm_core.blocks;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Question to {@code api.area().mayEdit(ask)}: may {@code source} change cells of one chunk between {@code minY} and
 * {@code maxY} (inclusive) for {@code actor}?
 *
 * @param source the plugin that wants to write
 * @param actor the player the edit is for, or null for console and plugin edits
 * @since 0.69.0
 */
public record EditAsk(Plugin source, UUID actor, World world, int chunkX, int chunkZ, int minY, int maxY) {
    public EditAsk {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(world, "world");
        if (minY > maxY) throw new IllegalArgumentException("minY " + minY + " > maxY " + maxY);
    }
}
