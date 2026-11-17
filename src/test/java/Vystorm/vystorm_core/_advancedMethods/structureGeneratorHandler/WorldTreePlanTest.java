package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldTreePlanTest {
    private static final List<Material> FUNGI = List.of(Material.RED_MUSHROOM, Material.BROWN_MUSHROOM, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS);

    @Test void givenEverySaplingAtMinDefaultAndMaxSizeThenPlanIsDeterministicBoundedAndUnclipped() {
        var origin = new StructurePos(-30, 64, 20);
        for (TreeSpecies species : TreeSpecies.values())
            for (int[] size : new int[][]{{32, 12}, {160, 44}, {192, 48}, {96, 24}})
                for (long seed : List.of(0L, 42L, -7L)) {
                    String label = species + " " + Arrays.toString(size) + " seed " + seed;
                    var tree = new GiantTree(species, seed, size[0], size[1]).build();
                    assertEquals(0, tree.clipped, "clipped blocks: " + label);
                    var plan = WorldTreePlan.generate(species.sapling, origin, seed, size[0], size[1]);
                    assertEquals(plan, WorldTreePlan.generate(species.sapling, origin, seed, size[0], size[1]), label);
                    assertTrue(plan.size() <= WorldTreePlan.MAX_BLOCKS, label);
                    assertTrue(plan.size() > 1500, label);
                    int f = WorldTreePlan.footprint(species.sapling, size[0], size[1]);
                    int deep = WorldTreePlan.depth(species.sapling, size[0], size[1]);
                    assertTrue(deep >= 6 && deep <= 14, label);
                    assertTrue(plan.keySet().stream().allMatch(p -> p.y() >= 64 - deep && p.y() < 64 + size[0]
                            && Math.abs(p.x() - (origin.x() + 5)) <= f && Math.abs(p.z() - (origin.z() + 5)) <= f), label);
                    assertTrue(plan.keySet().stream().mapToInt(StructurePos::y).max().orElseThrow() >= 64 + size[0] - 6, label);
                    assertTrue(plan.containsValue(species.leaves), label);
                    assertTrue(plan.containsValue(species.log), label);
                    assertThrows(UnsupportedOperationException.class, plan::clear);
                }
    }

    @Test void givenDefaultSizeThenRootsArchFarOutAndCrownSpreadsToItsRadius() {
        for (TreeSpecies species : TreeSpecies.values()) {
            var plan = WorldTreePlan.generate(species.sapling, new StructurePos(0, 0, 0), 123, 160, 44);
            double rootReach = 0, crownReach = 0;
            for (var e : plan.entrySet()) {
                var p = e.getKey(); double d = Math.hypot(p.x() - 5, p.z() - 5);
                Material m = e.getValue();
                if (p.y() <= 3 && (m.name().endsWith("_LOG") || m.name().endsWith("_WOOD") || m == Material.MANGROVE_ROOTS)) rootReach = Math.max(rootReach, d);
                if (m == species.leaves) crownReach = Math.max(crownReach, d);
            }
            assertTrue(rootReach >= 18, species + " root reach " + rootReach);
            assertTrue(crownReach >= 32, species + " crown reach " + crownReach);
            // Massive base: well beyond the 10x10 sapling square.
            long ground = plan.entrySet().stream().filter(e -> e.getKey().y() == 2 && Math.hypot(e.getKey().x() - 5, e.getKey().z() - 5) <= 9).count();
            assertTrue(ground >= 120 || species == TreeSpecies.MANGROVE, species + " base " + ground);
        }
    }

    @Test void givenMaximumSettingsThenEverySpeciesPlansWellUnderOneSecond() {
        for (TreeSpecies species : TreeSpecies.values()) WorldTreePlan.generate(species.sapling, new StructurePos(0, 0, 0), 1, 192, 48);
        for (TreeSpecies species : TreeSpecies.values()) {
            long start = System.nanoTime();
            var plan = WorldTreePlan.generate(species.sapling, new StructurePos(0, 0, 0), 99, 192, 48);
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 1000, species + " took " + millis + " ms for " + plan.size() + " blocks");
        }
    }

    @Test void givenEverySaplingThenRootsDigUndergroundAsWoodOnlyAndFungiStayAboveGround() {
        var origin = new StructurePos(0, 64, 0);
        for (TreeSpecies species : TreeSpecies.values()) for (int[] size : new int[][]{{32, 12}, {160, 44}, {192, 48}}) {
            var plan = WorldTreePlan.generate(species.sapling, origin, 11, size[0], size[1]);
            int deep = WorldTreePlan.depth(species.sapling, size[0], size[1]);
            String label = species + " " + Arrays.toString(size);
            var below = plan.entrySet().stream().filter(e -> e.getKey().y() < 64).toList();
            assertTrue(below.stream().allMatch(e -> e.getValue() == species.log || e.getValue() == Material.MANGROVE_ROOTS
                    || e.getValue().name().endsWith("_WOOD")), label + " only wood underground");
            int lowest = below.stream().mapToInt(e -> e.getKey().y()).min().orElse(64);
            assertTrue(lowest <= 64 - deep + 2, label + " deepest root " + (64 - lowest) + " of " + deep);
            // Many separate underground roots, spread well beyond the 10x10 square.
            double reach = below.stream().mapToDouble(e -> Math.hypot(e.getKey().x() - 5, e.getKey().z() - 5)).max().orElse(0);
            assertTrue(reach >= Math.min(14, size[1] * .9), label + " underground reach " + reach);
            assertTrue(below.size() >= 150, label + " underground blocks " + below.size());
            // Taproot / root flare directly below the trunk (mangrove: below its prop-root lattice).
            assertTrue(plan.containsKey(new StructurePos(5, 64 - deep + 2, 5)) || plan.containsKey(new StructurePos(4, 64 - deep + 2, 4))
                    || plan.containsKey(new StructurePos(5, 64 - deep + 2, 4)) || plan.containsKey(new StructurePos(4, 64 - deep + 2, 5)), label + " taproot");
        }
        for (Material plant : FUNGI) assertEquals(0, WorldTreePlan.depth(plant, 80, 24));
    }

    @Test void givenSpeciesDetailsThenUseMatchingDecorations() {
        var origin = new StructurePos(0, 0, 0);
        assertTrue(WorldTreePlan.generate(Material.PALE_OAK_SAPLING, origin, 3, 160, 44).containsValue(Material.PALE_HANGING_MOSS));
        assertTrue(WorldTreePlan.generate(Material.JUNGLE_SAPLING, origin, 3, 160, 44).containsValue(Material.VINE));
        assertTrue(WorldTreePlan.generate(Material.MANGROVE_PROPAGULE, origin, 3, 160, 44).containsValue(Material.MANGROVE_ROOTS));
        assertTrue(WorldTreePlan.generate(Material.OAK_SAPLING, origin, 3, 160, 44).containsValue(Material.OAK_WOOD));
        assertNotEquals(WorldTreePlan.generate(Material.OAK_SAPLING, origin, 3, 160, 44), WorldTreePlan.generate(Material.OAK_SAPLING, origin, 4, 160, 44));
    }

    @Test void givenFungiThenGeometryIsUnchangedFromZeroThirtyEight() {
        var origin = new StructurePos(-30, 64, 20);
        for (Material plant : FUNGI) for (long seed : List.of(0L, 42L)) {
            int height = plant == Material.CRIMSON_FUNGUS || plant == Material.WARPED_FUNGUS ? 48 : 96;
            assertEquals(OldWorldTreePlan.generate(plant, origin, seed, height, 24), WorldTreePlan.generate(plant, origin, seed, height, 24), plant.toString());
            int f = WorldTreePlan.footprint(plant, height, 24);
            assertTrue(WorldTreePlan.generate(plant, origin, seed, height, 24).keySet().stream()
                    .allMatch(p -> Math.abs(p.x() - (origin.x() + 5)) <= f && Math.abs(p.z() - (origin.z() + 5)) <= f));
        }
        var red = WorldTreePlan.generate(Material.RED_MUSHROOM, new StructurePos(0, 0, 0), 1, 80, 24);
        assertTrue(red.containsValue(Material.RED_MUSHROOM_BLOCK)); assertTrue(red.containsValue(Material.MUSHROOM_STEM));
        var warped = WorldTreePlan.generate(Material.WARPED_FUNGUS, new StructurePos(0, 0, 0), 1, 48, 18);
        assertTrue(warped.containsValue(Material.WARPED_WART_BLOCK)); assertTrue(warped.containsValue(Material.SHROOMLIGHT));
    }

    @Test void givenInvalidDimensionsOrPlantThenRejectBeforeGeneration() {
        var origin = new StructurePos(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> WorldTreePlan.generate(Material.STONE, origin, 0, 128, 28));
        assertThrows(IllegalArgumentException.class, () -> WorldTreePlan.generate(Material.OAK_SAPLING, origin, 0, 193, 28));
        assertThrows(IllegalArgumentException.class, () -> WorldTreePlan.generate(Material.OAK_SAPLING, origin, 0, 31, 28));
        assertThrows(IllegalArgumentException.class, () -> WorldTreePlan.generate(Material.OAK_SAPLING, origin, 0, 128, 49));
        assertThrows(IllegalArgumentException.class, () -> WorldTreePlan.generate(Material.OAK_SAPLING, origin, 0, 128, 11));
        assertThrows(IllegalArgumentException.class, () -> WorldTreePlan.generate(Material.RED_MUSHROOM, origin, 0, 128, 41));
        assertDoesNotThrow(() -> WorldTreePlan.generate(Material.OAK_SAPLING, origin, 0, 128, 48));
    }
}
