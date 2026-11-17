package Vystorm.vystorm_core._uiMethods.menu;

import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.autoGui.MenuEntryView;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import java.util.*;
import org.bukkit.entity.Player;

/**
 * Search, favourites and history for every registered menu; no retained Player references. @since 0.26.5
 * <p>Preferred access: {@code api.gui().menuExplorer()}.
 */
public final class MenuExplorer {
    private MenuExplorer() {}
    private static final String FAVORITES = "menu.favorites", RECENT = "menu.recent";

    static List<String> decode(String raw, int limit) {
        return Arrays.stream(raw.split("\n")).filter(s -> s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                .distinct().limit(limit).toList();
    }
    private static List<String> read(Player p, String id, int limit) {
        String value = p.getPersistentDataContainer().get(org.bukkit.NamespacedKey.fromString("vystorm_core:pref." + id), org.bukkit.persistence.PersistentDataType.STRING);
        return decode(value == null ? "" : value, limit);
    }
    private static void write(Player p, String id, List<String> keys) {
        p.getPersistentDataContainer().set(org.bukkit.NamespacedKey.fromString("vystorm_core:pref." + id), org.bukkit.persistence.PersistentDataType.STRING, String.join("\n", keys));
    }
    public static void visited(Player p, String key) {
        if (key.startsWith("vystorm_core:category-") || key.equals("vystorm_core:menu")) return;
        List<String> next = new ArrayList<>(read(p, RECENT, 8));
        next.remove(key); next.addFirst(key);
        write(p, RECENT, next.subList(0, Math.min(8, next.size())));
    }
    /** Clears only this player's recent menu history; favorites stay intact. Server thread only. @since 0.49.0 */
    public static void clearRecent(Player player) {
        player.getPersistentDataContainer().remove(org.bukkit.NamespacedKey.fromString("vystorm_core:pref." + RECENT));
    }

    public static void open(Player p, GuiInteface gui) { open(p, gui, "", "all", 0); }

    private static void open(Player p, GuiInteface gui, String query, String mode, int requestedPage) {
        var lang = Localization.get().core();
        var i18n = Localization.get();
        List<String> favorites = read(p, FAVORITES, 20), recent = read(p, RECENT, 8);
        String filter = query.toLowerCase(Locale.ROOT);
        List<MenuEntryView> visible = new ArrayList<>(gui.menuEntries(p).stream()
                .filter(e -> !e.key().startsWith("vystorm_core:category-") && !e.key().equals("vystorm_core:menu"))
                .filter(e -> mode.equals("all") || (mode.equals("favorites") ? favorites : recent).contains(e.key()))
                .filter(e -> (e.key() + " " + i18n.resolve(p, e.title()) + " " + i18n.resolve(p, e.description()))
                        .toLowerCase(Locale.ROOT).contains(filter)).toList());
        if (mode.equals("recent")) visible.sort(Comparator.comparingInt(e -> recent.indexOf(e.key())));
        int pages = Math.max(1, (visible.size() + 7) / 8), page = Math.max(0, Math.min(requestedPage, pages - 1));
        List<DialogButton> buttons = new ArrayList<>();
        for (String tab : List.of("all", "favorites", "recent")) buttons.add(new DialogButton(lang.component(p, "menu.explorer." + tab),
                (v, r) -> open(v, gui, query, tab, 0)));
        buttons.add(new DialogButton(lang.component(p, "menu.explorer.search"),
                (v, r) -> open(v, gui, Objects.requireNonNullElse(r.getText("query"), ""), mode, 0)));
        for (MenuEntryView e : visible.subList(page * 8, Math.min(visible.size(), (page + 1) * 8))) {
            buttons.add(new DialogButton(i18n.component(p, e.title()), i18n.component(p, e.description()), (v, r) -> {
                if (!gui.openMenu(v, e.key())) lang.send(v, "common.menu-unavailable");
            }));
            buttons.add(new DialogButton(lang.component(p, favorites.contains(e.key()) ? "menu.explorer.unpin" : "menu.explorer.pin"), (v, r) -> {
                // Access may have changed since this dialog was rendered.
                if (!gui.menuKeys(v).contains(e.key())) { lang.send(v, "common.menu-unavailable"); return; }
                List<String> next = new ArrayList<>(read(v, FAVORITES, 20));
                if (!next.remove(e.key())) {
                    if (next.size() == 20) { lang.send(v, "menu.explorer.full"); open(v, gui, query, mode, page); return; }
                    next.add(e.key());
                }
                write(v, FAVORITES, next); open(v, gui, query, mode, page);
            }));
        }
        if (mode.equals("recent") && !recent.isEmpty()) {
            buttons.add(new DialogButton(lang.component(p, "menu.explorer.clear-recent"), (v, r) -> {
                clearRecent(v);
                lang.send(v, "menu.explorer.recent-cleared");
                open(v, gui, query, mode, 0);
            }));
        }
        if (page > 0) buttons.add(new DialogButton(lang.component(p, "common.previous"), (v, r) -> open(v, gui, query, mode, page - 1)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang.component(p, "common.next"), (v, r) -> open(v, gui, query, mode, page + 1)));
        main.getApi().dialogs().open(main.getPlugin(), p, new DialogRequest(lang.component(p, "menu.explorer.title"),
                List.of(DialogBody.plainMessage(lang.component(p, "menu.explorer.summary", "count", visible.size(), "page", page + 1, "pages", pages))),
                List.of(DialogInput.text("query", lang.component(p, "menu.explorer.search")).initial(query).maxLength(80).build()),
                buttons, Player::isOnline, () -> {}, lang.text(p, "common.close"), 2));
    }
}
