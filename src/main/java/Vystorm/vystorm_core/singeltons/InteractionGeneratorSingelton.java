package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._advancedMethods.interactionGeneratorHandler.InteractionGeneratorInterface;
import Vystorm.vystorm_core._advancedMethods.interactionGeneratorHandler.InteractionGeneratorHandler;
import java.util.Objects;

public class InteractionGeneratorSingelton {
    private InteractionGeneratorInterface instance;
    public InteractionGeneratorInterface getInstance() {
        if (instance == null) instance = new InteractionGeneratorHandler();
        return instance;
    }
    public void override(InteractionGeneratorInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (instance != null && instance != replacement) instance.clear();
        instance = replacement;
    }
    public void reset() { override(new InteractionGeneratorHandler()); }
    public void clear() { if (instance != null) instance.clear(); }
    public void removeAll(org.bukkit.plugin.Plugin owner) { if (instance != null) instance.removeAll(owner); }
}
