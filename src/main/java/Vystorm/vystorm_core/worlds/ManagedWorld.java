package Vystorm.vystorm_core.worlds;

import java.nio.file.Path;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Eine von Core geladene Plugin-Welt. {@link #world()} ist {@code null}, sobald sie entladen ist.
 *
 * @since 0.26.0
 */
public interface ManagedWorld {
    String id();

    Plugin owner();

    WorldSpec spec();

    World world();

    /** Ob beim letzten Laden neu aufgebaut wurde (Hash anders oder Ordner neu). */
    boolean rebuilt();

    /** Echter Weltordner. */
    Path folder();
}
