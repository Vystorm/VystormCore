package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
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
import java.util.Locale;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Display settings screen ({@code /screen settings}, "Display" in the menu screen; since 0.57.0): aspect ratio presets
 * or the player's own resolution, field of view and UI size. Every change is saved at once; screens opened afterwards
 * use the new values.
 */
public final class ScreenSettings {
    /** Aspect presets: label, width, height. */
    static final String[][] PRESETS = { { "4:3", "4", "3" }, { "16:10", "16", "10" }, { "16:9", "16", "9" }, { "21:9", "21", "9" }, { "32:9", "32", "9" } };
    static final double WIDTH = 1.25, HEIGHT = 0.8;
    static final int FOV_STEP = 5;
    static final double SCALE_STEP = 0.1;

    private final Plugin core;
    private final Lang lang;

    public ScreenSettings(Plugin core) {
        this.core = core;
        this.lang = Localization.get().core();
    }

    /** Opens the settings; {@code done} runs after "Done" (null: just close). */
    public void open(Player player, Consumer<Player> done) {
        Screens screens = Screens.of(core);
        ScreenProfile[] profile = { screens.profile(player) };
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:screen-settings", layout(player, profile[0]))
                .backdrop(ScreenSpec.Backdrop.BLACK)
                .onInput((session, input) -> handle(session, input, profile, done))
                .fallback(p -> lang.send(p, "screen.settings.unavailable"))
                .build();
        screens.open(player, spec);
    }

    ScreenLayout layout(Player player, ScreenProfile profile) {
        double s = 1.0, m = 0.05, row = 0.1, w = WIDTH;
        List<ScreenWidget> widgets = new ArrayList<>();
        widgets.add(ScreenWidget.label("title", m, 0.03, w - 2 * m, 0.08, lang.component(player, "screen.settings.title")));
        widgets.add(ScreenWidget.label("current", m, 0.13, w - 2 * m, 0.045, lang.component(player, "screen.settings.current",
                "aspect", aspectText(profile.aspect()), "fov", profile.fov(), "scale", Math.round(profile.scale() * 100))));
        // aspect presets + own resolution
        widgets.add(ScreenWidget.label("aspect-label", m, 0.22, 0.3, 0.05, lang.component(player, "screen.settings.aspect")));
        double bw = (w - 2 * m - 0.32) / (PRESETS.length + 1);
        for (int i = 0; i < PRESETS.length; i++) {
            String id = "aspect:" + i;
            ScreenWidget b = ScreenWidget.button(id, m + 0.32 + i * bw, 0.21, bw - 0.01, 0.07, PRESETS[i][0]);
            widgets.add(b);
        }
        widgets.add(ScreenWidget.button("custom", m + 0.32 + PRESETS.length * bw, 0.21, bw - 0.01, 0.07, lang.component(player, "screen.settings.custom")));
        // field of view
        double y = 0.21 + row + 0.02;
        widgets.add(ScreenWidget.label("fov-label", m, y + 0.01, 0.3, 0.05, lang.component(player, "screen.settings.fov-label")));
        widgets.add(ScreenWidget.button("fov-", m + 0.32, y, 0.1, 0.07, "−"));
        widgets.add(ScreenWidget.label("fov", m + 0.44, y + 0.01, 0.3, 0.05, lang.component(player, "screen.settings.fov", "fov", profile.fov())));
        widgets.add(ScreenWidget.button("fov+", m + 0.76, y, 0.1, 0.07, "+"));
        // UI size
        y += row;
        widgets.add(ScreenWidget.label("scale-label", m, y + 0.01, 0.3, 0.05, lang.component(player, "screen.settings.scale-label")));
        widgets.add(ScreenWidget.button("scale-", m + 0.32, y, 0.1, 0.07, "−"));
        widgets.add(ScreenWidget.label("scale", m + 0.44, y + 0.01, 0.3, 0.05, lang.component(player, "screen.settings.scale", "scale", Math.round(profile.scale() * 100))));
        widgets.add(ScreenWidget.button("scale+", m + 0.76, y, 0.1, 0.07, "+"));
        // hint and done
        widgets.add(ScreenWidget.label("hint", m, HEIGHT - 0.21, w - 2 * m, 0.045, lang.component(player, "screen.settings.hint")));
        widgets.add(ScreenWidget.button("done", w / 2 - 0.15, HEIGHT - 0.12, 0.3, 0.08, lang.component(player, "screen.settings.done")));
        return new ScreenLayout(w * s, HEIGHT * s, widgets);
    }

    static String aspectText(double aspect) {
        for (String[] preset : PRESETS) if (Math.abs(aspect - Double.parseDouble(preset[1]) / Double.parseDouble(preset[2])) < 0.01) return preset[0];
        return String.format(Locale.ROOT, "%.2f:1", aspect);
    }

    /** New profile after clicking {@code widgetId}, or null when the click changes nothing. */
    static ScreenProfile apply(ScreenProfile profile, String widgetId) {
        if (widgetId == null) return null;
        if (widgetId.startsWith("aspect:")) {
            String[] preset = PRESETS[Integer.parseInt(widgetId.substring(7))];
            return profile.withAspect(Double.parseDouble(preset[1]) / Double.parseDouble(preset[2]));
        }
        return switch (widgetId) {
            case "fov-" -> profile.withFov(profile.fov() - FOV_STEP);
            case "fov+" -> profile.withFov(profile.fov() + FOV_STEP);
            case "scale-" -> profile.withScale(Math.round((profile.scale() - SCALE_STEP) * 10) / 10.0);
            case "scale+" -> profile.withScale(Math.round((profile.scale() + SCALE_STEP) * 10) / 10.0);
            default -> null;
        };
    }

    private void handle(ScreenSession session, ScreenInput input, ScreenProfile[] profile, Consumer<Player> done) {
        if (!(input instanceof ScreenInput.Click click) || click.widgetId() == null) return;
        Player player = session.player();
        switch (click.widgetId()) {
            case "done" -> {
                session.close();
                if (done != null) done.accept(player);
            }
            case "custom" -> askResolution(session, profile);
            default -> {
                ScreenProfile next = apply(profile[0], click.widgetId());
                if (next == null || next.equals(profile[0])) return;
                update(session, profile, next);
            }
        }
    }

    private void update(ScreenSession session, ScreenProfile[] profile, ScreenProfile next) {
        profile[0] = next;
        Screens.of(core).saveProfile(session.player(), next);
        session.setLayout(layout(session.player(), next));
    }

    private void askResolution(ScreenSession session, ScreenProfile[] profile) {
        Player player = session.player();
        DialogRequest request = new DialogRequest(lang.component(player, "screen.settings.custom-title"),
                List.of(),
                List.of(DialogInput.text("resolution", lang.component(player, "screen.settings.custom-label")).maxLength(16).build()),
                List.of(new DialogButton(lang.component(player, "screen.settings.custom-ok"), (p, response) -> {
                    Double aspect = ScreenProfile.parseResolution(response.getText("resolution"));
                    if (aspect == null) { lang.send(p, "screen.settings.custom-bad"); return; }
                    if (session.isOpen()) update(session, profile, profile[0].withAspect(aspect));
                    else Screens.of(core).saveProfile(p, profile[0].withAspect(aspect));
                })),
                p -> true, () -> { });
        Vystorm.vystorm_core.main.getApi().dialogs().open(core, player, request);
    }

    /** Keeps the profile's text for tests: "16:9 · 70° · 100 %". */
    static String summary(ScreenProfile profile) {
        return aspectText(profile.aspect()) + " · " + profile.fov() + "° · " + Math.round(profile.scale() * 100) + " %";
    }

    static Component plain(String text) { return Component.text(text); }
}
