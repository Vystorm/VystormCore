package Vystorm.vystorm_core.loot;

import java.io.File;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LootRollTest {
    private final Plugin core = plugin("Vystorm_Core"), other = plugin("Other");

    private static Plugin plugin(String name) {
        Plugin p = mock(Plugin.class);
        when(p.isEnabled()).thenReturn(true);
        when(p.getName()).thenReturn(name);
        return p;
    }

    @AfterEach void cleanup() { LootTables.release(core); LootTables.release(other); }

    private static LootTable.Entry e(String item, int weight) { return new LootTable.Entry(item, 1, 1, weight, weight); }

    private static LootTable tiers() {
        return new LootTable(1, 1, 1, List.of(e("common", 0), e("uncommon", 5000), e("rare", 8000), e("epic", 9500), e("legendary", 9900)));
    }

    private static Map<String, LootTable> coreFiles() {
        Map<String, LootTable> all = new LinkedHashMap<>();
        for (String name : LootTableConfig.CORE_FILES) {
            var parsed = LootTableConfig.parse(YamlConfiguration.loadConfiguration(new File("src/main/resources/" + name)));
            assertFalse(parsed.isEmpty(), name);
            for (var entry : parsed.entrySet()) assertNull(all.put(entry.getKey(), entry.getValue()), "duplicate " + entry.getKey());
        }
        return all;
    }

    @Test void givenPlainTable_whenRollingThroughLootRoll_thenSameDrawsAsLegacyRoll() {
        var table = new LootTable(1, 4, 0.8, List.of(new LootTable.Entry("a", 1, 3, 0, 0), e("b", 5000), e("", 7000), e("c", 9999)));
        for (int seed = 0; seed < 200; seed++) {
            var legacy = table.roll(LootTable.Source.MOB, new Random(seed));
            var hits = LootRoll.of(table).source(LootTable.Source.MOB).random(new Random(seed)).roll().hits();
            assertEquals(legacy, hits.stream().map(h -> new LootTable.Drop(h.item(), h.amount())).toList(), "seed " + seed);
        }
    }

    @Test void givenSeed_whenRollingTwice_thenIdentical() {
        var a = LootRoll.of(tiers()).rolls(10).seed(42).roll().hits();
        var b = LootRoll.of(tiers()).rolls(10).seed(42).roll().hits();
        assertEquals(a, b);
        assertEquals(10, a.size());
    }

    @Test void givenLuck_whenSimulating_thenRareShareRises() {
        double plain = share(0), lucky = share(4), unlucky = share(-2);
        assertTrue(lucky > plain * 1.3, plain + " -> " + lucky);
        assertTrue(unlucky < plain, plain + " -> " + unlucky);
    }

    private static double share(double luck) {
        int rare = 0, total = 0;
        for (int i = 0; i < 4000; i++) for (var hit : LootRoll.of(tiers()).luck(luck).seed(i).roll().hits()) {
            total++;
            if (hit.tier().compareTo(LootTier.RARE) >= 0) rare++;
        }
        return rare / (double) total;
    }

    @Test void givenTierFilters_whenRolling_thenOnlyMatchingEntriesAndNoEmptyDraws() {
        var table = new LootTable(3, 3, 1, List.of(e("common", 0), e("", 0), e("rare", 8000), e("epic", 9500)));
        for (int i = 0; i < 300; i++) {
            var exact = LootRoll.of(table).tier(LootTier.RARE).seed(i).roll().hits();
            assertEquals(3, exact.size());
            assertTrue(exact.stream().allMatch(h -> h.item().equals("rare")));
            assertTrue(LootRoll.of(table).minTier(LootTier.RARE).seed(i).roll().hits().stream().allMatch(h -> h.rarity() >= 7000));
        }
    }

    @Test void givenPity_whenNoHitReachesTier_thenLowestHitIsReplacedWithGuaranteedTier() {
        var table = new LootTable(3, 3, 1, List.of(e("junk", 0), e("epic", 9700)));
        for (int i = 0; i < 300; i++) {
            var result = LootRoll.of(table).guarantee(LootTier.EPIC).seed(i).roll();
            assertEquals(3, result.hits().size());
            assertTrue(result.hits().stream().anyMatch(h -> h.item().equals("epic")), "seed " + i);
            assertEquals(LootTier.EPIC, result.bestTier().orElseThrow());
        }
        var gated = new LootTable(1, 1, 0, table.entries());
        assertEquals("epic", LootRoll.of(gated).guarantee(LootTier.EPIC).seed(1).roll().hits().getFirst().item(), "pity ignores the table chance");
    }

    @Test void givenUniqueAndExclude_whenRolling_thenNoDuplicatesAndExcludedNeverDrops() {
        var table = new LootTable(4, 4, 1, List.of(e("a", 0), e("b", 0), e("c", 0), e("d", 0), e("x", 0)));
        for (int i = 0; i < 200; i++) {
            var hits = LootRoll.of(table).unique(true).exclude(List.of("x")).seed(i).roll().hits();
            assertEquals(4, hits.size());
            assertEquals(4, new HashSet<>(hits.stream().map(LootResult.Hit::item).toList()).size());
            assertTrue(hits.stream().noneMatch(h -> h.item().equals("x")));
        }
    }

    @Test void givenConditions_whenContextKnown_thenOnlyAllowedEntries() {
        var deep = new LootTable.Options(0, 0, false, "", new LootTable.Conditions(null, 0, Set.of("normal"), Set.of(), null, null), false);
        var table = new LootTable(1, 1, 1, List.of(new LootTable.Entry("deep", 1, 1, 0, 0, deep), e("any", 9000)));
        RollContext high = RollContext.NONE.withY(64).withEnvironment("NORMAL"), low = RollContext.NONE.withY(-20).withEnvironment("NORMAL");
        for (int i = 0; i < 100; i++) assertEquals("any", LootRoll.of(table).context(high).seed(i).roll().hits().getFirst().item());
        assertTrue(java.util.stream.IntStream.range(0, 100).anyMatch(i -> LootRoll.of(table).context(low).seed(i).roll().hits().getFirst().item().equals("deep")));
        assertTrue(java.util.stream.IntStream.range(0, 100).allMatch(i -> !LootRoll.of(table).context(low.withEnvironment("NETHER")).seed(i).roll().hits().getFirst().item().equals("deep")));
    }

    @Test void givenCategory_whenSelfRegistered_thenOnlyOwnTableElseFallback() {
        LootTables.replace(core, coreFiles());
        LootTables.replace(other, Map.of("other:boss", new LootTable(2, 2, 1, List.of(e("other:gem", 9000)))));
        for (int i = 0; i < 100; i++) {
            var own = LootRoll.table("category:boss_chest").context(RollContext.NONE.withSelf("other:boss")).seed(i).roll();
            assertEquals(2, own.hits().size());
            assertTrue(own.hits().stream().allMatch(h -> h.item().equals("other:gem") && h.table().equals("other:boss")), own.toString());
            var fallback = LootRoll.table("category:boss_chest").seed(i).roll();
            assertTrue(fallback.problems().isEmpty(), fallback.problems().toString());
            assertTrue(fallback.hits().size() >= 4, "boss chest fallback draws 4-7");
            assertTrue(fallback.hits().stream().allMatch(h -> h.item().startsWith("minecraft:")));
        }
        // Per-source override wins over the default category; unknown overrides fall back.
        assertEquals("other:boss", LootTables.choose("other:boss", "category:boss_chest"));
        assertEquals("category:boss_chest", LootTables.choose("nope:missing", "category:boss_chest"));
        assertEquals("category:boss_chest", LootTables.choose(" ", "category:boss_chest"));
        assertEquals("category:boss_chest", LootTables.choose("no namespace", "category:boss_chest"));
    }

    @Test void givenCoreFiles_whenLoaded_thenEveryCategoryRollsAndEveryItemIsVanilla() {
        Map<String, LootTable> all = coreFiles();
        LootTables.replace(core, all);
        Set<String> categories = new TreeSet<>();
        for (var entry : all.entrySet()) {
            if (entry.getKey().startsWith("category:")) categories.add(entry.getKey());
            for (LootTable.Entry item : entry.getValue().entries()) {
                if (item.tableRef()) {
                    if (!item.tableId().equals("self")) assertTrue(all.containsKey(item.tableId()), entry.getKey() + " -> " + item.item());
                    continue;
                }
                if (item.item().isEmpty()) continue;
                assertTrue(item.item().startsWith("minecraft:"), item.item());
                Material material = Material.matchMaterial(item.item());
                assertNotNull(material, entry.getKey() + ": " + item.item());
                assertFalse(material.isLegacy(), item.item());
                boolean gear = material.name().matches(".*_(SWORD|PICKAXE|AXE|SHOVEL|HOE|HELMET|CHESTPLATE|LEGGINGS|BOOTS)|BOW|CROSSBOW|FISHING_ROD|TRIDENT|MACE|SHIELD|ELYTRA|SADDLE|TOTEM_OF_UNDYING|NAME_TAG.*|.*POTION|MUSIC_DISC_.*|BEETROOT_SOUP|SPYGLASS|LEAD|BUCKET|TURTLE_HELMET");
                if (item.options().minEnchantLevel() > 0) assertTrue(gear || material == Material.BOOK, item.item());
                if (!item.options().potion().isEmpty()) assertTrue(material.name().endsWith("POTION") || material == Material.TIPPED_ARROW);
                if (gear && material != Material.LEAD) assertEquals(1, item.maxAmount(), "unstackable " + item.item());
            }
        }
        assertTrue(categories.containsAll(List.of("category:boss_chest", "category:dungeon_chest", "category:fishing", "category:expedition",
                "category:mob_drop_common", "category:mob_drop_elite", "category:mob_drop_boss", "category:quest_reward", "category:contract_reward",
                "category:arena_reward", "category:farm_rare_find", "category:shrine", "category:meteor", "category:sky_temple", "category:sky_wreck",
                "category:cave_loot_room", "category:mineshaft_cart", "category:treasure_vault")), categories.toString());
        for (String id : categories) for (int i = 0; i < 50; i++) {
            var result = LootRoll.table(id).seed(i).roll();
            assertTrue(result.problems().isEmpty(), id + " " + result.problems());
        }
        for (String tier : List.of("common", "uncommon", "rare", "epic", "legendary")) {
            LootTier expected = LootTier.valueOf(tier.toUpperCase(Locale.ROOT));
            for (var entry : all.get("vystorm_core:vanilla/" + tier).entries())
                assertEquals(expected, entry.tier(LootTable.Source.CHEST), tier + ": " + entry.item());
        }
    }

    @Test void givenEarlySources_whenRollingDefaults_thenNoLegendaryLoot() {
        LootTables.replace(core, coreFiles());
        for (String id : List.of("category:expedition", "category:mob_drop_common", "category:farm_rare_find", "category:mineshaft_cart", "category:fishing"))
            for (int i = 0; i < 2000; i++)
                for (var hit : LootRoll.table(id).seed(i).roll().hits())
                    assertNotEquals("vystorm_core:vanilla/legendary", hit.table(), id);
    }

    @Test void givenCycleOrUnknownReference_whenRolling_thenProblemInsteadOfLoop() {
        LootTables.replace(other, Map.of(
                "t:a", new LootTable(1, 1, 1, List.of(e("table:t:b", 0))),
                "t:b", new LootTable(1, 1, 1, List.of(e("table:t:a", 0)))));
        var result = LootRoll.table("t:a").seed(1).roll();
        assertTrue(result.hits().isEmpty());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("cycle")), result.problems().toString());
        assertFalse(LootRoll.table("t:missing").roll().problems().isEmpty());
    }

    @Test void givenLegacyRelativeWeights_whenConverted_thenOddsKept() {
        double[] weights = {60, 30, 9, 1};
        int[] ranks = LootTable.ranksFor(weights);
        assertEquals(0, ranks[0]);
        double total = 0;
        for (int r : ranks) total += 10001 - r;
        for (int i = 0; i < weights.length; i++) assertEquals(weights[i] / 100, (10001 - ranks[i]) / total, 0.001);
    }

    @Test void givenAdminOptions_whenParsed_thenApplied() {
        var sender = mock(org.bukkit.command.CommandSender.class);
        var roll = Vystorm.vystorm_core.loot.internal.LootAdminCommand.configure(LootRoll.of(tiers()), sender,
                new String[]{"tier=rare", "rolls=5", "seed=7", "luck=1"});
        var hits = roll.roll().hits();
        assertEquals(5, hits.size());
        assertTrue(hits.stream().allMatch(h -> h.tier() == LootTier.RARE));
        assertThrows(IllegalArgumentException.class, () -> Vystorm.vystorm_core.loot.internal.LootAdminCommand.configure(LootRoll.of(tiers()), sender, new String[]{"bogus=1"}));
    }

    @Test void givenEachModeTable_whenRolling_thenEntriesDropIndependentlyWithTheirChance() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("tables:\n  'm:drops':\n    mode: each\n    entries:\n"
                + "      - {item: 'minecraft:bone', chance: 0.5, min-amount: 1, max-amount: 2}\n"
                + "      - {item: 'minecraft:diamond', chance: 0.1, weight: 9000}\n"
                + "      - {item: 'minecraft:stick'}\n");
        LootTable table = LootTableConfig.parse(yaml).get("m:drops");
        assertTrue(table.each());
        int bones = 0, diamonds = 0, sticks = 0, n = 20000;
        for (int i = 0; i < n; i++) for (var hit : LootRoll.of(table).seed(i).roll().hits()) switch (hit.item()) {
            case "minecraft:bone" -> bones++;
            case "minecraft:diamond" -> diamonds++;
            default -> sticks++;
        }
        assertEquals(0.5, bones / (double) n, 0.02);
        assertEquals(0.1, diamonds / (double) n, 0.01);
        assertEquals(n, sticks);
        int lucky = 0;
        for (int i = 0; i < n; i++) lucky += (int) LootRoll.of(table).luck(4).seed(i).roll().hits().stream().filter(h -> h.item().equals("minecraft:diamond")).count();
        assertTrue(lucky > diamonds * 1.5, diamonds + " -> " + lucky);
        for (int i = 0; i < 50; i++) assertTrue(LootRoll.of(table).guarantee(LootTier.RARE).seed(i).roll().hits().stream().anyMatch(h -> h.item().equals("minecraft:diamond")));
        yaml.set("tables.m:drops.mode", "bogus");
        assertThrows(IllegalArgumentException.class, () -> LootTableConfig.parse(yaml));
    }
}
