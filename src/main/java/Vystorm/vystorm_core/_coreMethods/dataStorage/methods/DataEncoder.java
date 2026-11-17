package Vystorm.vystorm_core._coreMethods.dataStorage.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.inventory.ItemStack;
import java.util.Base64;
import java.util.Objects;

public class DataEncoder {
    public int decodeInt(String data){
        DebugMessages.low("[DataEncoder] decodeInt requested");
       data =  data.replace("INT::","");
       return Integer.parseInt(data);
    }
    public String encodeInt(int data){
        DebugMessages.low("[DataEncoder] encodeInt requested");
        return "INT::"+data;
    }
    public String decodeString(String data){
        DebugMessages.low("[DataEncoder] decodeString requested");
        data = data.replace("STR::", "");
        return data;
    }
    public String encodeString(String data){
        DebugMessages.low("[DataEncoder] encodeString requested");
        return "STR::"+data;
    }
    public String decodeBoolean(String data){
        DebugMessages.low("[DataEncoder] decodeBoolean requested");
        data = data.replace("BOOL::", "");
        return data;
    }
    public String encodeBoolean(String data){
        DebugMessages.low("[DataEncoder] encodeBoolean requested");
        return "BOOL::"+data;
    }
    /** Native item NBT retains components, custom data and the Minecraft data version. */
    public String encodeItem(ItemStack item) {
        DebugMessages.low("[DataEncoder] encodeItem requested");
        return Base64.getEncoder().encodeToString(
                Vystorm.vystorm_core.compat.StorageCompat.serializeAsBytes(Objects.requireNonNull(item, "item")));
    }

    public ItemStack decodeItem(String data) {
        DebugMessages.low("[DataEncoder] decodeItem requested");
        try {
            return Vystorm.vystorm_core.compat.StorageCompat.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (RuntimeException failure) {
            DebugMessages.high("[DataEncoder] Native item decoding failed: " + failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Preserves slot order and empty slots; use storage contents to exclude armor/offhand. */
    public String encodeInventory(ItemStack[] contents) {
        DebugMessages.low("[DataEncoder] encodeInventory requested");
        return Base64.getEncoder().encodeToString(
                Vystorm.vystorm_core.compat.ItemCompat.serialize(contents));
    }

    public ItemStack[] decodeInventory(String data) {
        DebugMessages.low("[DataEncoder] decodeInventory requested");
        try {
            return Vystorm.vystorm_core.compat.ItemCompat.deserialize(Base64.getDecoder().decode(data));
        } catch (RuntimeException failure) {
            DebugMessages.high("[DataEncoder] Native item decoding failed: " + failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
