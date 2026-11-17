package Vystorm.vystorm_core._coreMethods.eventRegulator;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

public interface NBTInterface {

    //Autoreplace included and setting it to new values

   ItemStack setNBT(ItemStack itemStack, String key, String value);
   //Blocks get now registered in a Location database with all the nbt values.
   void setNBT(Block block, String key, String value);
   Entity setNBT(Entity entity, String key, String value);
   void setNBT(Player player, String key, String value);

   //Gets the specific key
   String getNBT(ItemStack itemStack, String key);
   String getNBT(Block block, String key);
   String getNBT(Entity entity, String key);
   String getNBT(Player player, String key);

   String removeNBT(ItemStack itemStack, String key);
   String removeNBT(Block block, String key);
   String removeNBT(Entity entity, String key);
   String removeNBT(Player player, String key);
   //First one is the Key second the value
   HashMap<String,String> getAllNBT(ItemStack itemStack);
   HashMap<String,String> getAllNBT(Block block);
   HashMap<String,String> getAllNBT(Entity entity);
   HashMap<String,String> getAllNBT(Player player);

   //Just removes the nbt set by VCORE
   ItemStack clearNBT(ItemStack itemStack);
   Block clearNBT(Block block);
   Entity clearNBT(Entity entity);
   // no clear Player as it can bug a lot

   NBTEntity getEntityNBT(String key);
   NBTEntity setEntityNBT(NBTEntity entity, String key);
   HashMap<String, NBTEntity> getEntityNBTHashMap();
    /** Explicit plugin keys for typed item data; null removes that key only. */
    default <P, C> C getTag(ItemStack item, org.bukkit.NamespacedKey key,
                            org.bukkit.persistence.PersistentDataType<P, C> type) {
        return PersistentDataHelper.get(item, key, type);
    }

    default <P, C> ItemStack setTag(ItemStack item, org.bukkit.NamespacedKey key,
                                    org.bukkit.persistence.PersistentDataType<P, C> type, C value) {
        return PersistentDataHelper.set(item, key, type, value);
    }
}
