package Vystorm.vystorm_core._uiMethods.tutorial;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import Vystorm.vystorm_core.screen.internal.MapPlayback;
import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tutorials as a Vystorm Screen (since 0.59.0): one topic per screen with its build GIF on map tiles on the left
 * ({@code gif:} in {@code tutorial.yml}, a file in the plugin jar), the wrapped text on the right, and buttons for
 * back, next and all tutorials. Without a GIF the text uses the full width. Returns false when no screen can open, so
 * the guide falls back to its dialogs. Server thread only.
 */
final class TutorialScreen {
    static final double TEXT_H = 0.04, MARGIN = 0.04;
    private final Plugin owner;
    private final Plugin core;
    private final TutorialGuide guide;

    TutorialScreen(Plugin core, Plugin owner, TutorialGuide guide) {
        this.core = core;
        this.owner = owner;
        this.guide = guide;
    }

    private static Lang lang() { return Localization.get().core(); }

    /** Opens the topic at {@code position} of the visible topics; false = no screen (use the dialog). */
    boolean open(Player player, List<TutorialGuide.Page> topics, int position) {
        Screens screens = Screens.of(core);
        State state = new State(topics, position);
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:tutorial", layout(player, state))
                .onInput((session, input) -> { state.session = session; handle(player, state, input); })
                .fallback(p -> state.fallback = true)
                .build();
        if (!screens.open(player, spec).opened()) return false;
        screens.session(player).ifPresent(session -> { state.session = session; startGif(state); });
        return true;
    }

    private static final class State {
        final List<TutorialGuide.Page> topics;
        int position;
        ScreenSession session;
        BukkitTask playback;
        boolean fallback;
        State(List<TutorialGuide.Page> topics, int position) { this.topics = topics; this.position = position; }
        TutorialGuide.Page page() { return topics.get(position); }
    }

    ScreenLayout layout(Player player, State state) {
        ScreenProfile profile = Screens.of(core).profile(player);
        double s = profile.scale();
        double w = Math.max(1.2, Math.min(profile.viewWidth() * 0.9, 2.4 * s)), h = Math.max(0.8, profile.viewHeight() * 0.8);
        TutorialGuide.Page page = state.page();
        List<ScreenWidget> widgets = new ArrayList<>();
        double closeW = 0.2 * s, head = 0.1 * s, foot = 0.1 * s;
        widgets.add(ScreenWidget.label("title", MARGIN, 0.02 * s, w - 2 * MARGIN - closeW - 0.2 * s, 0.06 * s,
                Component.text(guide.title(player) + " · " + page.title()).color(TextColor.color(0xF2F2F2)).decorate(TextDecoration.BOLD))
                .withAlign(ScreenWidget.Align.START));
        widgets.add(ScreenWidget.label("count", w - MARGIN - closeW - 0.2 * s, 0.02 * s, 0.18 * s, 0.06 * s,
                Component.text((state.position + 1) + " / " + state.topics.size()).color(TextColor.color(0x9AA3B2))).withAlign(ScreenWidget.Align.END));
        widgets.add(ScreenWidget.button("close", w - MARGIN - closeW, 0.02 * s, closeW, 0.06 * s, lang().component(player, "menu.screen.close"))
                .withTextHeight(0.034 * s));
        widgets.add(ScreenWidget.fill("line", 0, head, w, 0.004 * s, 0xFF343B47));

        double bodyTop = head + 0.03 * s, bodyH = h - head - foot - 0.06 * s, textX = MARGIN, textW = w - 2 * MARGIN;
        if (page.gif() != null && !page.gif().isBlank()) {
            double gifW = (w - 3 * MARGIN) * 0.58, gifH = Math.min(bodyH, gifW * 254 / 384.0);
            widgets.add(ScreenWidget.fill("gif-box", MARGIN, bodyTop, gifW, gifH, 0xD0252A33));
            widgets.add(ScreenWidget.map("gif", MARGIN, bodyTop, gifW, gifH, new ScreenWidget.MapGrid(3, 2),
                    lang().component(player, "tutorial.gif-pinned-only")));
            textX = MARGIN * 2 + gifW;
            textW = w - textX - MARGIN;
        }
        double line = TEXT_H * s, y = bodyTop;
        int k = 0;
        for (String text : wrap(page.text(), textW, line)) {
            if (y + line > bodyTop + bodyH) break;
            widgets.add(ScreenWidget.label("t" + k++, textX, y, textW, line, Component.text(text).color(TextColor.color(0xF2F2F2)))
                    .withAlign(ScreenWidget.Align.START));
            y += line * 1.15;
        }

        double bw = 0.32 * s, bh = 0.07 * s, by = h - foot + 0.01 * s;
        widgets.add(ScreenWidget.fill("line2", 0, h - foot - 0.01 * s, w, 0.004 * s, 0xFF343B47));
        widgets.add(ScreenWidget.button("back", MARGIN, by, bw, bh, lang().component(player, "common.previous"))
                .withEnabled(state.position > 0).withTextHeight(0.036 * s));
        widgets.add(ScreenWidget.button("all", (w - bw * 1.4) / 2, by, bw * 1.4, bh, lang().component(player, "tutorial.all-tutorials"))
                .withTextHeight(0.036 * s).withFill(ScreenWidget.FILL_NONE).withHighlight(0xF03A4352));
        widgets.add(ScreenWidget.button("next", w - MARGIN - bw, by, bw, bh, lang().component(player, "common.next"))
                .withEnabled(state.position + 1 < state.topics.size()).withFill(0xF0E3B04B).withHighlight(0xF0F2C66A)
                .withTextHeight(0.036 * s).withText(lang().component(player, "common.next").color(TextColor.color(0x252A33))));
        return new ScreenLayout(w, h, widgets);
    }

    /** Word wrap by the default font's widths; keeps the text's own line breaks. */
    static List<String> wrap(String text, double width, double lineHeight) {
        double perPx = (1.0 / 40) * (lineHeight / 0.25 * 0.8);
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isBlank()) { out.add(""); continue; }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.strip().split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (ScreenRuntime.textWidth(candidate) * perPx > width && !current.isEmpty()) {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (!current.isEmpty()) out.add(current.toString());
        }
        return out;
    }

    private void handle(Player player, State state, ScreenInput input) {
        if (input instanceof ScreenInput.Closed) { if (state.playback != null) state.playback.cancel(); return; }
        if (!(input instanceof ScreenInput.Click click) || click.widgetId() == null) return;
        switch (click.widgetId()) {
            case "close" -> state.session.close();
            case "back" -> show(player, state, state.position - 1);
            case "next" -> show(player, state, state.position + 1);
            case "all" -> {
                state.session.close();
                guide.directory(player);
            }
            default -> { }
        }
    }

    private void show(Player player, State state, int position) {
        if (position < 0 || position >= state.topics.size()) return;
        state.position = position;
        if (state.playback != null) state.playback.cancel();
        state.session.setLayout(layout(player, state));
        startGif(state);
    }

    private void startGif(State state) {
        String gif = state.page().gif();
        if (gif == null || gif.isBlank() || state.session == null) return;
        int position = state.position;
        MapPlayback.load(core, owner.getName() + ":" + gif, () -> owner.getResource(gif), new ScreenWidget.MapGrid(3, 2), 200)
                .whenComplete((animation, failure) -> org.bukkit.Bukkit.getScheduler().runTask(core, () -> {
                    if (failure != null || state.position != position || !state.session.isOpen()) return;
                    state.playback = MapPlayback.play(core, state.session, "gif", animation.frames());
                }));
    }
}
