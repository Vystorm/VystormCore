package Vystorm.vystorm_core.lang.internal;

import Vystorm.vystorm_core.lang.CoreLanguage;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.lang.PlayerLanguageChangeEvent;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /language} as a panel (since 0.58.0, Vystorm Screens look): current language and choice on top, below one
 * list row per language with its flag, own name, code and coverage; "Automatic" first. Choosing a row stores the
 * language like {@code /language <code>} and redraws the panel in the new language. Without panels the chest menu
 * {@link LanguageMenu} stays. Server thread. Core-internal.
 */
public final class LanguagePanel {
    public static final String NAME = "language";

    private LanguagePanel() {}

    public static void register(Plugin core) {
        Lang lang = Localization.get().core();
        Panels.of(core).register(NAME, PanelSpec.builder(lang.ref("language-select.menu-title"))
                .render(LanguagePanel::render)
                .fallback(LanguageMenu::open)
                .build());
    }

    /** Opens the panel (screen, client mod or dialog), else the chest menu; false when neither is available. */
    public static boolean open(Player player) {
        try {
            var core = Vystorm.vystorm_core.main.getPlugin();
            if (core != null && Panels.of(core).open(player, NAME) != Vystorm.vystorm_core.panel.PanelOpenResult.NONE) return true;
        } catch (RuntimeException | LinkageError failure) {
            Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.high("[Localization] Language panel not opened: " + failure);
        }
        return LanguageMenu.open(player);
    }

    static Panel render(PanelContext ctx) {
        Player player = ctx.player();
        Localization service = Localization.get();
        Lang lang = service.core();
        List<LanguageChoices.Option> options = LanguageChoices.of(service).options();
        String choice = CoreLanguage.choice(player);
        String current = service.language(player);
        String client = Localization.clientLocale(player);
        String auto = lang.plain(player, "settings.language.auto");

        Ui.ListView list = Ui.list("languages").selected(choice.equals(Localization.AUTO) ? "auto" : choice)
                .item(Ui.listItem("auto", lang.plain(player, "language-select.auto-name"))
                        .icon(Ui.item(Material.COMPASS))
                        .subtitle(client.isEmpty() ? "?" : client + " → " + CoreLanguage.nativeName(lang.resolveLanguage(client))));
        for (LanguageChoices.Option option : options) {
            Ui.ListItem item = Ui.listItem(option.code(), option.name())
                    .icon(Ui.item(LanguageFlags.item(option.code())))
                    .subtitle(lang.plain(player, "language-select.code", "code", option.code()))
                    .right(lang.plain(player, "language-select.coverage", "coverage", option.percent()));
            if (option.code().equals(current) && !option.code().equals(choice)) item.badge(lang.plain(player, "language-select.in-use"));
            list.item(item);
        }
        list.onSelect((a, key) -> {
            LanguageCommand.apply(a.player(), a.player(), key.equals("auto") ? Localization.AUTO : key, PlayerLanguageChangeEvent.Cause.COMMAND);
            a.refresh();
            return Result.ok();
        });

        return Panel.page(lang.plain(player, "language-select.menu-title")).icon(Ui.item(Material.BOOK)).size(Panel.Size.MEDIUM)
                .children(Ui.kv().id("current")
                                .row(lang.plain(player, "language-select.current"), CoreLanguage.nativeName(current) + " (" + current + ")")
                                .row(lang.plain(player, "language-select.choice"), choice.equals(Localization.AUTO) ? auto : CoreLanguage.nativeName(choice)),
                        list);
    }
}
