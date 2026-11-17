package Vystorm.vystorm_core.lang.internal;

import Vystorm.vystorm_core.lang.Localization;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.List;
import java.util.Map;

/**
 * Flag-like banner icons for the language menu (a few patterns approximating the flag usually associated with the
 * language; a globe banner for others). Data only until {@link #item} runs on a server. Core-internal.
 */
public final class LanguageFlags {
    /** One banner: base colour and patterns (pattern id → colour, in order). */
    public record Flag(DyeColor base, List<Map.Entry<String, DyeColor>> layers) {}

    private static Map.Entry<String, DyeColor> l(String pattern, DyeColor color) { return Map.entry(pattern, color); }

    private static final Map<String, Flag> FLAGS = Map.ofEntries(
            Map.entry("en", new Flag(DyeColor.BLUE, List.of(l("cross", DyeColor.WHITE), l("straight_cross", DyeColor.RED)))),
            Map.entry("de", new Flag(DyeColor.RED, List.of(l("stripe_top", DyeColor.BLACK), l("stripe_bottom", DyeColor.YELLOW)))),
            Map.entry("zh_cn", new Flag(DyeColor.RED, List.of(l("square_top_left", DyeColor.YELLOW)))),
            Map.entry("zh_tw", new Flag(DyeColor.RED, List.of(l("square_top_left", DyeColor.BLUE)))),
            Map.entry("zh_hk", new Flag(DyeColor.RED, List.of(l("flower", DyeColor.WHITE)))),
            Map.entry("fr", new Flag(DyeColor.WHITE, List.of(l("stripe_left", DyeColor.BLUE), l("stripe_right", DyeColor.RED)))),
            Map.entry("it", new Flag(DyeColor.WHITE, List.of(l("stripe_left", DyeColor.GREEN), l("stripe_right", DyeColor.RED)))),
            Map.entry("es", new Flag(DyeColor.YELLOW, List.of(l("stripe_top", DyeColor.RED), l("stripe_bottom", DyeColor.RED)))),
            Map.entry("nl", new Flag(DyeColor.WHITE, List.of(l("stripe_top", DyeColor.RED), l("stripe_bottom", DyeColor.BLUE)))),
            Map.entry("ru", new Flag(DyeColor.WHITE, List.of(l("stripe_middle", DyeColor.BLUE), l("stripe_bottom", DyeColor.RED)))),
            Map.entry("pl", new Flag(DyeColor.WHITE, List.of(l("half_horizontal_bottom", DyeColor.RED)))),
            Map.entry("uk", new Flag(DyeColor.BLUE, List.of(l("half_horizontal_bottom", DyeColor.YELLOW)))),
            Map.entry("ja", new Flag(DyeColor.WHITE, List.of(l("circle", DyeColor.RED)))),
            Map.entry("ko", new Flag(DyeColor.WHITE, List.of(l("circle", DyeColor.RED), l("triangles_top", DyeColor.BLACK)))),
            Map.entry("pt", new Flag(DyeColor.RED, List.of(l("stripe_left", DyeColor.GREEN), l("circle", DyeColor.YELLOW)))),
            Map.entry("pt_br", new Flag(DyeColor.GREEN, List.of(l("rhombus", DyeColor.YELLOW), l("circle", DyeColor.BLUE)))),
            Map.entry("tr", new Flag(DyeColor.RED, List.of(l("circle", DyeColor.WHITE)))),
            Map.entry("sv", new Flag(DyeColor.BLUE, List.of(l("straight_cross", DyeColor.YELLOW)))),
            Map.entry("da", new Flag(DyeColor.RED, List.of(l("straight_cross", DyeColor.WHITE)))),
            Map.entry("no", new Flag(DyeColor.RED, List.of(l("straight_cross", DyeColor.WHITE), l("stripe_center", DyeColor.BLUE)))),
            Map.entry("fi", new Flag(DyeColor.WHITE, List.of(l("straight_cross", DyeColor.BLUE)))),
            Map.entry("cs", new Flag(DyeColor.WHITE, List.of(l("half_horizontal_bottom", DyeColor.RED), l("triangle_top", DyeColor.BLUE)))),
            Map.entry("hu", new Flag(DyeColor.WHITE, List.of(l("stripe_top", DyeColor.RED), l("stripe_bottom", DyeColor.GREEN)))));

    private static final Flag UNKNOWN = new Flag(DyeColor.LIGHT_BLUE, List.of(l("globe", DyeColor.WHITE)));

    private LanguageFlags() {}

    /** The flag for a code (exact code, then its language part, else a globe). */
    public static Flag flag(String code) {
        String normalized = Localization.normalize(code);
        Flag flag = FLAGS.get(normalized);
        if (flag == null) flag = FLAGS.get(Localization.languagePart(normalized));
        return flag == null ? UNKNOWN : flag;
    }

    /** A banner item for the code (server only: needs the banner pattern registry). */
    public static ItemStack item(String code) {
        Flag flag = flag(code);
        Material material = Material.matchMaterial(flag.base().name() + "_BANNER");
        ItemStack item = new ItemStack(material == null ? Material.WHITE_BANNER : material);
        try {
            Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, BannerMeta.class, meta -> {
                for (Map.Entry<String, DyeColor> layer : flag.layers()) {
                    PatternType type = Registry.BANNER_PATTERN.get(NamespacedKey.minecraft(layer.getKey()));
                    if (type != null) meta.addPattern(new Pattern(layer.getValue(), type));
                }
                try { meta.addItemFlags(ItemFlag.valueOf("HIDE_ADDITIONAL_TOOLTIP")); } catch (IllegalArgumentException ignored) { /* renamed */ }
            });
        } catch (RuntimeException | LinkageError ignored) {
            // plain banner of the base colour
        }
        return item;
    }
}
