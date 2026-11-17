package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._advancedMethods.packageHandler.PackageInterface;
import Vystorm.vystorm_core._advancedMethods.packageHandler.PackageHandler;
import java.util.Objects;

public class PackageSingelton {
    private PackageInterface instance;
    public PackageInterface getInstance() {
        if (instance == null) instance = new PackageHandler();
        return instance;
    }
    public void override(PackageInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (instance != null && instance != replacement) instance.clear();
        instance = replacement;
    }
    public void reset() { override(new PackageHandler()); }
    public void clear() { if (instance != null) instance.clear(); }
    public void removeAll(org.bukkit.plugin.Plugin owner) { if (instance != null) instance.removeAll(owner); }
}
