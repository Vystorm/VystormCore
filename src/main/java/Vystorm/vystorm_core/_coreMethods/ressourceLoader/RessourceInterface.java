package Vystorm.vystorm_core._coreMethods.ressourceLoader;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayList;

public interface RessourceInterface {
     void load(ArrayList <String> paths, Plugin plugin) throws IOException, InvalidConfigurationException;
     void resetConfig(String path);

    /** The merged server resource pack ({@code pack.ResourcePacks}). @since 0.64.0 */
    default Vystorm.vystorm_core.pack.ResourcePacksInterface packs() { return Vystorm.vystorm_core.pack.ResourcePacksInterface.DEFAULT; }
}
