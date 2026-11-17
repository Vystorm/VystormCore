// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelColor.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import Vystorm.vystorm_core.web.internal.clientproto.ui.Colors;
import java.util.Locale;

/** Farbe als ARGB oder Theme-Name ({@code accent}, {@code ok}, …, {@code rarity.mythic}); der Client löst Namen über das Theme auf. */
public record PanelColor(int argb, Token token) {
    public enum Token { ACCENT, OK, WARN, DANGER, MUTED, RARITY_COMMON, RARITY_UNCOMMON, RARITY_RARE, RARITY_EPIC, RARITY_LEGENDARY, RARITY_MYTHIC }

    public static PanelColor of(int argb) { return new PanelColor(argb, null); }

    public static PanelColor of(Token token) { return new PanelColor(0, token); }

    /** @return null, wenn ungültig */
    public static PanelColor parse(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "accent": return of(Token.ACCENT);
            case "ok": return of(Token.OK);
            case "warn": return of(Token.WARN);
            case "danger": return of(Token.DANGER);
            case "muted": return of(Token.MUTED);
            default: break;
        }
        if (v.startsWith("rarity.")) {
            try {
                return of(Token.valueOf("RARITY_" + v.substring(7).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        Integer argb = Colors.parse(v);
        return argb == null ? null : of(argb);
    }
}
