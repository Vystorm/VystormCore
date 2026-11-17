package Vystorm.vystorm_core._uiMethods.menu;

import org.bukkit.Material;

import java.util.List;

/**
 * Standard groups of the Core main menu (/vmenu). Pass {@link #group()} as the {@code group} argument of
 * {@code api.gui().registerMenu(...)}; a category only appears in the main menu while it has visible entries.
 */
public enum MenuCategory {
    CHARACTER("Charakter", "Skills, Begleiter und dein Zustand", Material.PLAYER_HEAD),
    ECONOMY("Wirtschaft", "Geld, Markt und Lager", Material.EMERALD),
    COMMUNITY("Gemeinschaft", "Städte, Gruppen und Mitspieler", Material.BEACON),
    WORLD("Welt & Abenteuer", "Arenen, Kopfgelder und Weltereignisse", Material.FILLED_MAP),
    BUILDING("Bauen & Technik", "Bauwerkzeuge und Funktionsblöcke", Material.ANVIL),
    TUTORIALS("Tutorials", "Anleitungen zu allen Plugins", Material.BOOK),
    ADMIN("Administration", "Werkzeuge für Admins", Material.COMMAND_BLOCK);

    private final String group;
    private final String description;
    private final Material icon;

    MenuCategory(String group, String description, Material icon) {
        this.group = group; this.description = description; this.icon = icon;
    }

    /** The directory group name used by registerMenu. */
    public String group() { return group; }
    public String description() { return description; }
    public Material icon() { return icon; }
    /** Registry key of the category entry in the main directory, e.g. {@code vystorm_core:category-economy} ({@code vystorm_core:tutorials} for tutorials). */
    public String key() { return this == TUTORIALS ? "tutorials" : "category-" + name().toLowerCase(java.util.Locale.ROOT); }

    public static List<MenuCategory> ordered() { return List.of(values()); }

    /** Core language key prefix, e.g. {@code menu.category.economy} ({@code .title}, {@code .description}). @since 0.22.0 */
    public String langKey() { return "menu.category." + name().toLowerCase(java.util.Locale.ROOT); }

    /** Name in the viewer's language (the {@link #group()} id stays unchanged). @since 0.22.0 */
    public String title(org.bukkit.command.CommandSender viewer) {
        return Vystorm.vystorm_core.lang.Localization.get().core().text(viewer, langKey() + ".title");
    }

    /** The category whose {@link #group()} id is {@code group}, if any. @since 0.22.0 */
    public static java.util.Optional<MenuCategory> byGroup(String group) {
        for (MenuCategory c : values()) if (c.group.equals(group)) return java.util.Optional.of(c);
        return java.util.Optional.empty();
    }
}
