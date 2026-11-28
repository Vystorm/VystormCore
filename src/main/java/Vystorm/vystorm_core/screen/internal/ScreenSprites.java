package Vystorm.vystorm_core.screen.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps a material to a texture sprite of the 26.x client (since 0.57.0), for icons drawn as text ({@code object}
 * components). Icons as text belong to the same see-through layer as the rest of a screen, so the black backdrop
 * cannot hide them. The list of sprite names ships in {@code screens/sprites.txt} (names only).
 */
public final class ScreenSprites {
    /** Atlas and sprite, e.g. {@code minecraft:items} + {@code item/diamond}. */
    public record Sprite(String atlas, String sprite) { }

    static final Sprite FALLBACK = new Sprite("minecraft:items", "item/paper");
    private static volatile Set<String> known;

    private ScreenSprites() { }

    static Set<String> known() {
        Set<String> names = known;
        if (names != null) return names;
        Set<String> loaded = new HashSet<>();
        try (InputStream in = ScreenSprites.class.getClassLoader().getResourceAsStream("screens/sprites.txt")) {
            if (in != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                reader.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(loaded::add);
            }
        } catch (IOException ignored) { }
        known = loaded;
        return loaded;
    }

    /** The best sprite for a material name like {@code COMPASS} or {@code GRASS_BLOCK}. */
    public static Sprite of(String material) {
        return of(material, known());
    }

    /**
     * True when the client has a flat texture for the material. Items that are drawn from a model (chests, beds,
     * banners, heads, shulker boxes) and blocks that borrow another block's texture (carpets) have none; for them
     * {@link #of} gives the paper icon. @since 0.65.0
     */
    public static boolean has(String material) {
        // 0.71.0: player heads are drawn as a player object glyph (ScreenHeads)
        return "PAPER".equalsIgnoreCase(material) || (material != null && ScreenHeads.isHeadName(material)) || !FALLBACK.equals(of(material));
    }

    static Sprite of(String material, Set<String> names) {
        String n = material == null ? "" : material.toLowerCase(Locale.ROOT);
        for (String item : List.of("item/" + n, "item/" + n + "_00")) if (names.contains(item)) return new Sprite("minecraft:items", item);
        String block = n.endsWith("_block") && !names.contains("block/" + n) ? n.substring(0, n.length() - 6) : n;
        for (String b : List.of("block/" + n, "block/" + n + "_top", "block/" + n + "_front", "block/" + n + "_side", "block/" + block, "block/" + block + "_top"))
            if (names.contains(b)) return new Sprite("minecraft:blocks", b);
        return FALLBACK;
    }
}
