package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.ManagedWorld;
import Vystorm.vystorm_core.worlds.WorldSpec;
import java.nio.file.Path;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Registrierte Plugin-Welt. {@link #world} wird beim Entladen {@code null}. */
public final class ManagedWorldImpl implements ManagedWorld {
    private final Plugin owner;
    private final WorldSpec spec;
    private final Path folder;
    private final boolean rebuilt;
    private volatile World world;

    public ManagedWorldImpl(Plugin owner, WorldSpec spec, World world, Path folder, boolean rebuilt) {
        this.owner = owner;this.spec = spec;this.world = world;this.folder = folder;this.rebuilt = rebuilt;
    }

    @Override public String id() { return spec.id(); }
    @Override public Plugin owner() { return owner; }
    @Override public WorldSpec spec() { return spec; }
    @Override public World world() { return world; }
    @Override public boolean rebuilt() { return rebuilt; }
    @Override public Path folder() { return folder; }

    void unloaded() { world = null; }
}
