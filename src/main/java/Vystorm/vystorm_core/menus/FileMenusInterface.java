package Vystorm.vystorm_core.menus;

import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Manager view of {@link FileMenus}, reached through {@code api.gui().fileMenus()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link FileMenus}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.65.0
 */
public interface FileMenusInterface {
    /** The delegating default. Plugins use {@code api.gui().fileMenus()}. */
    FileMenusInterface DEFAULT = new FileMenusInterface() {};

    /** See {@link FileMenus#enabled}. */
    default boolean enabled() { return FileMenus.enabled(); }

    /** See {@link FileMenus#reload}. */
    default int reload() { return FileMenus.reload(); }

    /** See {@link FileMenus#ids}. */
    default List<String> ids() { return FileMenus.ids(); }

    /** See {@link FileMenus#open}. */
    default boolean open(Player player, String id) { return FileMenus.open(player, id); }

    /** See {@link FileMenus#errors}. */
    default Map<String, List<String>> errors() { return FileMenus.errors(); }

    /** See {@link FileMenus#warnings}. */
    default Map<String, List<String>> warnings() { return FileMenus.warnings(); }
}
