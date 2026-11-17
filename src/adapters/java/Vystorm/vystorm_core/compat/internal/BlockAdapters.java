package Vystorm.vystorm_core.compat.internal;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingMoss;
import Vystorm.vystorm_core.compat.ServerCompat;

public final class BlockAdapters {
    private BlockAdapters() {}
    public static void tip(BlockData data, boolean tip) {
        if (ServerCompat.hasClass("org.bukkit.block.data.type.HangingMoss")) Modern.tip(data, tip);
    }
    private static final class Modern {
        static void tip(BlockData data, boolean tip) { if (data instanceof HangingMoss moss) moss.setTip(tip); }
    }
}
