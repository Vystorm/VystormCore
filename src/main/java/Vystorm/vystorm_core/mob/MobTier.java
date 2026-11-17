package Vystorm.vystorm_core.mob;

/** Stufenprofile statt eigener Entity-Systeme für Elite/Rare/Boss. */
public enum MobTier {
    NORMAL, VETERAN, ELITE, CHAMPION, RARE, BOSS, WORLD_BOSS;

    public static MobTier parse(String value, MobTier fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException invalid) { return fallback; }
    }
}
