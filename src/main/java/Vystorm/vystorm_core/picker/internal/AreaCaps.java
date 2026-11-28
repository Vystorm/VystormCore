package Vystorm.vystorm_core.picker.internal;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Core's caps of an area pick (config {@code pickers.world.max-area}; design 4.6, owner decision 5): the largest size
 * per axis and the most blocks any plugin gets. The config may only lower them below {@link #HARD}. Core-internal.
 */
public record AreaCaps(int x, int y, int z, long volume) {
    /** 512 x 384 x 512 and 50,000,000 blocks. */
    public static final AreaCaps HARD = new AreaCaps(512, 384, 512, 50_000_000L);

    static AreaCaps read(ConfigurationSection config) {
        if (config == null) return HARD;
        long volume = config.getLong("pickers.world.max-area.volume", HARD.volume);
        return new AreaCaps(
                PickerConfig.clamp(config.getInt("pickers.world.max-area.x", HARD.x), 1, HARD.x),
                PickerConfig.clamp(config.getInt("pickers.world.max-area.y", HARD.y), 1, HARD.y),
                PickerConfig.clamp(config.getInt("pickers.world.max-area.z", HARD.z), 1, HARD.z),
                Math.max(1, Math.min(HARD.volume, volume)));
    }
}
