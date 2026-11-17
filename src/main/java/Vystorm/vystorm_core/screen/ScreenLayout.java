package Vystorm.vystorm_core.screen;

import java.util.List;
import java.util.Optional;

/**
 * Panel of a screen (since 0.57.0): its size in panel units and its widgets in drawing order. {@link #hit} takes the
 * centre-origin point from the screen geometry (u right, v up) and returns the topmost clickable widget there.
 */
public final class ScreenLayout {
    private final double width, height;
    private final List<ScreenWidget> widgets;

    public ScreenLayout(double width, double height, List<ScreenWidget> widgets) {
        if (!(width > 0) || !(height > 0)) throw new IllegalArgumentException("Screen panel needs a positive size");
        this.width = width;
        this.height = height;
        this.widgets = List.copyOf(widgets);
    }

    public double width() { return width; }
    public double height() { return height; }
    public List<ScreenWidget> widgets() { return widgets; }

    /** Topmost enabled button or input under the centre-origin point (u right, v up); empty outside the panel. */
    public Optional<ScreenWidget> hit(double u, double v) {
        double x = u + width / 2, y = height / 2 - v;
        if (x < 0 || x > width || y < 0 || y > height) return Optional.empty();
        for (int i = widgets.size() - 1; i >= 0; i--) {
            ScreenWidget w = widgets.get(i);
            if (w.clickable() && w.contains(x, y)) return Optional.of(w);
        }
        return Optional.empty();
    }
}
