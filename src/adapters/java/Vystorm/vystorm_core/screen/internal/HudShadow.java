package Vystorm.vystorm_core.screen.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;

/** Text without shadow (Adventure 4.18+, clients 1.21.4+): HUD window rows must not leave a shadow at the screen centre. */
public final class HudShadow {
    private HudShadow() {}

    public static Component none(Component text) { return Component.empty().shadowColor(ShadowColor.none()).append(text); }
}
