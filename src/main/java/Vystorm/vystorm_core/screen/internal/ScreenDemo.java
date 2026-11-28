package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /screen} (since 0.57.0): {@code close} for everyone, {@code demo [pinned|floating|map]} for admins. The demo is the
 * calibration prototype: a title, three buttons, one text input via dialog and a counter. {@code demo map} (0.59.0)
 * opens {@link ScreenMapDemo}.
 */
public final class ScreenDemo {
    private final Plugin core;
    private final Lang lang;
    private final Screens screens;

    private ScreenDemo(Plugin core) {
        this.core = core;
        this.lang = Localization.get().core();
        this.screens = Screens.of(core);
    }

    public static void register(Plugin core) {
        ScreenDemo demo = new ScreenDemo(core);
        CoreCommands.register(core, demo.lang, CommandSpec.command("screen")
                .sub("close", s -> s.playerOnly().executes(c -> demo.close(c.player())))
                .sub("settings", s -> s.playerOnly().arg(Arg.greedy("resolution").optional())
                        .executes(c -> demo.settings(c.player(), c.get("resolution"))))
                .sub("classic", s -> s.playerOnly().arg(Arg.word("state").optional())
                        .executes(c -> demo.classic(c.player(), c.get("state"))))
                .sub("demo", s -> s.playerOnly().permission("vystorm_core.admin")
                        .arg(Arg.word("mode").optional()).arg(Arg.word("backdrop").optional())
                        .executes(c -> demo.open(c.player(), c.get("mode"), c.get("backdrop"))))
                .build());
    }

    /** {@code /screen settings [WIDTHxHEIGHT]}: with a resolution it is saved directly, without one the settings screen opens. */
    void settings(Player player, String resolution) {
        if (resolution == null || resolution.isBlank()) { new ScreenSettings(core).open(player, null); return; }
        Double aspect = Vystorm.vystorm_core.screen.ScreenProfile.parseResolution(resolution);
        if (aspect == null) { lang.send(player, "screen.settings.custom-bad"); return; }
        var next = screens.profile(player).withAspect(aspect);
        screens.saveProfile(player, next);
        lang.send(player, "screen.settings.saved", "aspect", ScreenSettings.aspectText(next.aspect()));
    }

    void close(Player player) {
        if (ScreenRuntime.get().session(player).isEmpty()) lang.send(player, "screen.none");
        else ScreenRuntime.get().close(player, ScreenInput.Reason.COMMAND);
    }

    /** {@code /screen classic [on|off]}: FLOATING screens with the crosshair as cursor, for players whose shader pack replaces Core's pointer shader. */
    void classic(Player player, String state) {
        boolean on = state == null ? !Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs.bool(core, player, "screen.classic", false)
                : state.equalsIgnoreCase("on") || state.equalsIgnoreCase("an") || state.equalsIgnoreCase("true");
        Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs.setBool(core, player, "screen.classic", on);
        lang.send(player, on ? "screen.classic.on" : "screen.classic.off");
    }

    /** The prototype layout: panel 0.9 x 0.55 with a title, three buttons, one input row and a counter label. */
    static ScreenLayout layout(Lang lang, Player viewer) {
        return new ScreenLayout(0.9, 0.55, List.of(
                ScreenWidget.label("title", 0.05, 0.03, 0.8, 0.08, lang.component(viewer, "screen.demo.title")),
                ScreenWidget.button("hello", 0.05, 0.16, 0.25, 0.08, lang.component(viewer, "screen.demo.hello")),
                ScreenWidget.button("count", 0.325, 0.16, 0.25, 0.08, lang.component(viewer, "screen.demo.count")),
                ScreenWidget.button("close", 0.6, 0.16, 0.25, 0.08, lang.component(viewer, "screen.demo.close")),
                ScreenWidget.input("name", 0.05, 0.3, 0.8, 0.08, lang.component(viewer, "screen.demo.name", "name", "—")),
                ScreenWidget.label("counter", 0.05, 0.44, 0.8, 0.06, lang.component(viewer, "screen.demo.counter", "count", 0))));
    }

    void open(Player player, String mode, String fill) {
        if ("map".equalsIgnoreCase(mode)) { new ScreenMapDemo(lang, screens).open(player); return; }
        // screen canvas: "/screen demo paint holes" is the 26.3 map experiment (C0)
        if ("paint".equalsIgnoreCase(mode)) { new ScreenPaintDemo(lang, screens).open(player, "holes".equalsIgnoreCase(fill)); return; }
        if ("gif".equalsIgnoreCase(mode)) { new ScreenGifDemo(core, lang, screens).open(player, fill); return; }
        if ("hud".equalsIgnoreCase(mode)) {
            var huds = Vystorm.vystorm_core.screen.Huds.of(core);
            huds.show(player, Vystorm.vystorm_core.screen.HudCard.of(org.bukkit.Material.IRON_PICKAXE, "Mining", "Lv 42", 0.63, "340 / 540 XP").withFooter("+12 XP"), 0);
            org.bukkit.Bukkit.getScheduler().runTaskLater(core, () -> huds.show(player, Vystorm.vystorm_core.screen.HudCard.of(org.bukkit.Material.IRON_PICKAXE, "Level up! Mining", "Lv 43", 0.0, "0 / 560 XP").withFooter("+5 % ore drops").highlighted(), 100), 60L);
            return;
        }
        ScreenSpec.Backdrop backdrop = ScreenSpec.Backdrop.BLACK;
        if (fill != null) try { backdrop = ScreenSpec.Backdrop.valueOf(fill.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { }
        ScreenSpec.Mode preferred = switch (mode == null ? "" : mode.toLowerCase(Locale.ROOT)) {
            case "pinned" -> ScreenSpec.Mode.PINNED;
            case "floating" -> ScreenSpec.Mode.FLOATING;
            default -> ScreenSpec.Mode.AUTO;
        };
        int[] count = { 0 };
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:demo", layout(lang, player))
                .mode(preferred)
                .backdrop(backdrop)
                .onInput((session, input) -> handle(session, input, count))
                .fallback(p -> { })
                .build();
        ScreenOpenResult result = screens.open(player, spec);
        if (result.opened()) lang.send(player, "screen.demo.opened", "mode", result.mode().name().toLowerCase(Locale.ROOT));
        else if (ScreenRuntime.silentRefusal(result.refusedKey())) lang.send(player, result.refusedKey());   // 0.71.1: the others the runtime told
    }

    private void handle(ScreenSession session, ScreenInput input, int[] count) {
        Player player = session.player();
        if (!(input instanceof ScreenInput.Click click) || click.widgetId() == null) return;
        switch (click.widgetId()) {
            case "hello" -> lang.send(player, "screen.demo.hello-message");
            case "count" -> session.setText("counter", lang.component(player, "screen.demo.counter", "count", ++count[0]));
            case "close" -> session.close();
            case "name" -> askName(session);
            default -> { }
        }
    }

    private void askName(ScreenSession session) {
        Player player = session.player();
        DialogRequest request = new DialogRequest(lang.component(player, "screen.demo.name-title"), List.of(),
                List.of(DialogInput.text("name", lang.component(player, "screen.demo.name-label")).maxLength(32).build()),
                List.of(new DialogButton(lang.component(player, "screen.demo.name-ok"), (p, response) -> {
                    String name = response.getText("name");
                    if (session.isOpen()) session.setText("name", lang.component(p, "screen.demo.name", "name", name == null || name.isBlank() ? "—" : name));
                })),
                p -> session.isOpen(), () -> { });
        Vystorm.vystorm_core.main.getApi().dialogs().open(core, player, request);
    }
}
