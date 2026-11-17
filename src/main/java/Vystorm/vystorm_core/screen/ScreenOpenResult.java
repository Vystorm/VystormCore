package Vystorm.vystorm_core.screen;

/**
 * Result of {@link Screens#open} (since 0.57.0): opened in a mode, or refused with a lang key
 * ({@code screen.refused.falling|combat|liquid|riding|gliding|busy|unsupported}); a refusal already ran the fallback.
 */
public record ScreenOpenResult(boolean opened, ScreenSpec.Mode mode, String refusedKey) {
    public static ScreenOpenResult opened(ScreenSpec.Mode mode) { return new ScreenOpenResult(true, mode, null); }
    public static ScreenOpenResult refused(String key) { return new ScreenOpenResult(false, null, key); }
}
