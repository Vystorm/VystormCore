import Vystorm.vystorm_core.compat.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import java.lang.reflect.*;
import java.util.*;

/** Run only in a disposable local test server with Core installed. No real account is used. */
public final class CoreNativeSmoke extends JavaPlugin {
    public void onEnable() { Bukkit.getScheduler().runTaskLater(this,this::probe,30); }
    private void check(boolean condition,String label) { if(!condition)throw new AssertionError(label);getLogger().info("CORE_NATIVE_OK "+label); }
    private void probe() {
        try {
            var board=Bukkit.getScoreboardManager().getNewScoreboard();
            var objective=PlatformCompat.registerNewObjective(board,"core_probe",Criteria.DUMMY,Component.text("Core probe"));
            var score=objective.getScore("entry");score.setScore(1);
            PlatformCompat.customName(score,Component.text("Portable score"));PlatformCompat.blankNumberFormat(objective);
            check(objective.getDisplayName().equals("Core probe"),"scoreboard objective");
            Method objectiveHandle=objective.getClass().getDeclaredMethod("getHandle");objectiveHandle.setAccessible(true);
            Object nativeObjective=objectiveHandle.invoke(objective);
            check(nativeObjective.getClass().getMethod("numberFormat").invoke(nativeObjective).getClass().getSimpleName().equals("BlankFormat"),"blank score numbers");
            Field entry=score.getClass().getDeclaredField("entry");entry.setAccessible(true);
            Object nativeBoard=board.getClass().getMethod("getHandle").invoke(board);
            Object info=nativeBoard.getClass().getMethod("getPlayerScoreInfo",Class.forName("net.minecraft.world.scores.ScoreHolder"),nativeObjective.getClass()).invoke(nativeBoard,entry.get(score),nativeObjective);
            Object display=info.getClass().getMethod("display").invoke(info);
            check(display.toString().contains("Portable score"),"score custom display");
            ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
            PlatformCompat.editMeta(sword,meta->{PlatformCompat.displayName(meta,Component.text("Probe sword"));meta.getPersistentDataContainer().set(new NamespacedKey(this,"probe"),PersistentDataType.STRING,"native-hover-marker");});
            ItemStack first=PlatformCompat.enchantWithLevels(sword,30,false,new Random(771));
            ItemStack second=PlatformCompat.enchantWithLevels(sword,30,false,new Random(771));
            check(!first.getEnchantments().isEmpty() && first.isSimilar(second),"seeded vanilla enchanting");
            String hover=GsonComponentSerializer.gson().serialize(PlatformCompat.displayName(first));
            check(hover.contains("show_item") && hover.contains("native-hover-marker"),"native item hover components");
            ItemStack[] decoded=ItemCompat.deserialize(ItemCompat.serialize(new ItemStack[]{first,null,new ItemStack(Material.AIR)}));
            check(decoded.length==3 && decoded[0].isSimilar(first) && PlatformCompat.isEmpty(decoded[1]) && PlatformCompat.isEmpty(decoded[2]),"inventory array roundtrip");
            profile();
            getLogger().info("CORE_NATIVE_PASSED");
        } catch(Throwable failure) { getLogger().log(java.util.logging.Level.SEVERE,"CORE_NATIVE_FAILED",failure); }
        finally { Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,10); }
    }
    private void profile()throws Exception {
        Object server=Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object level=Bukkit.getWorlds().getFirst().getClass().getMethod("getHandle").invoke(Bukkit.getWorlds().getFirst());
        String skin="https://textures.minecraft.net/texture/0123456789abcdef";
        String payload=Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\""+skin+"\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var properties=com.google.common.collect.ArrayListMultimap.<String,com.mojang.authlib.properties.Property>create();
        properties.put("textures",new com.mojang.authlib.properties.Property("textures",payload));
        var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"CoreProbe",new com.mojang.authlib.properties.PropertyMap(properties));
        Class<?> playerType=Class.forName("net.minecraft.server.level.ServerPlayer"),clientType=Class.forName("net.minecraft.server.level.ClientInformation");
        Object client=clientType.getMethod("createDefault").invoke(null);
        Object nativePlayer=playerType.getConstructor(Class.forName("net.minecraft.server.MinecraftServer"),Class.forName("net.minecraft.server.level.ServerLevel"),com.mojang.authlib.GameProfile.class,clientType).newInstance(server,level,profile,client);
        Player player=(Player)playerType.getMethod("getBukkitEntity").invoke(nativePlayer);
        check(PlatformCompat.getPlayerProfile(player).getTextures().getSkin().toString().equals(skin),"live profile skin properties");
    }
}
