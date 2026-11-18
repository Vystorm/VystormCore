package Vystorm.vystorm_core.menus.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.CommandSender;

/**
 * {@code /vadmin menus}: the menu files with their state (loaded with its command and tile, or skipped with the
 * reasons) and the remarks about loaded files; {@code /vadmin menus reload}: reads the folder again.
 *
 * <p>Core internal (called by the admin dashboard; permission checked there). Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class FileMenusAdmin {
    public static final List<String> SUBCOMMANDS = List.of("reload");

    private FileMenusAdmin() {}

    /** {@code args} as typed after {@code /vadmin}: {@code menus ...}. */
    public static void handle(CommandSender sender, String[] args) {
        Lang lang = Localization.get().core();
        FileMenuService service = FileMenuService.shared();
        if (service == null || !service.enabled()) {
            // a reload with the switch off still drops what was loaded before
            if (service != null && args.length == 2 && args[1].equalsIgnoreCase("reload")) service.reload();
            lang.send(sender, "file-menus.admin.disabled");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
            int loaded = service.reload();
            lang.send(sender, "file-menus.admin.reloaded", "loaded", loaded, "skipped", service.errors().size());
        } else if (args.length > 1) {
            lang.send(sender, "file-menus.admin.usage");
            return;
        }
        list(sender, service, lang);
    }

    private static void list(CommandSender sender, FileMenuService service, Lang lang) {
        Map<String, List<String>> errors = service.errors(), warnings = service.warnings();
        String folder = "plugins/" + service.folder().getParent().getFileName() + "/" + service.folder().getFileName();
        lang.send(sender, "file-menus.admin.header", "loaded", service.ids().size(), "skipped", errors.size(), "folder", folder);
        if (service.ids().isEmpty() && errors.isEmpty()) lang.send(sender, "file-menus.admin.none", "folder", folder);
        Map<String, String> commandProblems = service.commandProblems();
        for (String id : service.ids()) {
            MenuFile menu = service.menu(id);
            if (menu == null) continue;
            lang.send(sender, "file-menus.admin.line", "id", id, "frame", menu.frame().name().toLowerCase(Locale.ROOT),
                    "widgets", menu.widgets().size(),
                    "command", menu.command().isEmpty() || commandProblems.containsKey(id) ? "-" : "/" + menu.command(),
                    "tile", lang.plain(sender, menu.mainMenu() ? "file-menus.admin.tile-shown" : "file-menus.admin.tile-hidden"));
            if (commandProblems.containsKey(id)) lang.send(sender, "file-menus.admin.remark", "text", commandProblems.get(id));
            for (String line : warnings.getOrDefault(id + ".json", List.of())) lang.send(sender, "file-menus.admin.remark", "text", line);
        }
        errors.forEach((file, lines) -> {
            lang.send(sender, "file-menus.admin.skipped", "file", file);
            for (String line : lines) lang.send(sender, "file-menus.admin.reason", "text", line);
        });
        lang.send(sender, "file-menus.admin.usage");
    }
}
