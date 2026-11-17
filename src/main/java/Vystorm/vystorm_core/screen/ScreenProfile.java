package Vystorm.vystorm_core.screen;

/**
 * A player's display as Vystorm Screens see it (since 0.57.0). The server cannot ask the client for its window size,
 * so players set it in {@code /screen settings}: aspect ratio (width / height), vertical field of view in degrees (the
 * Minecraft FOV setting) and a UI size factor. Panel units are blocks at distance 1, so the visible area is
 * {@link #viewWidth()} x {@link #viewHeight()} units.
 */
public record ScreenProfile(double aspect, int fov, double scale) {
    public static final ScreenProfile DEFAULT = new ScreenProfile(16.0 / 9.0, 70, 1.0);
    public static final int MIN_FOV = 30, MAX_FOV = 110;
    public static final double MIN_SCALE = 0.6, MAX_SCALE = 1.6, MIN_ASPECT = 1.0, MAX_ASPECT = 4.5;

    public ScreenProfile {
        aspect = Double.isFinite(aspect) ? Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, aspect)) : DEFAULT.aspect;
        fov = Math.max(MIN_FOV, Math.min(MAX_FOV, fov));
        scale = Double.isFinite(scale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)) : 1.0;
    }

    /** Visible height in panel units: {@code 2 tan(fov / 2)}. */
    public double viewHeight() { return 2 * Math.tan(Math.toRadians(fov) / 2); }

    /** Visible width in panel units. */
    public double viewWidth() { return viewHeight() * aspect; }

    public ScreenProfile withAspect(double next) { return new ScreenProfile(next, fov, scale); }
    public ScreenProfile withFov(int next) { return new ScreenProfile(aspect, next, scale); }
    public ScreenProfile withScale(double next) { return new ScreenProfile(aspect, fov, next); }

    /** Parses {@code 2560x1440} (also {@code ×}, {@code *}, spaces) into an aspect ratio; null when unreadable. */
    public static Double parseResolution(String text) {
        if (text == null) return null;
        String[] parts = text.trim().toLowerCase(java.util.Locale.ROOT).split("\\s*[x×*:]\\s*|\\s+");
        if (parts.length != 2) return null;
        try {
            double w = Double.parseDouble(parts[0]), h = Double.parseDouble(parts[1]);
            return w > 0 && h > 0 ? w / h : null;
        } catch (NumberFormatException notNumbers) {
            return null;
        }
    }
}
