package Vystorm.vystorm_core.blocks;

import java.lang.reflect.Proxy;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Light stand-in for {@link BlockData} in tests without a server: answers {@code getAsString}, {@code getMaterial},
 * {@code clone} (a new stand-in with the same text, so a clone is a different object) and {@code equals}/
 * {@code hashCode} by text, like the server's block data. Everything else returns null, false or 0.
 */
public final class TestBlocks {
    private TestBlocks() {}

    public static BlockData data(String text, Material material) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAsString" -> text;
            case "getMaterial" -> material;
            case "clone" -> data(text, material);
            case "equals" -> args[0] instanceof BlockData other && Proxy.isProxyClass(other.getClass()) && text.equals(other.getAsString());
            case "hashCode" -> text.hashCode();
            case "toString" -> "TestBlocks[" + text + "]";
            default -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) yield false;
                if (type == int.class) yield 0;
                yield null;
            }
        });
    }

    /** Stand-in for {@code Bukkit.createBlockData(text)}: the material is the text without namespace and properties. */
    public static BlockData data(String text) {
        String name = text.startsWith("minecraft:") ? text.substring(10) : text;
        int bracket = name.indexOf('[');
        if (bracket >= 0) name = name.substring(0, bracket);
        return data(text, Material.valueOf(name.toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * Offline stand-in for {@code Material.isSolid()} (which needs the server's registry on Paper 26): air, liquids
     * and a few plants are not solid, everything else is.
     */
    public static boolean solid(Material material) {
        String name = material.name();
        return !(material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR || material == Material.WATER
                || material == Material.LAVA || name.endsWith("GRASS") || name.endsWith("FLOWER") || name.equals("SNOW"));
    }

    public static BlockData data(Material material) { return data("minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT), material); }
}
