package Vystorm.vystorm_core.region;

import java.util.Locale;
import java.util.Map;

/**
 * Standard-Prioritäten der Regionsarten (PROJECT_CONTEXT §8): Spawn/Safe vor Arena, Arena vor Markt und Stadt,
 * Stadt vor Strukturen und Fraktionsgebieten. Bei Überlappung gilt die Region mit der höchsten Priorität.
 */
public final class RegionPriority {
    public static final int SPAWN = 1000, SAFE = 950, ARENA_ZONE = 820, ARENA = 800, MARKET = 600, DUNGEON = 500,
            CITY = 400, STRUCTURE = 300, TERRITORY = 100, DEFAULT = 0;
    private static final Map<String, Integer> BY_KIND = Map.ofEntries(
            Map.entry("spawn", SPAWN), Map.entry("safe", SAFE), Map.entry("arena-zone", ARENA_ZONE), Map.entry("arena", ARENA),
            Map.entry("market", MARKET), Map.entry("dungeon", DUNGEON), Map.entry("city", CITY), Map.entry("structure", STRUCTURE),
            Map.entry("faction", TERRITORY), Map.entry("territory", TERRITORY));

    private RegionPriority() {}

    public static int forKind(String kind) { return kind == null ? DEFAULT : BY_KIND.getOrDefault(kind.toLowerCase(Locale.ROOT), DEFAULT); }
}
