package Vystorm.vystorm_core.picker.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.internal.ScreenHeads;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 0.71.1: picker rows on a Vystorm Screen draw the same skin as the chest's heads. */
class PickerScreenHeadTest {
    private static final String SKIN = "http://textures.minecraft.net/texture/abc123";
    private static final String OTHER = "https://textures.minecraft.net/texture/def456";
    private final UUID id = UUID.randomUUID();

    private PickerModel.Candidate candidate(boolean online, String skin) {
        return new PickerModel.Candidate(id, "Alex_1", online, 0L, 3.0, skin);
    }

    @Test void givenOnlinePlayerWithStoredSkin_whenScreenHead_thenThatSkin() {
        ScreenHeads.Head head = PickerChest.screenHead(candidate(true, SKIN), PickerConfig.Heads.ALL, u -> OTHER);
        assertNotNull(head);
        assertEquals(id, head.id());
        assertEquals("Alex_1", head.name());
        assertEquals(ScreenHeads.texturesValue(SKIN), head.texturesValue());
    }

    @Test void givenOnlinePlayerWithoutStoredSkin_whenScreenHead_thenTheServersProfileSkin() {
        ScreenHeads.Head head = PickerChest.screenHead(candidate(true, null), PickerConfig.Heads.ONLINE, u -> u.equals(id) ? OTHER : null);
        assertNotNull(head);
        assertEquals(ScreenHeads.texturesValue(OTHER), head.texturesValue());
    }

    @Test void givenOfflinePlayer_whenScreenHead_thenStoredSkinOnlyWhenHeadsAll() {
        assertNotNull(PickerChest.screenHead(candidate(false, SKIN), PickerConfig.Heads.ALL, u -> null));
        assertNull(PickerChest.screenHead(candidate(false, SKIN), PickerConfig.Heads.ONLINE, u -> OTHER), "as the chest: offline skins only with heads: all");
        assertNull(PickerChest.screenHead(candidate(false, null), PickerConfig.Heads.ALL, u -> OTHER), "an offline player's profile is never asked");
    }

    @Test void givenHeadsNoneOrNoUsableSkin_whenScreenHead_thenPlainHead() {
        assertNull(PickerChest.screenHead(candidate(true, SKIN), PickerConfig.Heads.NONE, u -> OTHER));
        assertNull(PickerChest.screenHead(candidate(true, "https://example.org/skin.png"), PickerConfig.Heads.ALL, u -> null), "only Mojang's texture server");
        assertNull(PickerChest.screenHead(candidate(true, null), PickerConfig.Heads.ALL, u -> null));
        assertNull(PickerChest.screenHead(null, PickerConfig.Heads.ALL, u -> OTHER));
    }

    @Test void givenStoredSkinNotDrawable_whenOnline_thenFallsBackToTheProfileSkin() {
        ScreenHeads.Head head = PickerChest.screenHead(candidate(true, "https://example.org/skin.png"), PickerConfig.Heads.ALL, u -> OTHER);
        assertNotNull(head);
        assertEquals(ScreenHeads.texturesValue(OTHER), head.texturesValue());
    }

    @Test void givenAHead_whenScreenHeadsOf_thenDrawnAsItIs() {
        ScreenHeads.Head head = ScreenHeads.skin(id, "a name with spaces", SKIN);
        assertEquals("Player", head.name(), "a name a profile cannot carry");
        assertSame(head, ScreenHeads.of(head));
    }
}
