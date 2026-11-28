package Vystorm.vystorm_core.screen.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Player heads in screens (0.71.0). A head item has no flat texture sprite, so {@link ScreenSprites} gave it the paper
 * icon (a white blob in every picker row and mirrored chest). A head is drawn as a 26.x {@code player} object glyph
 * instead: with the skin address the item already carries (a mirrored chest's {@code SkullMeta} profile, no lookup),
 * otherwise the client's default Steve skin from its own assets. Nothing here asks the network. Core-internal.
 */
public final class ScreenHeads {
    /** The client's default skin, a texture of the vanilla assets. */
    public static final String DEFAULT_TEXTURE = "minecraft:entity/player/wide/steve";

    /** What to draw: {@code texturesValue} null = the default skin ({@code id}/{@code name} null then), else a complete profile. */
    public record Head(UUID id, String name, String texturesValue) {
        public boolean plain() { return texturesValue == null; }
    }

    static final Head DEFAULT = new Head(null, null, null);

    private ScreenHeads() { }

    /** The head for an icon (Material or ItemStack), or null when the icon is no player head. */
    public static Head of(Object icon) {
        if (icon instanceof Head head) return head;   // 0.71.1: a head given as it is (Core's player picker rows)
        if (icon instanceof Material m) return m == Material.PLAYER_HEAD ? DEFAULT : null;
        if (!(icon instanceof ItemStack stack) || stack.getType() != Material.PLAYER_HEAD) return icon instanceof String s && isHeadName(s) ? DEFAULT : null;
        try {
            ItemMeta meta = stack.getItemMeta();
            if (!(meta instanceof SkullMeta skull)) return DEFAULT;
            org.bukkit.profile.PlayerProfile profile = skull.getOwnerProfile();
            if (profile == null) return DEFAULT;
            java.net.URL skin = profile.getTextures() == null ? null : profile.getTextures().getSkin();
            String value = skin == null ? null : texturesValue(skin.toString());
            if (value == null) return DEFAULT;
            String name = profile.getName();
            UUID id = profile.getUniqueId();
            // a complete profile (id, name, textures) is drawn as it is; the client looks nothing up for it
            return new Head(id == null ? new UUID(0, 0) : id, name == null || name.isEmpty() || name.length() > 16 || !name.matches("[A-Za-z0-9_]+") ? "Player" : name, value);
        } catch (RuntimeException | LinkageError noProfile) {
            return DEFAULT;
        }
    }

    /**
     * 0.71.1: the head of a player with this skin address (Mojang's texture server only), or null when the address
     * cannot be drawn. A name a profile cannot carry is drawn as "Player". Pure; no lookup.
     */
    public static Head skin(UUID id, String name, String skinUrl) {
        String value = texturesValue(skinUrl);
        if (value == null) return null;
        String shown = name == null || name.isEmpty() || name.length() > 16 || !name.matches("[A-Za-z0-9_]+") ? "Player" : name;
        return new Head(id == null ? new UUID(0, 0) : id, shown, value);
    }

    static boolean isHeadName(String material) {
        String n = material.toLowerCase(Locale.ROOT);
        return n.equals("player_head") || n.equals("minecraft:player_head");
    }

    /**
     * The unsigned {@code textures} property for a skin address of Mojang's texture server (the form player heads
     * carry), or null for any other address: the client would refuse it anyway.
     */
    public static String texturesValue(String skinUrl) {
        if (skinUrl == null) return null;
        String url = skinUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://textures.minecraft.net/texture/") || lower.startsWith("https://textures.minecraft.net/texture/"))) return null;
        if (url.indexOf('"') >= 0 || url.indexOf('\\') >= 0) return null;
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
