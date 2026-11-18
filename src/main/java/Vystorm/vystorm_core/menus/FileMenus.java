package Vystorm.vystorm_core.menus;

import Vystorm.vystorm_core.menus.internal.FileMenuService;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Screen menus from files (since 0.65.0, capability {@code file-menus}): a server owner designs a menu in the Vystorm
 * menu editor and puts the saved file into {@code plugins/vystorm_core/menus/<id>.json}; Core shows it as a Vystorm
 * screen. Every menu opens with {@code /vmenu <id>}, with the command the file names, and from its tile in the main
 * menu. Files are read when Core starts, when its config is reloaded and with {@code /vadmin menus reload}. The
 * format is described in {@code docs/MENUS.md}. Switch: {@code menus.enabled} in Core's {@code config.yml}.
 *
 * <pre>{@code
 * if (CoreApi.has(CoreApi.Capability.FILE_MENUS)) core.gui().fileMenus().open(player, "shop");
 * }</pre>
 *
 * <p>All methods on the server thread. While Core is not enabled or the feature is switched off, the lists are empty
 * and {@link #open} returns false.
 *
 * <p>Preferred access: {@code api.gui().fileMenus()}.
 */
public final class FileMenus {
    private FileMenus() {}

    /** True when the feature is switched on ({@code menus.enabled}) and Core is running. */
    public static boolean enabled() {
        FileMenuService service = FileMenuService.shared();
        return service != null && service.enabled();
    }

    /** Reads the folder again and replaces the menus, their tiles and their commands; returns how many menus are loaded. */
    public static int reload() {
        FileMenuService service = FileMenuService.shared();
        return service == null ? 0 : service.reload();
    }

    /** Ids of the loaded menus, in file order. */
    public static List<String> ids() {
        FileMenuService service = FileMenuService.shared();
        return service == null ? List.of() : service.ids();
    }

    /**
     * Opens a loaded menu for the player, as a screen or, where no screen can open, as a chest. Checks the menu's
     * permission. Returns false, without a message to the player, when no menu has this id or the player may not
     * open it.
     */
    public static boolean open(Player player, String id) {
        FileMenuService service = FileMenuService.shared();
        return service != null && service.open(player, id) == FileMenuService.Result.OPENED;
    }

    /** Files that were skipped: file name to the reasons, each naming the field and what is wrong with it. */
    public static Map<String, List<String>> errors() {
        FileMenuService service = FileMenuService.shared();
        return service == null ? Map.of() : service.errors();
    }

    /** Remarks about files that were loaded (an unknown item, a console command every player can run, ...). */
    public static Map<String, List<String>> warnings() {
        FileMenuService service = FileMenuService.shared();
        return service == null ? Map.of() : service.warnings();
    }
}
