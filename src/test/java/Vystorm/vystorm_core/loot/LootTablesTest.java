package Vystorm.vystorm_core.loot;

import Vystorm.vystorm_core.item.ItemRefs;
import Vystorm.vystorm_core.lifecycle.CoreLifecycle;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LootTablesTest {
    @Test void givenRarityBounds_whenRolling_thenTrashIsCommonAndMythicStillPossible() {
        var table = new LootTable(1,1,1,List.of(new LootTable.Entry("trash",1,1,0,10000),new LootTable.Entry("mythic",1,1,10000,0)));
        var random = mock(java.util.random.RandomGenerator.class);
        when(random.nextInt(1,2)).thenReturn(1);
        when(random.nextDouble(10002)).thenReturn(0.0,10001.0,10001.0);
        assertEquals("trash",table.roll(LootTable.Source.MOB,random).getFirst().item());
        assertEquals("mythic",table.roll(LootTable.Source.MOB,random).getFirst().item());
        assertEquals("mythic",table.roll(LootTable.Source.CHEST,random).getFirst().item());
        assertThrows(IllegalArgumentException.class,()->new LootTable.Entry("x",1,1,10001,0));
        assertThrows(IllegalArgumentException.class,()->new LootTable.Entry("x",1,1,-1,0));
    }

    @Test void givenSeedAndRanges_whenRolling_thenReproducibleAndBounded() {
        var table = new LootTable(2,4,1,List.of(new LootTable.Entry("iron",2,5,5000,5000)));
        for(int seed=0;seed<100;seed++) {
            var result=table.roll(LootTable.Source.MOB,new Random(seed));
            assertEquals(result,table.roll(LootTable.Source.MOB,new Random(seed)));
            assertTrue(result.size()>=2&&result.size()<=4);
            assertTrue(result.stream().allMatch(d->d.amount()>=2&&d.amount()<=5));
        }
        assertTrue(new LootTable(1,1,0,table.entries()).roll(LootTable.Source.MOB,new Random()).isEmpty());
        assertTrue(new LootTable(1,1,1,List.of(new LootTable.Entry("",1,1,0,0))).roll(LootTable.Source.MOB,new Random()).isEmpty());
    }

    @Test void givenInvalidConfig_whenParsing_thenRejectInsteadOfSilentlyChangingOdds() throws Exception {
        var yaml=new YamlConfiguration();
        yaml.loadFromString("tables:\n  'test:a':\n    entries:\n      - {item: 'minecraft:iron_ingot', weight: 9999, chest-weight: 0}\n");
        var table=LootTableConfig.parse(yaml).get("test:a");
        assertEquals(9999,table.entries().getFirst().mobWeight());
        assertEquals(0,table.entries().getFirst().chestWeight());
        yaml.set("tables.test:a.entries",List.of(Map.of("item","x","weight",10001)));
        assertThrows(IllegalArgumentException.class,()->LootTableConfig.parse(yaml));
        yaml.set("tables.test:a.entries",List.of(Map.of("item","x","weight",1.5)));
        assertThrows(IllegalArgumentException.class,()->LootTableConfig.parse(yaml));
    }

    @Test void givenRegistryAndCustomItems_whenGenerating_thenIndependentItemRollsAndLifecycleCleanup() {
        Plugin owner=mock(Plugin.class), other=mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);when(other.isEnabled()).thenReturn(true);
        var table=new LootTable(1,1,1,List.of(new LootTable.Entry("vystorm:sword",2,2,10000,10000)));
        try(var refs=mockStatic(ItemRefs.class)) {
            var first=mock(ItemStack.class);var second=mock(ItemStack.class);
            for(var item:List.of(first,second)){when(item.getType()).thenReturn(Material.DIAMOND_SWORD);when(item.getAmount()).thenReturn(1);}
            refs.when(()->ItemRefs.resolve("vystorm:sword",1)).thenReturn(Optional.of(first),Optional.of(second));
            LootTables.replace(owner,Map.of("test:items",table));
            assertThrows(IllegalArgumentException.class,()->LootTables.replace(other,Map.of("test:items",table)));
            assertEquals(List.of(first,second),LootTables.generate("test:items",LootTable.Source.CHEST,new Random(1)));
            refs.verify(()->ItemRefs.resolve("vystorm:sword",1),times(2));
            when(owner.isEnabled()).thenReturn(false);
            assertTrue(LootTables.find("test:items").isEmpty());
            when(owner.isEnabled()).thenReturn(true);
            CoreLifecycle.releaseAll(owner);
            assertTrue(LootTables.find("test:items").isEmpty());
        }finally{LootTables.release(owner);LootTables.release(other);}
    }
}
