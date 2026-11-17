package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.*;
import org.bukkit.Material;

/** Pure size-aware mushroom and Nether fungus geometry. @since 0.38.0 */
public final class FungusPlan {
    private FungusPlan() {}
    public static boolean supports(Material plant) {
        return plant == Material.RED_MUSHROOM || plant == Material.BROWN_MUSHROOM
                || plant == Material.CRIMSON_FUNGUS || plant == Material.WARPED_FUNGUS;
    }
    public static Map<StructurePos,Material> generate(Material plant, StructurePos origin,long seed,int width,int height,int radius) {
        Objects.requireNonNull(origin);
        if(!supports(plant) || !Set.of(1,2,3,5,10).contains(width) || height<6 || height>192 || radius<2 || radius>40)
            throw new IllegalArgumentException("Fungus requires width 1/2/3/5/10, height 6..192 and radius 2..40");
        if(width==10 && height>=32 && radius>=12) return WorldTreePlan.generate(plant,origin,seed,height,radius);
        Map<StructurePos,Material> result=new LinkedHashMap<>();
        boolean nether=plant==Material.CRIMSON_FUNGUS || plant==Material.WARPED_FUNGUS;
        boolean red=plant==Material.RED_MUSHROOM;
        Material stem=nether?(plant==Material.CRIMSON_FUNGUS?Material.CRIMSON_STEM:Material.WARPED_STEM):Material.MUSHROOM_STEM;
        Material cap= switch(plant) {
            case RED_MUSHROOM -> Material.RED_MUSHROOM_BLOCK;
            case BROWN_MUSHROOM -> Material.BROWN_MUSHROOM_BLOCK;
            case CRIMSON_FUNGUS -> Material.NETHER_WART_BLOCK;
            default -> Material.WARPED_WART_BLOCK;
        };
        int centre=width/2,top=height-1;
        int dome=nether?Math.max(2,height/5):red?Math.max(2,radius/2):1;
        // Every planted column is replaced by a supported stem, with no half-block misalignment.
        for(int y=0;y<height-1;y++)for(int x=0;x<width;x++)for(int z=0;z<width;z++)
            result.put(origin.add(x,y,z),stem);
        Random random=new Random(seed);
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            double distance=(x*x+z*z)/(double)(radius*radius);
            if(distance>1)continue;
            int surface=top-(int)Math.round(dome*distance);
            int thickness=nether?Math.max(2,dome):Math.max(1,width/2);
            for(int y=Math.max(1,surface-thickness+1);y<=surface;y++) {
                StructurePos pos=origin.add(centre+x,y,centre+z);
                if(result.get(pos)!=stem)result.put(pos,nether && y==surface-thickness+1 && random.nextInt(19)==0?Material.SHROOMLIGHT:cap);
            }
            // A few hanging strands distinguish Nether crowns from mushroom caps.
            if(nether && distance>.45 && distance<.9 && random.nextInt(35)==0) {
                int bottom=Math.max(1,surface-thickness-Math.max(2,height/5));
                if(plant==Material.CRIMSON_FUNGUS) {
                    for(int y=surface-thickness;y>bottom;y--)result.put(origin.add(centre+x,y,centre+z),Material.WEEPING_VINES_PLANT);
                    result.put(origin.add(centre+x,bottom,centre+z),Material.WEEPING_VINES);
                } else {
                    int end=Math.min(height/3,Math.max(2,width+1));
                    for(int y=0;y<end;y++)result.put(origin.add(centre+x,y,centre+z),Material.TWISTING_VINES_PLANT);
                    result.put(origin.add(centre+x,end,centre+z),Material.TWISTING_VINES);
                }
            }
        }
        result.put(origin.add(centre,top,centre),cap);
        if(result.size()>WorldTreePlan.MAX_BLOCKS)throw new IllegalArgumentException("Fungus block limit exceeded");
        return Collections.unmodifiableMap(result);
    }
}
