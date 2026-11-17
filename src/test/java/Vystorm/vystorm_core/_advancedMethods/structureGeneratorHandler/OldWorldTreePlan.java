package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.*;
import org.bukkit.Material;

/** Frozen 0.38.0 (CustomTrees 1.28.1) world-tree geometry, used only for before/after previews. */
final class OldWorldTreePlan {
    public static final int MAX_BLOCKS = 120_000;
    private final Map<StructurePos, Material> blocks = new LinkedHashMap<>();
    private final StructurePos origin;
    private final int height, radius;
    private final Material wood, crown;
    private final Random random;

    public static boolean supports(Material plant) {
        return TreeSpecies.fromSapling(plant) != null || plant == Material.RED_MUSHROOM
                || plant == Material.BROWN_MUSHROOM || plant == Material.CRIMSON_FUNGUS || plant == Material.WARPED_FUNGUS;
    }

    public static Map<StructurePos, Material> generate(Material plant, StructurePos origin, long seed, int height, int radius) {
        Objects.requireNonNull(origin); Objects.requireNonNull(plant);
        if (!supports(plant) || height < 32 || height > 192 || radius < 12 || radius > 40)
            throw new IllegalArgumentException("World tree requires a supported plant, height 32..192 and crown radius 12..40");
        OldWorldTreePlan plan = new OldWorldTreePlan(plant, origin, seed, height, radius);
        plan.build(plant);
        return Collections.unmodifiableMap(plan.blocks);
    }

    private OldWorldTreePlan(Material plant, StructurePos origin, long seed, int height, int radius) {
        this.origin = origin; this.height = height; this.radius = radius; this.random = new Random(seed);
        TreeSpecies species = TreeSpecies.fromSapling(plant);
        wood = species != null ? species.log : switch (plant) {
            case CRIMSON_FUNGUS -> Material.CRIMSON_STEM;
            case WARPED_FUNGUS -> Material.WARPED_STEM;
            default -> Material.MUSHROOM_STEM;
        };
        crown = species != null ? species.leaves : switch (plant) {
            case CRIMSON_FUNGUS -> Material.NETHER_WART_BLOCK;
            case WARPED_FUNGUS -> Material.WARPED_WART_BLOCK;
            case RED_MUSHROOM -> Material.RED_MUSHROOM_BLOCK;
            default -> Material.BROWN_MUSHROOM_BLOCK;
        };
    }

    private void build(Material plant) {
        boolean mushroom = plant == Material.RED_MUSHROOM || plant == Material.BROWN_MUSHROOM;
        boolean nether = plant == Material.CRIMSON_FUNGUS || plant == Material.WARPED_FUNGUS;
        int trunkTop = mushroom ? height - Math.max(8, radius / 2) : height * 3 / 4;
        // Rounded ten-block trunk, tapering towards the crown.
        for (int y = 0; y <= trunkTop; y++) {
            double r = 4.9 - 2.4 * y / trunkTop;
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
                if (square(x - .5) + square(z - .5) <= r * r) put(x, y, z, wood);
        }
        int roots = 8;
        for (int i = 0; i < roots; i++) {
            double angle = i * Math.PI * 2 / roots + random.nextDouble() * .2;
            line(0, 8, 0, Math.cos(angle) * 12, 0, Math.sin(angle) * 12, 2.3, wood);
        }
        if (mushroom) {
            int dome = plant == Material.RED_MUSHROOM ? Math.max(8, radius / 2) : 5;
            int top = height - 1;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                double d = (square(x) + square(z)) / square(radius);
                if (d > 1) continue;
                int y = top - (int)Math.round(dome * d);
                for (int layer = 0; layer < 3; layer++) put(x, y - layer, z, crown);
            }
            // Broad central stem reaches the underside even on flat brown caps.
            line(0, trunkTop, 0, 0, top - 2, 0, 2.5, wood);
        } else if (plant == Material.SPRUCE_SAPLING) {
            for (int layer = 0; layer < 8; layer++) {
                int y = height / 3 + layer * (height * 2 / 3 - 3) / 7;
                double r = radius * (1.0 - layer / 9.0);
                ellipsoid(0, y, 0, r, Math.max(3, height / 24.0), r, crown);
            }
            line(0, trunkTop, 0, 0, height - 2, 0, 1.5, wood);
        } else {
            double phase = random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < 9; i++) {
                double angle = phase + i * Math.PI * 2 / 9;
                double reach = radius * (.55 + random.nextDouble() * .12);
                int y = height * 2 / 3 + random.nextInt(Math.max(1, height / 10));
                double x = Math.cos(angle) * reach, z = Math.sin(angle) * reach;
                line(0, height / 2.0, 0, x, y, z, 2.2, wood);
                ellipsoid(x, y, z, radius * .34, nether ? 5 : height * .09, radius * .34, crown);
                if (nether) {
                    put((int)x, y - 4, (int)z, Material.SHROOMLIGHT);
                    Material vine = plant == Material.CRIMSON_FUNGUS ? Material.WEEPING_VINES_PLANT : Material.TWISTING_VINES_PLANT;
                    if (plant == Material.CRIMSON_FUNGUS) for (int n = 5; n <= 12; n++) put((int)x, y - n, (int)z, vine);
                    else for (int n = 1; n <= 10; n++) put((int)x, n, (int)z, vine);
                }
            }
            ellipsoid(0, height * .84, 0, radius * .55, height * .15, radius * .55, crown);
        }
    }

    private void line(double x1, double y1, double z1, double x2, double y2, double z2, double width, Material material) {
        int steps = Math.max(1, (int)Math.ceil(Math.sqrt(square(x2-x1)+square(y2-y1)+square(z2-z1)) * 1.5));
        for (int i = 0; i <= steps; i++) {
            double t = (double)i / steps;
            ellipsoid(x1+(x2-x1)*t, y1+(y2-y1)*t, z1+(z2-z1)*t, width*(1-.6*t), width*(1-.6*t), width*(1-.6*t), material);
        }
    }

    private void ellipsoid(double cx, double cy, double cz, double rx, double ry, double rz, Material material) {
        for (int x = (int)Math.floor(cx-rx); x <= Math.ceil(cx+rx); x++)
            for (int y = (int)Math.floor(cy-ry); y <= Math.ceil(cy+ry); y++)
                for (int z = (int)Math.floor(cz-rz); z <= Math.ceil(cz+rz); z++)
                    if (square((x-cx)/rx)+square((y-cy)/ry)+square((z-cz)/rz) <= 1) put(x,y,z,material);
    }

    private void put(int x, int y, int z, Material material) {
        if (y < 0 || y >= height || Math.abs(x) > Math.max(14, radius) || Math.abs(z) > Math.max(14, radius)) return;
        StructurePos pos = origin.add(4+x, y, 4+z);
        // Crowns do not replace their supporting branches.
        if (blocks.get(pos) == wood && material != wood) return;
        blocks.put(pos, material);
        if (blocks.size() > MAX_BLOCKS) throw new IllegalArgumentException("World tree exceeds " + MAX_BLOCKS + " blocks");
    }
    private static double square(double value) { return value * value; }
}
