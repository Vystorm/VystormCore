package Vystorm.vystorm_core.visual;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link Visuals}, reached through {@code api.particle().visuals()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link Visuals}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface VisualsInterface {
    /** The delegating default. Plugins use {@code api.particle().visuals()}. */
    VisualsInterface DEFAULT = new VisualsInterface() {};

    /** See {@link Visuals#open}. */
    default BlockView open(Plugin owner, Player viewer) { return Visuals.open(owner, viewer); }

    /** See {@link Visuals#release}. */
    default void release(Plugin owner) { Visuals.release(owner); }

    /** Emit points in the viewer's current world. Maximum 512 particles per call; no global broadcast. */
    default <T> void particles(Player viewer, Particle particle, List<Point3> points, T data) { Visuals.particles(viewer, particle, points, data); }

    /**
     * Sample destination blocks projected behind an upright aperture, clipped to the viewer's sight
     * through it. Radius 1..12, depth 1..16, maxBlocks 1..4096. Reads only loaded chunks in both worlds.
     * Tile entity payloads, light, collision and entities are not projected. Cardinal block orientations
     * are rotated to match the source frame. Caller owns refresh scheduling and distance limits.
     */
    default Map<BlockPosition, BlockData> projection(ViewFrame entrance, ViewFrame destination, Point3 eye, int radius, int depth, int maxBlocks) {
        return Visuals.projection(entrance, destination, eye, radius, depth, maxBlocks);
    }

    /**
     * Project loaded destination blocks beneath an upward-facing aperture. The opening mask contains
     * source-world block cells in the plane layer; gaps remain occluded (for example a centre pillar).
     * Radius 1..12, depth 1..16, maxBlocks 1..4096, mask at most 1024 cells. Server thread only.
     * Sampling only: does not load/generate chunks or change world blocks. @since 0.30.0
     */
    default Map<BlockPosition, BlockData> projection(HorizontalFrame entrance, HorizontalFrame destination, Point3 eye, int radius, int depth, int maxBlocks, Set<BlockPosition> opening) {
        return Visuals.projection(entrance, destination, eye, radius, depth, maxBlocks, opening);
    }
}
