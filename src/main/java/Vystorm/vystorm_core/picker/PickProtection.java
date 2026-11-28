package Vystorm.vystorm_core.picker;

/**
 * Which protection a world pick asks before a target may be chosen (0.71.0).
 *
 * @since 0.71.0
 */
public enum PickProtection {
    /** None: the plugin's own permission decides (admin tools). Default. */
    NONE,
    /**
     * {@code api.area().mayEdit(..)} for the cell the target changes (the block itself; for a face the cell in front of
     * it): Core's regions (flag {@code build}) and every plugin that registered an edit guard. Players with the pick's
     * {@link WorldPick.Builder#bypass bypass} permission skip it.
     */
    BUILD
}
