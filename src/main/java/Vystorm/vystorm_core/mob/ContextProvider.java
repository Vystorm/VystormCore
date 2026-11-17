package Vystorm.vystorm_core.mob;

import java.util.List;

/**
 * Liefert Kontextebenen für einen Ort/Mob. Wird auf dem Server-Thread aufgerufen, muss schnell sein und darf
 * keine Chunks laden. Ergebnis wird vom Mob-System zwischengespeichert und periodisch erneuert.
 */
public interface ContextProvider {
    String id();

    List<ContextLayer> layers(MobContextQuery query);
}
