package Vystorm.vystorm_core.screen.internal;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;

/**
 * Texture sprites as text (26.x {@code object} components, since Core 0.57.0). Adapter: the API is newer than Paper
 * 1.20.6, so callers catch {@link LinkageError} and fall back to item displays there.
 */
public final class SpriteGlyphs {
    private SpriteGlyphs() { }

    public static Component glyph(String atlas, String sprite) {
        return Component.object(ObjectContents.sprite(Key.key(atlas), Key.key(sprite)));
    }

    /**
     * A player head as text (0.71.0): with a skin ({@code textures} property, unsigned, from what the server has) a
     * complete profile, so the client resolves nothing; without one the client's default skin texture.
     */
    public static Component head(ScreenHeads.Head head) {
        PlayerHeadObjectContents.Builder b = ObjectContents.playerHead().hat(true);
        if (head == null || head.plain()) {
            b.texture(Key.key(ScreenHeads.DEFAULT_TEXTURE));
        } else {
            b.id(head.id()).name(head.name());
            b.profileProperty(PlayerHeadObjectContents.property("textures", head.texturesValue()));
        }
        return Component.object(b.build());
    }
}
