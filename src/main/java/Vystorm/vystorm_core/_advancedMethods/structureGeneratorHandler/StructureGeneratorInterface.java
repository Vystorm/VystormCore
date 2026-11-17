package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.Random;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Structure registry and bounded generation helpers. Callers choose a thread appropriate for
 * their access implementation, including a Paper {@code LimitedRegion}; no scheduler or world
 * ownership is assumed. Removing an owner prevents future callbacks only, not one already running.
 */
public interface StructureGeneratorInterface {
    /** Pure size-aware fungus plan, with no world access. @since 0.38.0 */
    default java.util.Map<StructurePos, org.bukkit.Material> fungus(org.bukkit.Material plant,
            StructurePos origin, long seed, int width, int height, int radius) {
        return FungusPlan.generate(plant, origin, seed, width, height, radius);
    }
    /** Pure bounded 10x10 tree/mushroom/Nether-fungus plan, with no world access. @since 0.37.0 */
    default java.util.Map<StructurePos, org.bukkit.Material> worldTree(org.bukkit.Material plant,
            StructurePos origin, long seed, int height, int radius) {
        return WorldTreePlan.generate(plant, origin, seed, height, radius);
    }
    TreeGenerator tree(StructureAccess access, Random random, TreeSpecies species);
    /** Additive profile overload. Older implementations may reject non-default profiles. @since 0.26.5 */
    default TreeGenerator tree(StructureAccess access, Random random, TreeSpecies species, TreeGrowthProfile profile) {
        if (!TreeGrowthProfile.DEFAULT.equals(profile)) throw new UnsupportedOperationException("Tree profiles unavailable");
        return tree(access, random, species);
    }
    int heightBonus(TreeSpecies species, int width);
    int decorateGlowstone(StructureAccess access, Set<StructurePos> changedPositions,
                          TreeSpecies species, Random random, int leavesPerBlock,
                          int maxBlocks, int darkLightLevel);
    void register(Plugin owner, NamespacedKey id, StructureGenerator generator);
    boolean unregister(Plugin owner, NamespacedKey id);
    boolean generate(NamespacedKey id, StructureAccess access, StructurePos origin, Random random);
    Set<NamespacedKey> registeredNames();
    void removeAll(Plugin owner);
    void clear();

    /** Whether {@link #fungus} can grow this plant ({@link FungusPlan#supports}). @since 0.64.0 */
    default boolean supportsFungus(org.bukkit.Material plant) { return FungusPlan.supports(plant); }

    /** Whether {@link #worldTree} can grow this plant ({@link WorldTreePlan#supports}). @since 0.64.0 */
    default boolean supportsWorldTree(org.bukkit.Material plant) { return WorldTreePlan.supports(plant); }

    /** Horizontal half-extent of a world tree plan ({@link WorldTreePlan#footprint}). @since 0.64.0 */
    default int worldTreeFootprint(org.bukkit.Material plant, int height, int radius) { return WorldTreePlan.footprint(plant, height, radius); }

    /** Blocks below the planting level a world tree plan may reach ({@link WorldTreePlan#depth}). @since 0.64.0 */
    default int worldTreeDepth(org.bukkit.Material plant, int height, int radius) { return WorldTreePlan.depth(plant, height, radius); }
}
