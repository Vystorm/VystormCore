package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import Vystorm.vystorm_core.screen.internal.ScreenListLayout;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Feature switches as a Vystorm screen (0.60.0, {@code /vadmin features}). Since 0.66.0 a settings list: one heading row
 * per plugin (name, "on / total"), under it one row per feature with its full name, ON/OFF on the right and its
 * description under the name. A click anywhere on a row flips the switch and saves it through the config editor
 * (backup, history, reload hook, restart notice). {@code /vadmin features PLUGIN} shows only that plugin, with Back.
 * The mouse wheel scrolls; the footer says which rows are shown and, while pointing at a row, its full description.
 * Without a screen the config editor opens instead. Server thread.
 */
final class FeatureScreen {
    static final double MARGIN = 0.05;
    static final int MAX_DESCRIPTION = 90;
    /** Rows per mouse-wheel step. */
    static final int SCROLL_ROWS = 3;
    /** The plugin page's own Back button uses Core's id, so Core leads it to the parent screen when there is one. */
    static final String BACK = "vystorm:back";
    static final String FOOTER = "footer";

    /** Panel size for one display profile; lengths scale with the UI size. */
    record Metrics(double width, double height, double scale) {
        static Metrics of(ScreenProfile profile) {
            return new Metrics(Math.max(1.0, Math.min(2.6, profile.viewWidth() * 0.7)), Math.max(0.7, Math.min(1.6, profile.viewHeight() * 0.8)), profile.scale());
        }
        double top() { return 0.15 * scale; }
        double footer() { return 0.13 * scale; }
        double listWidth() { return width - 2 * MARGIN; }
        double listHeight() { return Math.max(0.1, height - top() - footer()); }
        ScreenListLayout.Sizes sizes() { return new ScreenListLayout.Sizes(scale); }
    }

    /** The rows of the list and, per row, the plugin and feature behind it (both null for a heading). */
    record Model(List<ScreenListLayout.Row> rows, List<ConfigEditorService.PluginState> plugins, List<ConfigEditorService.FeatureView> features) {}

    private FeatureScreen() {}

    private static Lang lang() { return Localization.get().core(); }

    /** Plugins with at least one feature switch that this player may configure. */
    static List<ConfigEditorService.PluginState> plugins(ConfigEditorService service, Player player) {
        return service.visible(player).stream().filter(s -> !service.features(s).isEmpty()).toList();
    }

    static void overview(ConfigEditorService service, Player player, int offset) {
        if (plugins(service, player).isEmpty()) { lang().send(player, "features.none"); return; }
        open(service, player, null, offset);
    }

    static void plugin(ConfigEditorService service, Player player, ConfigEditorService.PluginState state, int offset) {
        if (service.features(state).isEmpty()) { lang().send(player, "features.none"); return; }
        open(service, player, state, offset);
    }

    /** {@code only} null = every plugin the player may configure, else that plugin's page (with Back). */
    private static void open(ConfigEditorService service, Player player, ConfigEditorService.PluginState only, int offset) {
        Plugin core = service.corePlugin();
        Metrics m = Metrics.of(Screens.of(core).profile(player));
        Model[] model = { model(service, player, only) };
        int[] at = { clamp(offset, model[0], m) };
        String key = only == null ? "vystorm_core:features" : "vystorm_core:features-plugin";
        ScreenSpec spec = ScreenSpec.builder(key, layout(player, only, model[0], m, at[0], null))
                .backdrop(ScreenSpec.Backdrop.BLACK)
                .onInput((session, input) -> {
                    switch (input) {
                        case ScreenInput.Scroll scroll -> {
                            int next = clamp(at[0] + scroll.delta() * SCROLL_ROWS, model[0], m);
                            if (next != at[0]) { at[0] = next; session.setLayout(layout(player, only, model[0], m, next, null)); }
                        }
                        case ScreenInput.Hover hover -> {
                            int index = row(hover.widgetId(), model[0]);
                            ConfigEditorService.FeatureView feature = index < 0 ? null : model[0].features().get(index);
                            session.setText(FOOTER, feature == null ? hint(player, model[0], m, at[0]) : describe(player, feature));
                        }
                        case ScreenInput.Click click when "close".equals(click.widgetId()) -> session.close();
                        // only reached without a parent screen (/vadmin features PLUGIN): Core handles Back otherwise
                        case ScreenInput.Click click when BACK.equals(click.widgetId()) -> { session.close(); overview(service, player, 0); }
                        case ScreenInput.Click click -> {
                            int index = row(click.widgetId(), model[0]);
                            if (index < 0 || model[0].features().get(index) == null) return;
                            ConfigEditorService.PluginState state = model[0].plugins().get(index);
                            if (!state.owner.isEnabled()) { session.close(); return; }
                            ConfigEditorService.FeatureView feature = model[0].features().get(index);
                            String problem = service.toggleFeature(player, state, feature);
                            model[0] = model(service, player, only);
                            at[0] = clamp(at[0], model[0], m);
                            Component note = problem != null ? Component.text(problem, NamedTextColor.RED)
                                    : describe(player, service.features(state).stream().filter(f -> f.path().equals(feature.path())).findFirst().orElse(feature));
                            if (session.isOpen()) session.setLayout(layout(player, only, model[0], m, at[0], note));
                        }
                        default -> { }
                    }
                })
                .fallback(only == null ? service::showOverview : p -> service.open(p, only.owner.getName()))
                .build();
        Screens.of(core).open(player, spec, only == null ? "vystorm_core:features" : "vystorm_core:features:" + only.owner.getName(),
                p -> open(service, p, only, 0));
    }

    /** Heading per plugin ("on / total"), then its features with ON/OFF and description. Row ids: {@code r<index>}. */
    static Model model(ConfigEditorService service, Player player, ConfigEditorService.PluginState only) {
        List<ScreenListLayout.Row> rows = new ArrayList<>();
        List<ConfigEditorService.PluginState> owners = new ArrayList<>();
        List<ConfigEditorService.FeatureView> features = new ArrayList<>();
        for (ConfigEditorService.PluginState state : only != null ? List.of(only) : plugins(service, player)) {
            List<ConfigEditorService.FeatureView> list = service.features(state);
            long on = list.stream().filter(ConfigEditorService.FeatureView::on).count();
            rows.add(ScreenListLayout.Row.heading("r" + rows.size(), Component.text(displayName(state.owner)),
                    lang().component(player, "features.count", "on", on, "total", list.size()), false));
            owners.add(null);
            features.add(null);
            for (ConfigEditorService.FeatureView feature : list) {
                rows.add(ScreenListLayout.Row.entry("r" + rows.size(), null, Component.text(Localization.get().resolve(player, feature.label())),
                        lang().component(player, feature.on() ? "features.state-on" : "features.state-off"), description(player, feature), true));
                owners.add(state);
                features.add(feature);
            }
        }
        return new Model(List.copyOf(rows), java.util.Collections.unmodifiableList(owners), java.util.Collections.unmodifiableList(features));
    }

    /** The description line of a feature, with the restart note when it only applies after a restart. */
    static Component description(Player player, ConfigEditorService.FeatureView feature) {
        String text = Localization.get().resolve(player, feature.description());
        Component out = Component.text(text == null ? "" : text);
        if (feature.activation() == SettingPolicy.Activation.RESTART)
            out = (text == null || text.isBlank() ? Component.empty() : out.append(Component.text(" · "))).append(lang().component(player, "features.restart"));
        return out;
    }

    static ScreenLayout layout(Player player, ConfigEditorService.PluginState only, Model model, Metrics m, int offset, Component footer) {
        double s = m.scale(), w = m.width(), button = 0.22 * s, gap = 0.02 * s;
        List<ScreenWidget> widgets = new ArrayList<>();
        int buttons = only == null ? 1 : 2;
        Component title = only == null ? lang().component(player, "features.title")
                : lang().component(player, "features.title-plugin", "plugin", displayName(only.owner));
        widgets.add(ScreenWidget.label("title", MARGIN, 0.03 * s, w - 2 * MARGIN - buttons * (button + gap), 0.08 * s, title).withAlign(ScreenWidget.Align.START));
        if (only != null) widgets.add(ScreenWidget.button(BACK, w - MARGIN - 2 * button - gap, 0.03 * s, button, 0.07 * s, lang().component(player, "menu.back")));
        widgets.add(ScreenWidget.button("close", w - MARGIN - button, 0.03 * s, button, 0.07 * s, lang().component(player, "menu.screen.close")));
        widgets.addAll(rows(model, m, offset));
        widgets.add(ScreenWidget.label(FOOTER, MARGIN, m.height() - m.footer() + 0.02 * s, w - 2 * MARGIN, 0.04 * s,
                footer != null ? footer : hint(player, model, m, offset)));
        return new ScreenLayout(w, m.height(), widgets);
    }

    /** The visible rows from {@code offset}, below the title, with a scroll bar when not all fit. */
    static List<ScreenWidget> rows(Model model, Metrics m, int offset) {
        ScreenListLayout.Sizes z = m.sizes();
        double lw = m.listWidth() - (needsBar(model, m) ? 0.03 * m.scale() : 0);
        ScreenListLayout.Window window = ScreenListLayout.window(model.rows(), offset, lw, m.listHeight(), z);
        List<ScreenWidget> out = new ArrayList<>();
        double v = m.top();
        for (int i = window.from(); i < window.to(); i++) {
            ScreenListLayout.Placed placed = ScreenListLayout.place(model.rows().get(i), MARGIN, v, lw, z);
            out.addAll(placed.widgets());
            v += placed.height();
        }
        if (needsBar(model, m)) {
            int max = Math.max(1, ScreenListLayout.maxOffset(model.rows(), lw, m.listHeight(), z));
            double track = m.listHeight(), thumb = Math.max(0.04 * m.scale(), track * (window.to() - window.from()) / Math.max(1, model.rows().size()));
            double x = m.width() - MARGIN - 0.008 * m.scale();
            out.add(ScreenWidget.fill("bar", x, m.top(), 0.008 * m.scale(), track, 0x60343B47));
            out.add(ScreenWidget.fill("thumb", x, m.top() + (track - thumb) * Math.min(1, (double) window.from() / max), 0.008 * m.scale(), thumb, 0xD0E3B04B));
        }
        return out;
    }

    private static boolean needsBar(Model model, Metrics m) {
        return ScreenListLayout.maxOffset(model.rows(), m.listWidth(), m.listHeight(), m.sizes()) > 0;
    }

    static int clamp(int offset, Model model, Metrics m) {
        double lw = m.listWidth() - (needsBar(model, m) ? 0.03 * m.scale() : 0);
        return Math.max(0, Math.min(ScreenListLayout.maxOffset(model.rows(), lw, m.listHeight(), m.sizes()), offset));
    }

    private static Component hint(Player player, Model model, Metrics m, int offset) {
        double lw = m.listWidth() - (needsBar(model, m) ? 0.03 * m.scale() : 0);
        int[] pos = ScreenListLayout.position(model.rows(), ScreenListLayout.window(model.rows(), offset, lw, m.listHeight(), m.sizes()));
        return lang().component(player, "features.hint-list", "position",
                lang().plain(player, "screen.list.position", "from", pos[0], "to", pos[1], "total", pos[2]));
    }

    /** Footer for one feature: name, state, when a change applies and the whole description. */
    static Component describe(Player player, ConfigEditorService.FeatureView feature) {
        String description = Localization.get().resolve(player, feature.description());
        // The footer is one line; longer texts would be shrunk until nobody can read them (the row shows two lines).
        if (description != null && description.length() > MAX_DESCRIPTION) description = shorten(description, MAX_DESCRIPTION);
        Component text = Component.text(Localization.get().resolve(player, feature.label()) + ": ")
                .append(lang().component(player, feature.on() ? "features.state-on" : "features.state-off"));
        if (feature.activation() == SettingPolicy.Activation.RESTART) text = text.append(Component.text(" · ")).append(lang().component(player, "features.restart"));
        return description == null || description.isBlank() ? text : text.append(Component.text(" – " + description, NamedTextColor.GRAY));
    }

    /** {@code text} in at most {@code max} characters, ending after a whole word with "…" (0.66.1; text without spaces is cut by characters). */
    static String shorten(String text, int max) {
        if (text.length() <= max) return text;
        String head = text.substring(0, max - 1);
        int space = head.lastIndexOf(' ');
        if (space > max / 2) head = head.substring(0, space);
        return head.stripTrailing() + "…";
    }

    /**
     * The name of a plugin as people write it (0.66.1): Core's {@code vystorm_core} is "Vystorm Core"; other names with
     * underscores get spaces and capitals the same way; names such as {@code VystormChat} stay as they are.
     */
    static String displayName(org.bukkit.plugin.Plugin plugin) {
        String name = plugin.getName();
        if (name == null || name.indexOf('_') < 0) return name;
        StringBuilder out = new StringBuilder();
        for (String part : name.split("_+")) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.isEmpty() ? name : out.toString();
    }

    /** Index of a row widget id ({@code r12}) in the model, or -1. */
    static int row(String widgetId, Model model) {
        return index(widgetId, 'r', model.rows().size());
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
