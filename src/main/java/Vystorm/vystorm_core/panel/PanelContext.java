package Vystorm.vystorm_core.panel;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Eingabe von {@code PanelSpec.render}: für wen, mit welchem Argument, Texte des Plugins in der Spielersprache. @since 0.25.0 */
public interface PanelContext {
    Player player();

    /** Argument aus {@code navigate}/{@code open(…, arg)}; aus P_REQUEST ist es **Client-Eingabe** – selbst prüfen. */
    String arg();

    /** Text aus dem Sprachpaket des Plugins, einfach (für Beschriftungen, Muster). */
    String text(String key, Object... placeholders);

    /** Text aus dem Sprachpaket des Plugins mit Farben/Stil. */
    Component component(String key, Object... placeholders);

    PanelSession session();
}
