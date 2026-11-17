package Vystorm.vystorm_core.display;

import Vystorm.vystorm_core.display.internal.DisplayPackets;
import Vystorm.vystorm_core.display.internal.IdPool;
import Vystorm.vystorm_core.display.internal.MapTiles;
import Vystorm.vystorm_core.display.internal.WallGeometry;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import Vystorm.vystorm_core.display.ClientDisplays.*;

/**
 * Manager view of {@link ClientDisplays}, reached through {@code api.packages().clientDisplays()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClientDisplays}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ClientDisplaysInterface {
    /** The delegating default. Plugins use {@code api.packages().clientDisplays()}. */
    ClientDisplaysInterface DEFAULT = new ClientDisplaysInterface() {};

    /** True when this server can show client-only displays (packet adapter built and its packets encode). */
    default boolean supported() { return ClientDisplays.supported(); }

    /** Why {@link #supported()} is false; empty when supported. */
    default Optional<String> unsupportedReason() { return ClientDisplays.unsupportedReason(); }

    /** A new client-only map wall, or empty when unsupported or the owner is disabled. */
    default Optional<MapWall> mapWall(Plugin owner, MapWall.Spec spec) { return ClientDisplays.mapWall(owner, spec); }

    /** Removes every wall of the plugin (also done automatically when it disables). */
    default void release(Plugin owner) { ClientDisplays.release(owner); }

    /** Map packets per player and in total per tick (defaults 8 and 64). A full tile is 16 KiB before compression. */
    default void budgets(int mapsPerPlayerPerTick, int mapsPerTick) { ClientDisplays.budgets(mapsPerPlayerPerTick, mapsPerTick); }

    /** See {@link ClientDisplays#stats}. */
    default Stats stats() { return ClientDisplays.stats(); }
}
