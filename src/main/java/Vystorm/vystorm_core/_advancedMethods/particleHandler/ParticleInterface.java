package Vystorm.vystorm_core._advancedMethods.particleHandler;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Server-thread particle operations. The default handler shares a tick budget across all methods.
 * Under load it reduces counts/resolution or skips effects; it does not queue deferred emissions.
 */
public interface ParticleInterface {
    <T> void spawn(Particle particle, Location location, int count, T data);
    /** World emission with Bukkit offsets/speed; legacy custom handlers fall back to their basic spawn. */
    default <T> void spawn(Particle particle, Location location, int count,
                          double offsetX, double offsetY, double offsetZ, double extra, T data) {
        spawn(particle, location, count, data);
    }
    <T> void spawn(Player viewer, Particle particle, Location location, int count, T data);
    <T> void line(Particle particle, Location from, Location to, int points, T data);
    <T> void circle(Particle particle, Location center, double radius, int points, T data);
    BukkitTask repeat(Plugin owner, Runnable effect, long periodTicks, int iterations);

    /** Per-player block projections and particles ({@code visual.Visuals}). @since 0.64.0 */
    default Vystorm.vystorm_core.visual.VisualsInterface visuals() { return Vystorm.vystorm_core.visual.VisualsInterface.DEFAULT; }

    /** Point lists for lines and outlines ({@code visual.ParticlePaths}). @since 0.64.0 */
    default Vystorm.vystorm_core.visual.ParticlePathsInterface paths() { return Vystorm.vystorm_core.visual.ParticlePathsInterface.DEFAULT; }
}
