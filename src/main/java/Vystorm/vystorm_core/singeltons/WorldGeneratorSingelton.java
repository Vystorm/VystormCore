package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._advancedMethods.worldGeneratorHandler.WorldGeneratorInterface;
import Vystorm.vystorm_core._advancedMethods.worldGeneratorHandler.WorldGeneratorHandler;
import java.util.Objects;

public class WorldGeneratorSingelton {
    private WorldGeneratorInterface instance;
    public WorldGeneratorInterface getInstance() {
        if (instance == null) instance = new WorldGeneratorHandler();
        return instance;
    }
    public void override(WorldGeneratorInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        instance = replacement;
    }
    public void reset() { override(new WorldGeneratorHandler()); }
}
