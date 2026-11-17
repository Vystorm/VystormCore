package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.autoGui.MenuEntryView;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * {@code /vmenu} als Raster aus Item-Knöpfen je Kategorie (Spec §5.1, §6 Schritt 2). Einträge, die selbst Panels sind,
 * öffnen nativ; alle anderen laufen wie bisher über ihren Opener (Dialog/Kiste). Ohne Mod unverändert der Dialog.
 * Core-intern. @since 0.25.0
 */
public final class MenuPanels {
    public static final String NAME = "menu";

    private MenuPanels() {}

    public static void register(Plugin core, GuiInteface gui) {
        var lang = Localization.get().core();
        Panels.of(core).register(NAME, PanelSpec.builder(lang.ref("menu.directory.title"))
                .render(ctx -> render(ctx, gui))
                .fallback(p -> gui.openDirectory(p, 0))
                .build());
    }

    static Panel render(PanelContext ctx, GuiInteface gui) {
        var lang = Localization.get().core();
        java.util.Set<String> used = new java.util.HashSet<>();
        Panel page = Panel.page(lang.ref("menu.directory.title")).size(Panel.Size.LARGE)
                .subtitle(lang.ref("menu.panel.subtitle")).icon(Ui.item(org.bukkit.Material.COMPASS));
        page.child(Ui.button("explorer").label(lang.ref("menu.explorer.title")).onClick(a -> {
            a.closeAndThen(viewer -> Vystorm.vystorm_core._uiMethods.menu.MenuExplorer.open(a.player(), gui));
            return Result.ok();
        }));
        List<MenuEntryView> root = gui.menuEntries(ctx.player(), "").stream().filter(e -> !isCategory(e.key())).toList();
        if (!root.isEmpty()) page.child(grid(lang.ref("menu.panel.general"), root, gui, used));
        for (MenuCategory category : MenuCategory.ordered()) {
            List<MenuEntryView> entries = gui.menuEntries(ctx.player(), category.group());
            if (!entries.isEmpty()) page.child(grid(lang.ref(category.langKey() + ".title"), entries, gui, used));
        }
        boolean any = !root.isEmpty();
        for (MenuCategory category : MenuCategory.ordered()) any |= !gui.menuEntries(ctx.player(), category.group()).isEmpty();
        if (!any) page.child(Ui.text(lang.ref("menu.directory.empty")).style(Ui.TextStyle.MUTED));
        return page;
    }

    private static boolean isCategory(String key) {
        return key.startsWith("vystorm_core:category-") || key.equals("vystorm_core:tutorials");
    }

    /**
     * Stabile Kachel-ID aus dem Menüschlüssel ({@code m:<key>}), nicht aus der Position (Final Review Important 7); passt
     * der Schlüssel nicht ins ID-Muster oder kommt er zweimal vor, ein Hash aus Gruppe und Schlüssel.
     */
    static String tileId(String group, String key, java.util.Set<String> used) {
        String plain = "m:" + key;
        String id = Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds.element(plain) && !used.contains(plain)
                ? plain : "m:" + SettingsPanels.hash(group + "\u0000" + key);
        used.add(id);
        return id;
    }

    private static Ui.Section grid(String title, List<MenuEntryView> entries, GuiInteface gui, java.util.Set<String> used) {
        Ui.Grid grid = Ui.gridCells(84).gap(4);
        for (MenuEntryView e : entries) {
            Ui.Button tile = Ui.button(tileId(e.group(), e.key(), used)).label(e.title()).icon(Ui.item(e.icon()));
            if (!e.description().isBlank()) tile.tooltip(e.title(), e.description());
            tile.onClick(a -> gui.openMenu(a.player(), e.key()) ? Result.ok() : Result.error(Localization.get().core().ref("common.menu-unavailable")));
            grid.child(tile);
        }
        return Ui.section(title).children(grid);
    }
}
