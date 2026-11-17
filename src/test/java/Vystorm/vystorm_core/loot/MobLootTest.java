package Vystorm.vystorm_core.loot;

import java.io.File;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobLootTest {
    private final Plugin core = plugin("Vystorm_Core"), items = plugin("VystormItems"), director = plugin("SpawnDirector");

    private static Plugin plugin(String name) {
        Plugin p = mock(Plugin.class);
        when(p.isEnabled()).thenReturn(true);
        when(p.getName()).thenReturn(name);
        return p;
    }

    @AfterEach void cleanup() {
        LootTables.release(core); LootTables.release(items);
        MobLoot.release(items); MobLoot.release(director);
        MobLoot.configure(MobLoot.Settings.DEFAULT);
    }

    private void coreFiles() {
        Map<String, LootTable> all = new LinkedHashMap<>();
        for (String name : LootTableConfig.CORE_FILES)
            all.putAll(LootTableConfig.parse(YamlConfiguration.loadConfiguration(new File("src/main/resources/" + name))));
        LootTables.replace(core, all);
    }

    @Test void givenLevels_whenComputingLuck_thenLinearUpToTheCap() {
        assertEquals(0, MobLoot.luck(0));
        assertEquals(0, MobLoot.luck(-5));
        assertEquals(0, MobLoot.luck(Double.NaN));
        assertEquals(1.0, MobLoot.luck(20), 1e-9);
        assertEquals(3.0, MobLoot.luck(60), 1e-9);
        assertEquals(3.0, MobLoot.luck(100), 1e-9);
        var custom = new MobLoot.Settings(0.1, 2, null, null, null);
        assertEquals(2.0, MobLoot.luck(50, custom), 1e-9);
    }

    @Test void givenConfig_whenReadingSettings_thenValuesAndPityApplied() {
        var yaml = new YamlConfiguration();
        yaml.set("loot.mob-level-luck.per-level", 0.1);
        yaml.set("loot.mob-level-luck.max", 5.0);
        yaml.set("loot.mob-pity.elite", "uncommon");
        yaml.set("loot.mob-pity.boss", "none");
        var s = MobLoot.Settings.of(yaml);
        assertEquals(0.1, s.perLevel(), 1e-9);
        assertEquals(5.0, s.maxLuck(), 1e-9);
        assertEquals(LootTier.UNCOMMON, s.elitePity());
        assertNull(s.bossPity());
        assertNull(s.commonPity());
        var defaults = MobLoot.Settings.of(new YamlConfiguration());
        assertEquals(MobLoot.Settings.DEFAULT, defaults);
        assertEquals(LootTier.RARE, defaults.bossPity());
    }

    @Test void givenMobRoll_whenLevelRises_thenRareShareRises() {
        coreFiles();
        double low = rareShare(0), high = rareShare(60);
        assertTrue(high > low * 1.3, low + " -> " + high);
    }

    private static double rareShare(double level) {
        int rare = 0, total = 0;
        for (int i = 0; i < 6000; i++)
            for (var hit : LootRoll.forMob("vystorm_core:mob/zombie", level, MobLoot.Rank.COMMON).seed(i).roll().hits()) {
                total++;
                if (hit.rarity() >= LootTier.RARE.min()) rare++;
            }
        return rare / (double) Math.max(1, total);
    }

    @Test void givenPlayerLuck_whenRollingMobDrops_thenNoEffect() {
        coreFiles();
        for (int seed = 0; seed < 300; seed++) {
            var unlucky = LootRoll.forMob("category:mob_drop_common", RollContext.NONE.withSelf("vystorm_core:mob/skeleton"), 10, MobLoot.Rank.COMMON)
                    .seed(seed).roll().hits();
            var lucky = LootRoll.forMob("category:mob_drop_common", RollContext.NONE.withSelf("vystorm_core:mob/skeleton").withLuck(20), 10, MobLoot.Rank.COMMON)
                    .seed(seed).roll().hits();
            // also when the context is replaced later with a "player" context full of luck
            var replaced = LootRoll.forMob("vystorm_core:mob/skeleton", 10, MobLoot.Rank.COMMON)
                    .context(RollContext.NONE.withLuck(15).withSelf("vystorm_core:mob/skeleton")).seed(seed).roll().hits();
            assertEquals(unlucky, lucky, "seed " + seed);
            assertEquals(unlucky, replaced, "seed " + seed);
        }
    }

    @Test void givenBossRank_whenRolling_thenRarePityByDefault() {
        coreFiles();
        for (int seed = 0; seed < 200; seed++) {
            var result = LootRoll.forMob("vystorm_core:mob/zombie", 0, MobLoot.Rank.BOSS).seed(seed).roll();
            assertTrue(result.hits().stream().anyMatch(h -> h.rarity() >= LootTier.RARE.min()), "seed " + seed + " " + result.hits());
        }
        // common mobs keep their plain odds (most kills give nothing extra)
        int empty = 0;
        for (int seed = 0; seed < 200; seed++) if (LootRoll.forMob("vystorm_core:mob/zombie", 0, MobLoot.Rank.COMMON).seed(seed).roll().hits().isEmpty()) empty++;
        assertTrue(empty > 100, "empty " + empty);
    }

    @Test void givenVanillaMobTables_whenLoaded_thenEveryTableIsAnEntityTypeAndRollsCleanly() {
        coreFiles();
        Set<String> mobTables = new TreeSet<>();
        for (String id : LootTables.ids()) if (id.startsWith(MobLoot.TABLE_PREFIX)) mobTables.add(id);
        assertTrue(mobTables.size() >= 35, mobTables.toString());
        for (String id : mobTables) {
            String key = id.substring(MobLoot.TABLE_PREFIX.length());
            if (!key.equals("default")) assertDoesNotThrow(() -> EntityType.valueOf(key.toUpperCase(Locale.ROOT)), key);
            for (int seed = 0; seed < 100; seed++) {
                var result = LootRoll.forMob(id, 30, MobLoot.Rank.BOSS).seed(seed).roll();
                assertTrue(result.problems().isEmpty(), id + " " + result.problems());
                for (var hit : result.hits()) assertNotNull(Material.matchMaterial(hit.item()), id + ": " + hit.item());
            }
        }
        for (String type : List.of("zombie", "husk", "drowned", "skeleton", "stray", "bogged", "wither_skeleton", "creeper", "spider",
                "cave_spider", "enderman", "blaze", "ghast", "magma_cube", "slime", "witch", "pillager", "vindicator", "evoker", "ravager",
                "phantom", "guardian", "elder_guardian", "shulker", "piglin", "piglin_brute", "zombified_piglin", "hoglin", "breeze", "warden"))
            assertEquals(MobLoot.TABLE_PREFIX + type, MobLoot.table(EntityType.valueOf(type.toUpperCase(Locale.ROOT))));
        assertEquals(MobLoot.DEFAULT_TABLE, MobLoot.table(EntityType.COW));
        assertEquals(MobLoot.TABLE_PREFIX + "zombie", MobLoot.table("minecraft:zombie"));
    }

    @Test void givenKill_whenMarkedRolled_thenSecondRollerSkips() {
        coreFiles();
        LivingEntity zombie = mock(LivingEntity.class);
        when(zombie.getUniqueId()).thenReturn(UUID.randomUUID());
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        assertFalse(MobLoot.rolled(zombie));
        assertTrue(MobLoot.markRolled(zombie));
        assertTrue(MobLoot.rolled(zombie));
        assertFalse(MobLoot.markRolled(zombie));
        assertSame(MobLoot.Drop.NONE, MobLoot.drop(director, zombie, "", "vystorm_core:mob/zombie", 10, MobLoot.Rank.COMMON, null));
    }

    @Test void givenContribution_whenOwnerRolls_thenExtraPartRolledOnceWithLevelLuckAndNotForItself() {
        coreFiles();
        LootTables.replace(items, Map.of("vystormitems:monsters/zombie", new LootTable(1, 1, 1,
                List.of(new LootTable.Entry("minecraft:diamond", 1, 1, 9000, 9000)))));
        List<MobLoot.Kill> seen = new ArrayList<>();
        List<Integer> hits = new ArrayList<>();
        MobLoot.contribute(items, new MobLoot.Contribution() {
            @Override public List<String> tables(MobLoot.Kill kill) { seen.add(kill); return List.of("vystormitems:monsters/" + kill.type(), "unknown:table"); }
            @Override public List<ItemStack> items(MobLoot.Kill kill, LootResult part) { hits.add(part.hits().size()); return List.of(); }
        });
        LivingEntity zombie = mock(LivingEntity.class);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        var kill = new MobLoot.Kill(zombie, "zombie", 42, MobLoot.Rank.ELITE, null, "SpawnDirector");
        MobLoot.extras(director, kill, p -> fail(p));
        assertEquals(List.of(kill), seen);
        assertEquals(List.of(1), hits);
        // the contributing plugin itself rolling the kill does not add its own part twice
        MobLoot.extras(items, kill, p -> fail(p));
        assertEquals(1, seen.size());
        // a disabled contributor is skipped
        when(items.isEnabled()).thenReturn(false);
        MobLoot.extras(director, kill, p -> fail(p));
        assertEquals(1, seen.size());
    }

    @Test void givenRanks_whenMapped_thenCategoriesAndTiers() {
        assertEquals("category:mob_drop_boss", MobLoot.Rank.of(Vystorm.vystorm_core.mob.MobTier.WORLD_BOSS).category());
        assertEquals(MobLoot.Rank.ELITE, MobLoot.Rank.of(Vystorm.vystorm_core.mob.MobTier.CHAMPION));
        assertEquals(MobLoot.Rank.COMMON, MobLoot.Rank.of(Vystorm.vystorm_core.mob.MobTier.VETERAN));
        assertEquals(MobLoot.Rank.COMMON, MobLoot.Rank.of(null));
    }

    @Test void givenAdminLevelOption_whenParsed_thenMobRollWithLevel() {
        var roll = Vystorm.vystorm_core.loot.internal.LootAdminCommand.configure(LootRoll.table("x:y"), (org.bukkit.command.CommandSender) null,
                new String[]{"level=40"});
        assertEquals(40, roll.mobLevel().orElseThrow(), 1e-9);
    }
}
