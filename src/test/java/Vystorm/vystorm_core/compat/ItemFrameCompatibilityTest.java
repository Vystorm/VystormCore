package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class ItemFrameCompatibilityTest {
    private static byte[] frame(boolean oldCore,int count,int length,byte[] item,boolean trailing)throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        if(oldCore)out.writeInt(0x56434931);else out.writeByte(1);
        out.writeInt(count);if(count>0){out.writeInt(length);out.write(item);}if(trailing)out.writeByte(0);
        return bytes.toByteArray();
    }
    @Test void givenPaperAndLegacyCoreArraysWhenReadOnBukkitThenSlotsAndNativeItemDataArePreserved()throws Exception {
        ItemStack item=mock(ItemStack.class);byte[] nbt={1,2,3,4};
        try(var storage=mockStatic(StorageCompat.class)) {
            storage.when(()->StorageCompat.deserializeBytes(nbt)).thenReturn(item);
            for(boolean old:new boolean[]{false,true})assertArrayEquals(new ItemStack[]{item},ItemCompat.deserializeFrame(frame(old,1,nbt.length,nbt,false)));
        }
    }
    @Test void givenEmptyPaperSlotWhenReadThenSlotIsNotDropped()throws Exception {
        ItemStack air=mock(ItemStack.class);
        try(var platform=mockStatic(PlatformCompat.class)) {
            platform.when(PlatformCompat::emptyItem).thenReturn(air);
            assertArrayEquals(new ItemStack[]{air},ItemCompat.deserializeFrame(frame(false,1,0,new byte[0],false)));
        }
    }
    @Test void givenCorruptArrayWhenReadThenItFailsBeforeRestoringPartialInventory()throws Exception {
        for(byte[] data:new byte[][]{new byte[0],new byte[]{2},frame(false,-1,0,new byte[0],false),
                frame(false,100001,0,new byte[0],false),frame(false,1,-1,new byte[0],false),
                frame(false,1,8,new byte[]{1},false),frame(false,0,0,new byte[0],true)})
            assertThrows(UncheckedIOException.class,()->ItemCompat.deserializeFrame(data));
    }
}
