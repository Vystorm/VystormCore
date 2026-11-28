package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.WorldPick;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Section {@code pickers.world} of Core's config.yml (read at every pick start, so changes apply at once). Values are
 * clamped to Core's hard caps: reach at most {@link WorldPick#MAX_REACH} (64), preview distance at most 64 blocks,
 * at most 512 preview points per pass (the particle call's limit). Core-internal.
 *
 * @param defaultReach    reach of a pick that keeps the default ({@link WorldPick#DEFAULT_REACH}); config may lower or raise it
 * @param maxReach        the largest reach a plugin gets
 * @param previewDistance outline points farther than this from the player are not drawn
 * @param previewPoints   most points per preview pass
 * @param typedDistance   typed coordinates may be at most this far from the player
 * @param area            Core's caps of an area pick ({@code pickers.world.max-area}, only lower than {@link AreaCaps#HARD})
 */
public record WorldPickConfig(boolean enabled, int defaultReach, int maxReach, int previewDistance, int previewPoints, int typedDistance, AreaCaps area) {
    public static final int HARD_PREVIEW_DISTANCE = 64, HARD_PREVIEW_POINTS = 512, HARD_TYPED_DISTANCE = 1024;

    public static final WorldPickConfig DEFAULTS = new WorldPickConfig(true, WorldPick.DEFAULT_REACH, WorldPick.MAX_REACH, 48, 450, 128, AreaCaps.HARD);

    public static WorldPickConfig read(ConfigurationSection config) {
        if (config == null) return DEFAULTS;
        int max = PickerConfig.clamp(config.getInt("pickers.world.max-reach", WorldPick.MAX_REACH), 1, WorldPick.MAX_REACH);
        return new WorldPickConfig(config.getBoolean("pickers.world.enabled", true),
                PickerConfig.clamp(config.getInt("pickers.world.default-reach", WorldPick.DEFAULT_REACH), 1, max), max,
                PickerConfig.clamp(config.getInt("pickers.world.preview-distance", 48), 4, HARD_PREVIEW_DISTANCE),
                PickerConfig.clamp(config.getInt("pickers.world.preview-points", 450), 24, HARD_PREVIEW_POINTS),
                PickerConfig.clamp(config.getInt("pickers.world.typed-distance", 128), 1, HARD_TYPED_DISTANCE),
                AreaCaps.read(config));
    }

    /**
     * The reach a pick gets: a pick that kept {@link WorldPick#DEFAULT_REACH} takes the config's default, any other value
     * is the plugin's own; both capped by {@link #maxReach}.
     */
    public int reach(WorldPick pick) {
        int asked = pick.reach() == WorldPick.DEFAULT_REACH ? defaultReach : pick.reach();
        return Math.max(1, Math.min(maxReach, asked));
    }
}
