package Vystorm.vystorm_core._uiMethods.autoGui;

import org.bukkit.Material;

/**
 * Ein für den Spieler sichtbarer {@code /vmenu}-Eintrag (für andere Darstellungen wie Core-Panels). {@code title} und
 * {@code description} wie registriert – auch {@code Lang.ref}.
 *
 * @since 0.25.0
 */
public record MenuEntryView(String key, String title, Material icon, String group, String description) {}
