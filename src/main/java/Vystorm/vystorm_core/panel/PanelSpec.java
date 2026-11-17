package Vystorm.vystorm_core.panel;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Beschreibung eines Panels (Spec §5). Unveränderlich. @since 0.25.0 */
public final class PanelSpec {
    private final String title;
    private final Predicate<Player> access;
    private final Function<PanelContext, Panel> render;
    private final MenuCategory menuCategory;
    private final Material menuIcon;
    private final String menuDescription;
    private final String webModule;
    private final String webSubPath;
    private final boolean dialogFallback;
    private final Consumer<Player> fallback;

    private PanelSpec(Builder b) {
        title = b.title;
        access = b.access;
        render = b.render;
        menuCategory = b.menuCategory;
        menuIcon = b.menuIcon;
        menuDescription = b.menuDescription;
        webModule = b.webModule;
        webSubPath = b.webSubPath;
        dialogFallback = b.dialogFallback;
        fallback = b.fallback;
    }

    /** @param title Anzeigename (auch {@code Lang.ref}) für Menüeintrag und Dialogtitel */
    public static Builder builder(String title) { return new Builder(title); }

    public String title() { return title; }
    public Predicate<Player> access() { return access; }
    public Function<PanelContext, Panel> render() { return render; }
    public MenuCategory menuCategory() { return menuCategory; }
    public Material menuIcon() { return menuIcon; }
    public String menuDescription() { return menuDescription; }
    public String webModule() { return webModule; }
    public String webSubPath() { return webSubPath; }
    public boolean dialogFallback() { return dialogFallback; }
    public Consumer<Player> fallback() { return fallback; }

    public static final class Builder {
        private final String title;
        private Predicate<Player> access = p -> true;
        private Function<PanelContext, Panel> render;
        private MenuCategory menuCategory;
        private Material menuIcon;
        private String menuDescription = "";
        private String webModule;
        private String webSubPath = "";
        private boolean dialogFallback;
        private Consumer<Player> fallback;

        private Builder(String title) { this.title = Objects.requireNonNull(title, "title"); }

        /** Wird beim Öffnen **und** bei jeder Aktion geprüft. */
        public Builder access(Predicate<Player> access) { this.access = Objects.requireNonNull(access); return this; }

        /** Panel als Funktion des Zustands; wird bei Öffnen und {@code refresh} aufgerufen (Server-Thread). */
        public Builder render(Function<PanelContext, Panel> render) { this.render = Objects.requireNonNull(render); return this; }

        /** Eintrag in {@code /vmenu} (öffnet nativ, sonst Fallback). */
        public Builder menu(MenuCategory category, Material icon, String description) {
            this.menuCategory = Objects.requireNonNull(category);
            this.menuIcon = Objects.requireNonNull(icon);
            this.menuDescription = description == null ? "" : description;
            return this;
        }

        /** Knopf „Im Browser öffnen“ und Link-Fallback. */
        public Builder web(String moduleId, String subPath) {
            this.webModule = Objects.requireNonNull(moduleId);
            this.webSubPath = subPath == null ? "" : subPath;
            return this;
        }

        /** Automatischer Paper-Dialog, wenn das Panel dialogfähig ist (Spec §7). */
        public Builder dialogFallback(boolean on) { this.dialogFallback = on; return this; }

        /** Eigener Kisten-/Dialogweg; hat Vorrang vor Dialog und Link. */
        public Builder fallback(Consumer<Player> fallback) { this.fallback = fallback; return this; }

        public PanelSpec build() {
            if (render == null) throw new IllegalStateException("PanelSpec needs render(...)");
            return new PanelSpec(this);
        }
    }
}
