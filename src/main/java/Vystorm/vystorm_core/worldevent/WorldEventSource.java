package Vystorm.vystorm_core.worldevent;

import java.util.List;
import java.util.UUID;

/**
 * Wird vom Plugin implementiert, das World-Events verwaltet (WorldDirector). Aufrufe müssen schnell und
 * seiteneffektfrei sein und dürfen keine Chunks laden.
 */
public interface WorldEventSource {
    /** Alle laufenden Events. */
    List<WorldEventView> active();

    /** Events, die an dieser Position wirken (global, Welt, Region, lokal). */
    List<WorldEventView> activeAt(UUID worldId, int x, int y, int z);

    /** Kombinierter Multiplikator der an dieser Position wirkenden Events für einen Modifikator-Schlüssel (1.0 = neutral). */
    double modifier(String key, UUID worldId, int x, int y, int z);
}
