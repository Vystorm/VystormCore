package Vystorm.vystorm_core.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Wurzel eines Panels ({@code page}). Nicht threadsicher; pro Render neu bauen. @since 0.25.0 */
public final class Panel {
    public enum Size { SMALL, MEDIUM, LARGE, FULL }

    public enum Theme { VYSTORM, VANILLA }

    final Object title;
    Object subtitle;
    Ui.Icon icon;
    Size size = Size.MEDIUM;
    Theme theme;
    String accent;
    final List<Ui.Element<?>> children = new ArrayList<>();
    final List<Ui.Button> footer = new ArrayList<>();

    private Panel(Object title) { this.title = Objects.requireNonNull(title); }

    /** @param title String, {@code Lang.ref} oder Adventure-{@code Component} */
    public static Panel page(Object title) { return new Panel(title); }

    public Panel subtitle(Object subtitle) { this.subtitle = subtitle; return this; }
    public Panel icon(Ui.Icon icon) { this.icon = icon; return this; }
    public Panel size(Size size) { this.size = Objects.requireNonNull(size); return this; }
    public Panel theme(Theme theme) { this.theme = theme; return this; }
    /** Plugin-Markenfarbe ({@code #RRGGBB}); das einzige überschreibbare Theme-Feld. */
    public Panel accent(String color) { this.accent = color; return this; }
    public Panel children(Ui.Element<?>... elements) { for (Ui.Element<?> e : elements) children.add(Objects.requireNonNull(e)); return this; }
    public Panel child(Ui.Element<?> element) { children.add(Objects.requireNonNull(element)); return this; }
    /** Feste Knopfleiste unten. */
    public Panel footer(Ui.Button... buttons) { for (Ui.Button b : buttons) footer.add(Objects.requireNonNull(b)); return this; }
}
