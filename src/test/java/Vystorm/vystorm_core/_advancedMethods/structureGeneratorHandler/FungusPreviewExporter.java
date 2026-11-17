package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** Export actual exposed voxels from the shipped generator for the reviewable local preview. */
public final class FungusPreviewExporter {
    public static void main(String[] args)throws Exception {
        var config=YamlConfiguration.loadConfiguration(Path.of(args[0]).toFile());
        List<Material> palette=new ArrayList<>();StringBuilder models=new StringBuilder();
        for(Material plant:List.of(Material.RED_MUSHROOM,Material.BROWN_MUSHROOM,Material.CRIMSON_FUNGUS,Material.WARPED_FUNGUS)) {
            boolean nether=plant==Material.CRIMSON_FUNGUS || plant==Material.WARPED_FUNGUS;
            for(int width:List.of(1,2,3,5,10)) {
                String prefix="world-trees."+(width==10?"":"fungi."+width+"x"+width+".");
                int height=config.getInt(prefix+(nether?"nether":"mushroom")+"-height");
                int radius=config.getInt(prefix+(nether?"nether":"mushroom")+"-radius");
                var plan=FungusPlan.generate(plant,new StructurePos(0,0,0),123,width,height,radius);
                if(!models.isEmpty())models.append(',');
                models.append("{\"plant\":\"").append(plant).append("\",\"width\":").append(width)
                    .append(",\"height\":").append(height).append(",\"radius\":").append(radius)
                    .append(",\"count\":").append(plan.size()).append(",\"blocks\":[");
                boolean first=true;
                for(var entry:plan.entrySet()) {
                    var p=entry.getKey();int mask=0;
                    if(!plan.containsKey(p.add(1,0,0)))mask|=1;
                    if(!plan.containsKey(p.add(0,0,1)))mask|=2;
                    if(!plan.containsKey(p.add(0,1,0)))mask|=4;
                    if(mask==0)continue;
                    Material material=entry.getValue();if(!palette.contains(material))palette.add(material);
                    if(!first)models.append(',');first=false;
                    models.append('[').append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(',')
                        .append(palette.indexOf(material)).append(',').append(mask).append(']');
                }
                models.append("]}");
            }
        }
        String names=String.join(",",palette.stream().map(m->"\""+m.name()+"\"").toList());
        Path out=Path.of(args[1]);Files.createDirectories(out.getParent());
        Files.writeString(out,"{\"palette\":["+names+"],\"models\":["+models+"]}");
        System.out.println("Exported 20 real generator models to "+out);
    }
}
