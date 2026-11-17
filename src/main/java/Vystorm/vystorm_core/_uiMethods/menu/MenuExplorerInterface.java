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
 * Manager view of {@link MenuExplorer}, reached through {@code api.gui().menuExplorer()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MenuExplorer}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MenuExplorerInterface {
    /** The delegating default. Plugins use {@code api.gui().menuExplorer()}. */
    MenuExplorerInterface DEFAULT = new MenuExplorerInterface() {};

    /** See {@link MenuExplorer#visited}. */
    default void visited(Player p, String key) { MenuExplorer.visited(p, key); }

    /** Clears only this player's recent menu history; favorites stay intact. Server thread only. @since 0.49.0 */
    default void clearRecent(Player player) { MenuExplorer.clearRecent(player); }

    /** See {@link MenuExplorer#open}. */
    default void open(Player p, GuiInteface gui) { MenuExplorer.open(p, gui); }
}
