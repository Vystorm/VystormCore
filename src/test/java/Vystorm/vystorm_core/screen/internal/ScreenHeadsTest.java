package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.Test;

/**
 * Defect 2 of the first client look at the pickers: every player head in a screen (picker rows, mirrored chests) was
 * drawn as a white blob, because a head has no flat item sprite and {@link ScreenSprites} answers paper for it.
 */
class ScreenHeadsTest {
    static final String SKIN = "http://textures.minecraft.net/texture/1a2b3c4d5e6f";

    @Test
    void givenAPlayerHeadMaterial_whenDrawn_thenThePlainDefaultHeadNotPaper() {
        assertEquals(ScreenSprites.FALLBACK, ScreenSprites.of("PLAYER_HEAD"), "the cause: no sprite, so paper");
        ScreenHeads.Head head = ScreenHeads.of(Material.PLAYER_HEAD);
        assertNotNull(head);
        assertTrue(head.plain());
        assertTrue(ScreenHeads.of("minecraft:player_head").plain());
        assertNull(ScreenHeads.of(Material.STONE), "other icons keep their sprite");
        assertTrue(ScreenSprites.has("PLAYER_HEAD"), "file menus no longer warn that a head shows as paper");
    }

    @Test
    void givenAPlainHead_whenTurnedIntoText_thenThePlayerObjectWithTheClientsDefaultSkin() {
        String json = GsonComponentSerializer.gson().serialize(SpriteGlyphs.head(ScreenHeads.DEFAULT));
        assertTrue(json.contains("\"player\""), json);
        assertTrue(json.contains(ScreenHeads.DEFAULT_TEXTURE.substring("minecraft:".length())), json);
        assertFalse(json.contains("\"id\""), "no profile to look up: " + json);
    }

    @Test
    void givenAHeadItemWithAStoredSkin_whenDrawn_thenACompleteProfileWithThatSkinAndNoLookup() throws Exception {
        UUID id = new UUID(7, 9);
        ItemStack stack = mock(ItemStack.class);
        SkullMeta meta = mock(SkullMeta.class);
        PlayerProfile profile = mock(PlayerProfile.class);
        PlayerTextures textures = mock(PlayerTextures.class);
        when(stack.getType()).thenReturn(Material.PLAYER_HEAD);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getOwnerProfile()).thenReturn(profile);
        when(profile.getUniqueId()).thenReturn(id);
        when(profile.getName()).thenReturn("AlderQuinn");
        when(profile.getTextures()).thenReturn(textures);
        when(textures.getSkin()).thenReturn(URI.create(SKIN).toURL());
        ScreenHeads.Head head = ScreenHeads.of(stack);
        assertFalse(head.plain());
        assertEquals(id, head.id());
        assertEquals("AlderQuinn", head.name());
        String decoded = new String(Base64.getDecoder().decode(head.texturesValue()), StandardCharsets.UTF_8);
        assertEquals("{\"textures\":{\"SKIN\":{\"url\":\"" + SKIN + "\"}}}", decoded);
        String json = GsonComponentSerializer.gson().serialize(SpriteGlyphs.head(head));
        assertTrue(json.contains("textures") && json.contains("AlderQuinn"), json);

        when(textures.getSkin()).thenReturn(null);
        assertTrue(ScreenHeads.of(stack).plain(), "no skin on the item: the default head, never a lookup");
        when(meta.getOwnerProfile()).thenReturn(null);
        assertTrue(ScreenHeads.of(stack).plain());
    }

    @Test
    void givenSkinAddresses_whenTurnedIntoTheTexturesProperty_thenOnlyMojangsTextureServer() {
        assertNotNull(ScreenHeads.texturesValue(SKIN));
        assertNotNull(ScreenHeads.texturesValue("https://textures.minecraft.net/texture/abc"));
        assertNull(ScreenHeads.texturesValue("http://example.com/texture/abc"));
        assertNull(ScreenHeads.texturesValue("http://textures.minecraft.net/texture/a\"b"));
        assertNull(ScreenHeads.texturesValue(null));
    }
}
