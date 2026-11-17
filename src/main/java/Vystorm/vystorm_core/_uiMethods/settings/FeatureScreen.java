package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Feature switches as a Vystorm screen (0.60.0, {@code /vadmin features}): an overview of the plugins the admin may
 * configure with "on / total", and one page per plugin with a row per feature. A click on a row flips the switch and
 * saves it through the config editor (backup, history, reload hook, restart notice). Hovering shows the description,
 * the mouse wheel scrolls. Without a screen the config editor opens instead. Server thread.
 */
final class FeatureScreen {
    static final double MARGIN = 0.05;
    static final int MAX_DESCRIPTION = 90;
    /** The plugin page's own Back button uses Core's id, so Core leads it to the parent screen when there is one. */
    static final String BACK = "vystorm:back";
    static final int ON = 0xC02E7D46, OFF = 0xC08A3A3A, ROW = 0x50252A33;

    /** Panel size and row height for one display profile; lengths scale with the UI size. */
    record Metrics(double width, double height, double scale) {
        static Metrics of(ScreenProfile profile) {
            return new Metrics(Math.max(1.0, Math.min(2.6, profile.viewWidth() * 0.7)), Math.max(0.7, Math.min(1.6, profile.viewHeight() * 0.8)), profile.scale());
        }
        double top() { return 0.15 * scale; }
        double footer() { return 0.13 * scale; }
        double row() { return 0.075 * scale; }
        int rows() { return Math.max(1, (int) Math.floor((height - top() - footer() + 1e-9) / row())); }
        int maxOffset(int count) { return Math.max(0, count - rows()); }
    }

    private FeatureScreen() {}

    private static Lang lang() { return Localization.get().core(); }

    /** Plugins with at least one feature switch that this player may configure. */
    static List<ConfigEditorService.PluginState> plugins(ConfigEditorService service, Player player) {
        return service.visible(player).stream().filter(s -> !service.features(s).isEmpty()).toList();
    }

    static void overview(ConfigEditorService service, Player player, int offset) {
        Plugin core = service.corePlugin();
        List<ConfigEditorService.PluginState> states = plugins(service, player);
        if (states.isEmpty()) { lang().send(player, "features.none"); return; }
        Metrics m = Metrics.of(Screens.of(core).profile(player));
        int[] at = { Math.max(0, Math.min(m.maxOffset(states.size()), offset)) };
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:features", overviewLayout(service, player, states, m, at[0]))
                .backdrop(ScreenSpec.Backdrop.BLACK)
                .onInput((session, input) -> {
                    switch (input) {
                        case ScreenInput.Scroll scroll -> {
                            int next = Math.max(0, Math.min(m.maxOffset(states.size()), at[0] + scroll.delta()));
                            if (next != at[0]) { at[0] = next; session.setLayout(overviewLayout(service, player, states, m, next)); }
                        }
                        case ScreenInput.Click click when "close".equals(click.widgetId()) -> session.close();
                        case ScreenInput.Click click -> {
                            int index = index(click.widgetId(), 'p', states.size());
                            if (index < 0) return;
                            session.close();
                            plugin(service, player, states.get(index), 0);
                        }
                        default -> { }
                    }
                })
                .fallback(service::showOverview)
                .build();
        Screens.of(core).open(player, spec, "vystorm_core:features", p -> overview(service, p, 0));
    }

    static ScreenLayout overviewLayout(ConfigEditorService service, Player player, List<ConfigEditorService.PluginState> states, Metrics m, int offset) {
        double s = m.scale(), w = m.width(), button = 0.22 * s;
        List<ScreenWidget> widgets = new ArrayList<>();
        widgets.add(ScreenWidget.label("title", MARGIN, 0.03 * s, w - 2 * MARGIN - button, 0.08 * s, lang().component(player, "features.title")));
        widgets.add(ScreenWidget.button("close", w - MARGIN - button, 0.03 * s, button, 0.07 * s, lang().component(player, "menu.screen.close")));
        double v = m.top();
        for (int i = offset; i < Math.min(states.size(), offset + m.rows()); i++) {
            List<ConfigEditorService.FeatureView> features = service.features(states.get(i));
            long on = features.stream().filter(ConfigEditorService.FeatureView::on).count();
            widgets.add(ScreenWidget.button("p" + i, MARGIN, v + 0.004, w - 2 * MARGIN, m.row() - 0.008, Component.text(states.get(i).owner.getName()))
                    .withAlign(ScreenWidget.Align.START).withTextHeight(0.04 * s).withFill(ROW));
            widgets.add(ScreenWidget.label("c" + i, w - MARGIN - 0.5 * s, v + 0.018 * s, 0.48 * s, 0.04 * s,
                    lang().component(player, "features.count", "on", on, "total", features.size())).withAlign(ScreenWidget.Align.END));
            v += m.row();
        }
        widgets.add(ScreenWidget.label("footer", MARGIN, m.height() - m.footer() + 0.02 * s, w - 2 * MARGIN, 0.04 * s,
                lang().component(player, "features.hint-overview", "page", offset + 1, "pages", m.maxOffset(states.size()) + 1)));
        return new ScreenLayout(w, m.height(), widgets);
    }

    static void plugin(ConfigEditorService service, Player player, ConfigEditorService.PluginState state, int offset) {
        Plugin core = service.corePlugin();
        String name = state.owner.getName();
        if (service.features(state).isEmpty()) { lang().send(player, "features.none"); return; }
        Metrics m = Metrics.of(Screens.of(core).profile(player));
        int[] at = { Math.max(0, Math.min(m.maxOffset(service.features(state).size()), offset)) };
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:features-plugin", pluginLayout(service, player, state, m, at[0], null))
                .backdrop(ScreenSpec.Backdrop.BLACK)
                .onInput((session, input) -> {
                    List<ConfigEditorService.FeatureView> features = service.features(state);
                    switch (input) {
                        case ScreenInput.Scroll scroll -> {
                            int next = Math.max(0, Math.min(m.maxOffset(features.size()), at[0] + scroll.delta()));
                            if (next != at[0]) { at[0] = next; session.setLayout(pluginLayout(service, player, state, m, next, null)); }
                        }
                        case ScreenInput.Hover hover -> {
                            int index = index(hover.widgetId(), 'f', features.size());
                            session.setText("footer", index < 0 ? hint(player, m, at[0], features.size()) : describe(player, features.get(index)));
                        }
                        case ScreenInput.Click click when "close".equals(click.widgetId()) -> session.close();
                        // only reached without a parent screen (/vadmin features PLUGIN): Core handles Back otherwise
                        case ScreenInput.Click click when BACK.equals(click.widgetId()) -> { session.close(); overview(service, player, 0); }
                        case ScreenInput.Click click -> {
                            int index = index(click.widgetId(), 'f', features.size());
                            if (index < 0) return;
                            if (!state.owner.isEnabled()) { session.close(); return; }
                            ConfigEditorService.FeatureView feature = features.get(index);
                            String problem = service.toggleFeature(player, state, feature);
                            Component note = problem != null ? Component.text(problem, NamedTextColor.RED)
                                    : describe(player, service.features(state).stream().filter(f -> f.path().equals(feature.path())).findFirst().orElse(feature));
                            if (session.isOpen()) session.setLayout(pluginLayout(service, player, state, m, at[0], note));
                        }
                        default -> { }
                    }
                })
                .fallback(p -> service.open(p, name))
                .build();
        Screens.of(core).open(player, spec, "vystorm_core:features:" + name, p -> plugin(service, p, state, 0));
    }

    static ScreenLayout pluginLayout(ConfigEditorService service, Player player, ConfigEditorService.PluginState state, Metrics m, int offset, Component footer) {
        double s = m.scale(), w = m.width(), button = 0.22 * s, gap = 0.02 * s, stateWidth = 0.2 * s;
        List<ConfigEditorService.FeatureView> features = service.features(state);
        List<ScreenWidget> widgets = new ArrayList<>();
        widgets.add(ScreenWidget.label("title", MARGIN, 0.03 * s, w - 2 * MARGIN - 2 * (button + gap), 0.08 * s,
                lang().component(player, "features.title-plugin", "plugin", state.owner.getName())));
        widgets.add(ScreenWidget.button(BACK, w - MARGIN - 2 * button - gap, 0.03 * s, button, 0.07 * s, lang().component(player, "menu.back")));
        widgets.add(ScreenWidget.button("close", w - MARGIN - button, 0.03 * s, button, 0.07 * s, lang().component(player, "menu.screen.close")));
        double v = m.top();
        for (int i = offset; i < Math.min(features.size(), offset + m.rows()); i++) {
            ConfigEditorService.FeatureView feature = features.get(i);
            widgets.add(ScreenWidget.button("f" + i, MARGIN, v + 0.004, w - 2 * MARGIN, m.row() - 0.008,
                    Component.text(Localization.get().resolve(player, feature.label()))).withAlign(ScreenWidget.Align.START).withTextHeight(0.04 * s).withFill(ROW));
            widgets.add(ScreenWidget.fill("b" + i, w - MARGIN - stateWidth - 0.01, v + 0.012 * s, stateWidth, m.row() - 0.024 * s, feature.on() ? ON : OFF));
            widgets.add(ScreenWidget.label("s" + i, w - MARGIN - stateWidth - 0.01, v + 0.018 * s, stateWidth, 0.04 * s,
                    lang().component(player, feature.on() ? "features.state-on" : "features.state-off")).withAlign(ScreenWidget.Align.CENTER));
            v += m.row();
        }
        widgets.add(ScreenWidget.label("footer", MARGIN, m.height() - m.footer() + 0.02 * s, w - 2 * MARGIN, 0.04 * s,
                footer != null ? footer : hint(player, m, offset, features.size())));
        return new ScreenLayout(w, m.height(), widgets);
    }

    private static Component hint(Player player, Metrics m, int offset, int count) {
        return lang().component(player, "features.hint-plugin", "page", offset + 1, "pages", m.maxOffset(count) + 1);
    }

    /** Footer for one feature: state, description and when a change applies. */
    static Component describe(Player player, ConfigEditorService.FeatureView feature) {
        String description = Localization.get().resolve(player, feature.description());
        // The footer is one line; longer texts would be shrunk until nobody can read them.
        if (description.length() > MAX_DESCRIPTION) description = description.substring(0, MAX_DESCRIPTION - 1).stripTrailing() + "…";
        Component text = Component.text(Localization.get().resolve(player, feature.label()) + ": ")
                .append(lang().component(player, feature.on() ? "features.state-on" : "features.state-off"));
        if (feature.activation() == SettingPolicy.Activation.RESTART) text = text.append(Component.text(" · ")).append(lang().component(player, "features.restart"));
        return description.isBlank() ? text : text.append(Component.text(" – " + description, NamedTextColor.GRAY));
    }

    /** Index of a widget id such as {@code f12} for the given prefix, or -1. */
    static int index(String widgetId, char prefix, int size) {
        if (widgetId == null || widgetId.length() < 2 || widgetId.charAt(0) != prefix) return -1;
        try {
            int index = Integer.parseInt(widgetId.substring(1));
            return index >= 0 && index < size ? index : -1;
        } catch (NumberFormatException other) {
            return -1;
        }
    }
}
