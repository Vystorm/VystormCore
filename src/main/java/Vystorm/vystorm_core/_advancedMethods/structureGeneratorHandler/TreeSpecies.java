package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;

public enum TreeSpecies {
    OAK(Material.OAK_SAPLING, Material.OAK_LOG, Material.OAK_LEAVES, Shape.ROUND, 5, 11),
    BIRCH(Material.BIRCH_SAPLING, Material.BIRCH_LOG, Material.BIRCH_LEAVES, Shape.ROUND, 6, 10),
    SPRUCE(Material.SPRUCE_SAPLING, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, Shape.CONIFER, 7, 12),
    JUNGLE(Material.JUNGLE_SAPLING, Material.JUNGLE_LOG, Material.JUNGLE_LEAVES, Shape.ROUND, 17, 26),
    ACACIA(Material.ACACIA_SAPLING, Material.ACACIA_LOG, Material.ACACIA_LEAVES, Shape.UMBRELLA, 10, 15),
    DARK_OAK(Material.DARK_OAK_SAPLING, Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES, Shape.ROUND, 6, 9),
    MANGROVE(Material.MANGROVE_PROPAGULE, Material.MANGROVE_LOG, Material.MANGROVE_LEAVES, Shape.MANGROVE, 6, 10),
    CHERRY(Material.CHERRY_SAPLING, Material.CHERRY_LOG, Material.CHERRY_LEAVES, Shape.UMBRELLA, 11, 16),
    PALE_OAK(Vystorm.vystorm_core.compat.ServerCompat.material("PALE_OAK_SAPLING", Material.OAK_SAPLING), Vystorm.vystorm_core.compat.ServerCompat.material("PALE_OAK_LOG", Material.OAK_LOG), Vystorm.vystorm_core.compat.ServerCompat.material("PALE_OAK_LEAVES", Material.OAK_LEAVES), Shape.ROUND, 7, 10);

    private static final Set<Material> ALL_LOGS = EnumSet.noneOf(Material.class);
    private static final Set<Material> ALL_LEAVES = EnumSet.noneOf(Material.class);
    static { for (TreeSpecies species : values()) { ALL_LOGS.add(species.log); ALL_LEAVES.add(species.leaves); } }

    public final Material sapling;
    public final Material log;
    public final Material leaves;
    public final Shape shape;
    public final int minHeight;
    public final int maxHeight;

    TreeSpecies(Material sapling, Material log, Material leaves, Shape shape, int minHeight, int maxHeight) {
        this.sapling = sapling; this.log = log; this.leaves = leaves; this.shape = shape;
        this.minHeight = minHeight; this.maxHeight = maxHeight;
    }
    public static TreeSpecies fromLog(Material material) { for (TreeSpecies species : values()) if (species.log == material) return species; return null; }
    public static TreeSpecies fromSapling(Material material) { for (TreeSpecies species : values()) if (species.sapling == material) return species; return null; }
    public static boolean isLog(Material material) { return ALL_LOGS.contains(material); }
    public static boolean isLeaves(Material material) { return ALL_LEAVES.contains(material); }

    public enum Shape { ROUND, CONIFER, UMBRELLA, MANGROVE }
}
