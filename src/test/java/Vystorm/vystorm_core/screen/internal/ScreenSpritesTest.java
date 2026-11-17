package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScreenSpritesTest {
    @Test void givenShippedList_thenItemsBlocksAndAnimatedItemsResolve() {
        assertEquals(new ScreenSprites.Sprite("minecraft:items", "item/writable_book"), ScreenSprites.of("WRITABLE_BOOK"));
        assertEquals(new ScreenSprites.Sprite("minecraft:items", "item/compass_00"), ScreenSprites.of("COMPASS"));
        assertEquals(new ScreenSprites.Sprite("minecraft:blocks", "block/stone"), ScreenSprites.of("STONE"));
        assertEquals("minecraft:blocks", ScreenSprites.of("GRASS_BLOCK").atlas());
        assertTrue(ScreenSprites.of("GRASS_BLOCK").sprite().startsWith("block/grass_block"));
    }

    @Test void givenUnknownMaterial_thenPaper() {
        assertEquals(ScreenSprites.FALLBACK, ScreenSprites.of("NOT_A_THING"));
        assertEquals(ScreenSprites.FALLBACK, ScreenSprites.of(null));
    }
}
