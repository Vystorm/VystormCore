package Vystorm.vystorm_core.picker.internal;

import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** Section {@code pickers} of Core's config.yml (read at every picker start, so changes apply at once). Core-internal. */
public record PickerConfig(boolean enabled, int maxNearby, int defaultNearby, int recentDays, int searchMinChars, int searchMaxHits, Heads heads) {
    /** Most rows of the Recent tab. */
    public static final int RECENT_LIMIT = 200;

    /** Which chest heads get a skin: {@code all} (stored skin addresses, online players), {@code online}, {@code none}. */
    public enum Heads { ALL, ONLINE, NONE }

    public static final PickerConfig DEFAULTS = new PickerConfig(true, 64, 16, 30, 3, 20, Heads.ALL);

    public static PickerConfig read(ConfigurationSection config) {
        if (config == null) return DEFAULTS;
        int maxNearby = clamp(config.getInt("pickers.players.max-nearby", 64), 1, 256);
        Heads heads;
        try {
            heads = Heads.valueOf(config.getString("pickers.players.heads", "all").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException odd) {
            heads = Heads.ALL;
        }
        return new PickerConfig(config.getBoolean("pickers.enabled", true), maxNearby,
                clamp(config.getInt("pickers.players.default-nearby", 16), 1, maxNearby),
                clamp(config.getInt("pickers.players.recent-days", 30), 1, 3650),
                clamp(config.getInt("pickers.players.search-min-chars", 3), 1, 16),
                clamp(config.getInt("pickers.players.search-max-hits", 20), 1, 50), heads);
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
