package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingScope;
import Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Core-Einstellungen als natives Panel (Spec §5.1, §6 Schritt 1), automatisch aus **demselben** {@code SectionView}-Modell
 * wie Dialog und Web-Seite. Speichern nur über {@link SettingsRegistry#save} (Quelle {@code "panel"}). Kein Plugin muss
 * etwas tun. Core-intern. @since 0.25.0
 */
public final class SettingsPanels {
    public static final String NAME = "settings";

    private SettingsPanels() {}

    private static Vystorm.vystorm_core.lang.Lang lang() { return Localization.get().core(); }

    public static void register(Plugin core, SettingsRegistry registry) {
        Panels panels = Panels.of(core);
        panels.register(NAME, PanelSpec.builder(lang().ref("settings.title"))
                .render(ctx -> render(ctx, registry))
                .web("settings", "")
                .fallback(registry::openOverview)
                .build());
        registry.onChange((player, key) -> {
            if (player == null) panels.refreshAll(NAME);
            else panels.refresh(player, NAME);
        });
    }

    static Panel render(PanelContext ctx, SettingsRegistry registry) {
        Player p = ctx.player();
        String arg = ctx.arg() == null ? "" : ctx.arg();
        String filter = ctx.session().get("filter") instanceof String f ? f.toLowerCase(Locale.ROOT) : "";
        List<SettingsRegistry.SectionView> all = registry.view(p);
        List<Ui.Element<?>> personal = new ArrayList<>(), server = new ArrayList<>();
        boolean single = arg.contains(":");
        boolean configs = false;
        for (SettingsRegistry.SectionView v : all) {
            if (single && !v.key().equalsIgnoreCase(arg)) continue;
            // Config-editor sections (thousands of values for all plugins) would exceed the panel document limits;
            // the list shows one entry that opens the config overview instead (0.27.1). A single section still renders.
            if (!single && !registry.group(v.key()).isEmpty()) { configs = true; continue; }
            if (!filter.isEmpty() && !matches(p, v, filter)) continue;
            (v.section().scope() == SettingScope.SERVER ? server : personal).add(section(p, v, registry));
        }
        Panel page = Panel.page(lang().ref("settings.title")).size(Panel.Size.LARGE);
        page.child(Ui.input("filter", lang().ref("settings.panel.filter")).placeholder(lang().ref("settings.panel.filter-hint"))
                .value(filter).maxLength(40).search((a, q) -> {
                    a.session().put("filter", q);
                    a.refresh();
                    return Result.ok();
                }));
        if (single) page.child(Ui.button("all").label(lang().ref("settings.panel.all")).ghost().navigate(NAME, ""));
        if (all.stream().anyMatch(v -> v.section().scope() == SettingScope.SERVER)) page.child(Ui.button("draft")
                .label(lang().ref("settings.draft.title")).onClick(a -> {
                    a.closeAndThen(viewer -> Vystorm.vystorm_core._uiMethods.settings.SettingsDraftDialog.open(a.player(), registry));
                    return Result.ok();
                }));
        if (configs) server.addFirst(Ui.button("configs").label(lang().ref("config.entry")).onClick(a -> {
            a.closeAndThen(viewer -> Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.openOverview(a.player()));
            return Result.ok();
        }));
        boolean hasServer = all.stream().anyMatch(v -> v.section().scope() == SettingScope.SERVER);
        if (personal.isEmpty() && server.isEmpty()) page.child(Ui.text(lang().ref(filter.isEmpty() ? "settings.empty" : "settings.panel.no-match")).style(Ui.TextStyle.MUTED));
        else if (hasServer && !single) page.child(Ui.tabs("scope")
                .tab("personal", lang().ref("settings.personal"), personal.toArray(Ui.Element[]::new))
                .tab("server", lang().ref("settings.server"), server.toArray(Ui.Element[]::new))
                .select(arg.equals("server") ? "server" : "personal"));
        else {
            personal.forEach(page::child);
            server.forEach(page::child);
        }
        return page;
    }

    private static boolean matches(Player p, SettingsRegistry.SectionView v, String filter) {
        var i18n = Localization.get();
        if (i18n.resolve(p, v.section().title()).toLowerCase(Locale.ROOT).contains(filter)) return true;
        if (i18n.resolve(p, v.section().description()).toLowerCase(Locale.ROOT).contains(filter)) return true;
        for (SettingsRegistry.SettingView s : v.settings())
            if (i18n.resolve(p, s.setting().label()).toLowerCase(Locale.ROOT).contains(filter)) return true;
        return false;
    }

    private static Ui.Section section(Player p, SettingsRegistry.SectionView v, SettingsRegistry registry) {
        Ui.Section section = Ui.section(v.section().title());
        if (!v.section().description().isBlank()) section.child(Ui.text(v.section().description()).style(Ui.TextStyle.MUTED));
        for (SettingsRegistry.SettingView s : v.settings()) section.child(control(settingId(v.key(), s.setting().id()), v.key(), s, registry));
        return section;
    }

    /**
     * Stabile Element-ID aus Abschnitt und Einstellung ({@code s:<abschnitt>/<einstellung>}), nicht aus der Position: kommt
     * ein Abschnitt dazu oder fällt einer weg, zeigt eine ID nie auf eine andere Einstellung (Final Review Important 7).
     * Passt der Klartext nicht ins ID-Muster (Großbuchstaben, Leerzeichen, > 64 Zeichen), dann ein Hash daraus.
     */
    static String settingId(String sectionKey, String settingId) {
        String plain = "s:" + sectionKey + "/" + settingId;
        return Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds.element(plain) ? plain : "s:" + hash(sectionKey + "\u0000" + settingId);
    }

    static String hash(String text) {
        return Vystorm.vystorm_core.web.internal.clientproto.panel.Transfer.hex(
                Vystorm.vystorm_core.web.internal.clientproto.panel.Transfer.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 24);
    }

    private static Ui.Element<?> control(String id, String key, SettingsRegistry.SettingView view, SettingsRegistry registry) {
        Setting s = view.setting();
        if (!view.readable()) return Ui.text(lang().ref("settings.panel.read-failed")).style(Ui.TextStyle.ERROR).id(id);
        Object value = view.value();
        return switch (s.kind()) {
            case TOGGLE -> {
                Ui.Toggle t = Ui.toggle(id, s.label()).value(Boolean.TRUE.equals(value)).onChange((a, on) -> save(registry, a.player(), key, s, on));
                if (!s.description().isBlank()) t.description(s.description());
                yield t;
            }
            case NUMBER -> {
                double v = value instanceof Number n ? SettingsRegistry.coerce(s, n.doubleValue()) instanceof Double d ? d : s.min() : s.min();
                int decimals = s.step() >= 1 ? 0 : Math.min(6, (int) Math.ceil(-Math.log10(s.step())));
                // The shared panel schema caps numeric widgets at 100,000 steps. Keep large settings
                // editable without changing their step size or breaking older client protocol readers.
                if (s.min() < -1e12 || s.max() > 1e12 || s.step() < 1e-9 || s.step() > 1e12
                        || (s.max() - s.min()) / s.step() > 100_000) {
                    Ui.Input input = Ui.input(id, s.label()).maxLength(64).submitOnEnter()
                            .value(java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString())
                            .onChange((a, text) -> saveNumberText(registry, a.player(), key, s, text));
                    if (!s.description().isBlank()) input.tooltip(s.description());
                    yield input;
                }
                if ((s.max() - s.min()) / s.step() <= 200) {
                    Ui.Slider slider = Ui.slider(id, s.label()).range(s.min(), s.max(), s.step()).value(v).decimals(decimals)
                            .onChange((a, n) -> save(registry, a.player(), key, s, n));
                    if (!s.description().isBlank()) slider.tooltip(s.description());
                    yield slider;
                }
                Ui.NumberField number = Ui.number(id, s.label()).range(s.min(), s.max(), s.step()).value(v).decimals(decimals)
                        .onChange((a, n) -> save(registry, a.player(), key, s, n));
                if (!s.description().isBlank()) number.tooltip(s.description());
                yield number;
            }
            case CHOICE -> {
                Ui.Select select = Ui.select(id, s.label()).onChange((a, o) -> save(registry, a.player(), key, s, o));
                for (Setting.Option o : s.options()) select.option(o.id(), o.label());
                String current = value == null ? null : value.toString();
                if (current != null && s.options().stream().anyMatch(o -> o.id().equals(current))) select.value(current);
                if (s.options().size() <= 4) select.segmented();
                else if (s.options().size() >= 12) select.searchable();
                if (!s.description().isBlank()) select.tooltip(s.description());
                yield select;
            }
            case TEXT -> {
                String text = value == null ? "" : value.toString();
                Ui.Input input = Ui.input(id, s.label()).maxLength(s.maxLength()).value(text.length() > s.maxLength() ? text.substring(0, s.maxLength()) : text)
                        .onChange((a, t) -> save(registry, a.player(), key, s, t));
                if (!s.description().isBlank()) input.tooltip(s.description());
                yield input;
            }
        };
    }

    static Result saveNumberText(SettingsRegistry registry, Player player, String key, Setting setting, String text) {
        try {
            double value = Double.parseDouble(text.strip().replace(',', '.'));
            if (Double.isFinite(value) && value >= setting.min() && value <= setting.max())
                return save(registry, player, key, setting, value);
        } catch (NumberFormatException ignored) {
            // Let the existing settings validation produce the same localized invalid-value result.
        }
        return save(registry, player, key, setting, text);
    }

    /** Derselbe Speicherweg wie Dialog und Web; Setter-Fehlertext wird zur Meldung am Element. */
    static Result save(SettingsRegistry registry, Player player, String key, Setting s, Object value) {
        SettingsRegistry.SaveResult r = registry.save(player, key, Map.of(s.id(), value), "panel");
        if (!r.found()) return Result.error(lang().ref("common.settings-gone"));
        if (!r.problems().isEmpty()) {
            SettingsRegistry.Problem pr = r.problems().getFirst();
            String label = Localization.get().resolve(player, pr.label());
            return Result.error(switch (pr.kind()) {
                case INVALID -> lang().plain(player, "settings.invalid", "label", label);
                case REJECTED -> lang().plain(player, "settings.error", "label", label, "error", Localization.get().resolve(player, pr.error()));
                case FAILED -> lang().plain(player, "settings.save-failed", "label", label);
            });
        }
        if (r.applyFailed()) return Result.error(lang().plain(player, "settings.apply-failed"));
        return Result.ok();
    }
}
