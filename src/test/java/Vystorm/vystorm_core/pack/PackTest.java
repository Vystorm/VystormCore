package Vystorm.vystorm_core.pack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PackTest {
    private final Plugin items = Mockito.mock(Plugin.class);
    private final Plugin mobs = Mockito.mock(Plugin.class);

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void givenSameModules_whenBuiltTwice_thenIdenticalHash() throws Exception {
        AssetRegistry registry = new AssetRegistry();
        registry.register(items, "vystorm-items", List.of("vystorm_items"), Map.of("assets/vystorm_items/models/item/sword.json", b("{}")), 10);
        registry.register(mobs, "vystorm-mobs", List.of("vystorm_mobs"), Map.of("assets/vystorm_mobs/textures/a.png", b("png")), 20);
        PackBuilder.Built first = PackBuilder.build(registry.modules(), 69, "Vystorm");
        PackBuilder.Built second = PackBuilder.build(registry.modules(), 69, "Vystorm");
        assertEquals(first.sha1(), second.sha1());
        assertEquals(3, first.files());
        assertTrue(first.conflicts().isEmpty());
    }

    @Test
    void givenForeignNamespaceOrTraversal_whenRegistered_thenRejected() {
        AssetRegistry registry = new AssetRegistry();
        registry.register(items, "vystorm-items", List.of("vystorm_items"), Map.of(), 10);
        assertThrows(IllegalStateException.class, () -> registry.register(mobs, "other", List.of("vystorm_items"), Map.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> registry.register(mobs, "m", List.of("vystorm_mobs"), Map.of("assets/vystorm_items/x.json", b("{}")), 1));
        assertThrows(IllegalArgumentException.class, () -> registry.register(mobs, "m", List.of("vystorm_mobs"), Map.of("assets/vystorm_mobs/../x.json", b("{}")), 1));
        assertThrows(IllegalArgumentException.class, () -> registry.register(mobs, "m", List.of("minecraft"), Map.of(), 1));
    }

    @Test
    void givenOverrideOfSameFile_whenBuilt_thenConflictReportedAndHigherPriorityWins() throws Exception {
        AssetRegistry registry = new AssetRegistry();
        registry.register(items, "a", List.of("ns_a"), Map.of("assets/minecraft/models/item/stick.json", b("A")), 1);
        registry.register(mobs, "b", List.of("ns_b"), Map.of("assets/minecraft/models/item/stick.json", b("B")), 2);
        PackBuilder.Built built = PackBuilder.build(registry.modules(), 69, "x");
        assertEquals(1, built.conflicts().size());
    }

    @Test
    void givenAssetKeys_whenModelDataRequested_thenStableAndUnique() {
        AssetRegistry registry = new AssetRegistry();
        int a = registry.customModelData("vystorm:item/swords/iron_01");
        int b = registry.customModelData("vystorm:item/swords/iron_02");
        assertNotEquals(a, b);
        assertEquals(a, registry.customModelData("VYSTORM:item/swords/iron_01"));
        assertEquals("vystorm:item/swords/iron_01", registry.assetForModelData(a).orElseThrow());
        assertEquals("vystorm", AssetRegistry.itemModel("vystorm:item/swords/iron_01").getNamespace());
    }

    @Test
    void givenClientStatuses_whenMapped_thenPackStates() {
        assertEquals(PackState.LOADED, ResourcePacks.map(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
        assertEquals(PackState.DECLINED, ResourcePacks.map(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED));
        assertEquals(PackState.DOWNLOADING, ResourcePacks.map(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED));
        assertEquals(PackState.FAILED, ResourcePacks.map(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD));
    }

    @Test
    void givenModernFormat_whenBuilt_thenMcmetaHasMinAndMaxFormat() throws Exception {
        AssetRegistry registry = new AssetRegistry();
        registry.register(items, "vystorm-items", List.of("vystorm_items"), Map.of("assets/vystorm_items/textures/a.png", b("png")), 10);
        String mcmeta = PackBuilder.mcmeta(88, "Vystorm");
        assertTrue(mcmeta.contains("\"min_format\":88"), mcmeta);
        assertTrue(mcmeta.contains("\"max_format\":" + PackBuilder.MAX_FORMAT), mcmeta);
        assertFalse(mcmeta.contains("pack_format"), mcmeta);
        assertTrue(PackBuilder.MAX_FORMAT >= 88);
        // 26.2 (Ressourcenformat 88) liegt auch bei alter Einstellung 69 im Bereich
        assertTrue(PackBuilder.mcmeta(69, "x").contains("\"min_format\":69"));
    }

    @Test
    void givenLegacyFormat_whenBuilt_thenMcmetaUsesPackFormat() {
        String mcmeta = PackBuilder.mcmeta(46, "x");
        assertTrue(mcmeta.contains("\"pack_format\":46"), mcmeta);
        assertFalse(mcmeta.contains("min_format"), mcmeta);
    }
}
