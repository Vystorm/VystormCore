package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._advancedMethods.particleHandler.ParticleInterface;
import Vystorm.vystorm_core._advancedMethods.particleHandler.ParticleHandler;
import java.util.Objects;

public class ParticleSingelton {
    private ParticleInterface instance;
    public ParticleInterface getInstance() {
        if (instance == null) instance = new ParticleHandler();
        return instance;
    }
    public void override(ParticleInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        instance = replacement;
    }
    public void reset() { override(new ParticleHandler()); }
}
