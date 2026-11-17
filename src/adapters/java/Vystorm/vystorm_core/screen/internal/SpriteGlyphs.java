package Vystorm.vystorm_core.screen.internal;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;

/**
 * Texture sprites as text (26.x {@code object} components, since Core 0.57.0). Adapter: the API is newer than Paper
 * 1.20.6, so callers catch {@link LinkageError} and fall back to item displays there.
 */
public final class SpriteGlyphs {
    private SpriteGlyphs() { }

    public static Component glyph(String atlas, String sprite) {
        return Component.object(ObjectContents.sprite(Key.key(atlas), Key.key(sprite)));
    }
}
