package Vystorm.vystorm_core.panel;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import java.util.Objects;
import java.util.function.BiConsumer;
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
    private final BiConsumer<Player, FallbackContext> contextFallback;

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
        BiConsumer<Player, FallbackContext> withContext = b.contextFallback;
        contextFallback = withContext;
        fallback = withContext == null ? b.fallback
                : player -> Vystorm.vystorm_core.panel.internal.PanelOpener.runFresh(player, this, withContext);
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
    /**
     * The plugin's own fallback as a one-argument call. For a fallback given with
     * {@link Builder#contextFallback(BiConsumer)} this runs it with a fresh {@link FallbackContext} (empty argument and
     * session, no result), so code and tests that call {@code spec.fallback().accept(player)} keep working.
     */
    public Consumer<Player> fallback() { return fallback; }

    /** The fallback given with {@link Builder#contextFallback(BiConsumer)}, or null. @since 0.71.0 */
    public BiConsumer<Player, FallbackContext> contextFallback() { return contextFallback; }

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
        private BiConsumer<Player, FallbackContext> contextFallback;

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
        public Builder fallback(Consumer<Player> fallback) { this.fallback = fallback; this.contextFallback = null; return this; }

        /**
         * Own chest or dialog route that also gets the panel's argument, session values and the result of a pick that
         * came back ({@link FallbackContext}, read its session lifetime rule). Same place in the route order as
         * {@link #fallback(Consumer)}; the later of the two calls wins. A name of its own (not a {@code fallback}
         * overload), so {@code fallback(null)} and method references to overloaded methods still compile. @since 0.71.0
         */
        public Builder contextFallback(BiConsumer<Player, FallbackContext> fallback) { this.contextFallback = fallback; this.fallback = null; return this; }

        public PanelSpec build() {
            if (render == null) throw new IllegalStateException("PanelSpec needs render(...)");
            return new PanelSpec(this);
        }
    }
}
