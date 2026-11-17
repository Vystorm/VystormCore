package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.web.WebOpenResult;
import Vystorm.vystorm_core.web.WebServices;
import java.util.logging.Level;
import org.bukkit.entity.Player;

/**
 * {@code Panels.open} (Spec §7): Recht → natives Panel → eigenes {@code fallback} des Plugins → [Paper-Dialog] →
 * Web-Link ({@code WebServices.openOrLink}) → Hinweis. Server-Thread. Core-intern.
 */
public final class PanelOpener {
    private PanelOpener() {}

    /** Core config {@code screens.panels} (default true on the screens branch; the owner decides before release). */
    static boolean screensForPanels() {
        try { return Vystorm.vystorm_core.main.getPlugin().getConfig().getBoolean("screens.panels", true); }
        catch (RuntimeException | LinkageError noCore) { return false; }
    }

    public static PanelOpenResult open(Player player, PanelRegistry.Registered reg, String arg) {
        if (player == null || reg == null || !reg.owner().isEnabled()) return PanelOpenResult.NONE;
        boolean allowed;
        try { allowed = reg.spec().access().test(player); } catch (RuntimeException failure) { allowed = false; }
        if (!allowed) {
            Localization.get().core().send(player, "panel.no-permission");
            return PanelOpenResult.NONE;
        }
        PanelHub hub = PanelPlatform.hub().orElse(null);
        if (hub != null && hub.available(player)) return hub.openNative(player, reg, arg, 0);
        // Vystorm Screens (0.57.0): before the plugin's own fallback, so classic chest menus give way to the screen.
        if (screensForPanels() && hub != null && hub.openScreen(player, reg, arg)) return PanelOpenResult.DIALOG;
        if (reg.spec().fallback() != null) {
            try {
                reg.spec().fallback().accept(player);
            } catch (RuntimeException failure) {
                reg.owner().getLogger().log(Level.WARNING, "Panel fallback of " + reg.id() + " failed", failure);
            }
            return PanelOpenResult.FALLBACK;
        }
        if (reg.spec().dialogFallback()) {
            PanelHub dialogHub = PanelPlatform.hub().orElse(null);
            if (dialogHub != null && dialogHub.openDialog(player, reg, arg)) return PanelOpenResult.DIALOG;
        }
        if (reg.spec().webModule() != null) {
            try {
                WebOpenResult r = WebServices.openOrLink(player, reg.spec().webModule(), reg.spec().webSubPath(), null);
                if (r == WebOpenResult.LINK || r == WebOpenResult.OVERLAY) return PanelOpenResult.WEB;
            } catch (RuntimeException | LinkageError webOff) {
                // weiter zum Hinweis
            }
        }
        Localization.get().core().send(player, "panel.unavailable");
        return PanelOpenResult.NONE;
    }
}
