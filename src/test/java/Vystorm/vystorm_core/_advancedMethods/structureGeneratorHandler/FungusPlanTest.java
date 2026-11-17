package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FungusPlanTest {
    @Test void allFourFungiGrowInAllFiveSizesWithSupportedStemsAndDeterministicGeometry() {
        for(Material plant:List.of(Material.RED_MUSHROOM,Material.BROWN_MUSHROOM,Material.CRIMSON_FUNGUS,Material.WARPED_FUNGUS)) {
            int previous=0;
            for(int width:List.of(1,2,3,5,10)) {
                int height=width==10?80:width*8,radius=width==10?24:width*3;
                var origin=new StructurePos(20,64,-30);
                var plan=FungusPlan.generate(plant,origin,123,width,height,radius);
                assertEquals(plan,FungusPlan.generate(plant,origin,123,width,height,radius));
                assertTrue(plan.size()>previous,plant+" "+width);previous=plan.size();
                assertTrue(plan.size()<=WorldTreePlan.MAX_BLOCKS);
                assertEquals(64+height-1,plan.keySet().stream().mapToInt(StructurePos::y).max().orElseThrow());
                if(width<10)for(int x=0;x<width;x++)for(int z=0;z<width;z++)assertNotNull(plan.get(origin.add(x,0,z)));
                assertTrue(plan.keySet().stream().allMatch(p->p.y()>=64 && p.y()<64+height));
                assertThrows(UnsupportedOperationException.class,plan::clear);
            }
        }
    }
    @Test void invalidSizesAndNonFungiAreRejected() {
        var origin=new StructurePos(0,0,0);
        assertThrows(IllegalArgumentException.class,()->FungusPlan.generate(Material.OAK_SAPLING,origin,0,1,8,3));
        assertThrows(IllegalArgumentException.class,()->FungusPlan.generate(Material.RED_MUSHROOM,origin,0,4,8,3));
        assertThrows(IllegalArgumentException.class,()->FungusPlan.generate(Material.RED_MUSHROOM,origin,0,1,5,3));
    }
}
