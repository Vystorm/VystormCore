package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureGeneratorInterface;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureGeneratorHandler;
import java.util.Objects;
import org.bukkit.plugin.Plugin;

/** Shared registry, also safely published to LimitedRegion generation threads. */
public class StructureGeneratorSingelton {
    private volatile StructureGeneratorInterface instance = new StructureGeneratorHandler();

    public StructureGeneratorInterface getInstance() { return instance; }

    public synchronized void override(StructureGeneratorInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (instance != replacement) instance.clear();
        instance = replacement;
    }

    public void reset() { override(new StructureGeneratorHandler()); }
    public void clear() { instance.clear(); }
    public void removeAll(Plugin owner) { instance.removeAll(owner); }
}
