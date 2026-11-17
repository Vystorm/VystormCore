package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

class TreeBuilderStructureTest {
    @Test
    void everySpeciesCanSelectAllFourArchitectureForms() {
        for (TreeSpecies species : TreeSpecies.values()) {
            Set<TreeBuilder.TreeForm> forms = EnumSet.noneOf(TreeBuilder.TreeForm.class);
            for (int seed = 0; seed < 512; seed++) {
                forms.add(TreeBuilder.selectForm(new Random(seed * 7919L + 17), species));
            }
            assertEquals(EnumSet.allOf(TreeBuilder.TreeForm.class), forms,
                    species + " must expose open, forest, windswept and ancient forms");
        }
    }

    @Test
    void everyShapeKeepsItsWoodConnectedToTheBase() {
        forEveryShape((label, access, width) -> {
            Set<StructurePos> wood = new HashSet<>();
            access.blocks.forEach((position, material) -> {
                if (isWood(material)) {
                    wood.add(position);
                }
            });
            ArrayDeque<StructurePos> pending = new ArrayDeque<>();
            Set<StructurePos> reached = new HashSet<>();
            for (StructurePos position : wood) {
                if (position.y() == 0) {
                    pending.add(position);
                    reached.add(position);
                }
            }
            while (!pending.isEmpty()) {
                StructurePos current = pending.removeFirst();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            StructurePos next = current.add(dx, dy, dz);
                            if (wood.contains(next) && reached.add(next)) {
                                pending.add(next);
                            }
                        }
                    }
                }
            }
            assertEquals(wood.size(), reached.size(), label + " has floating wood");
        });
    }

    @Test
    void everyLeafStaysWithinDecayDistanceOfALog() {
        forEveryShape((label, access, width) -> {
            Map<StructurePos, Integer> distance = new HashMap<>();
            ArrayDeque<StructurePos> pending = new ArrayDeque<>();
            access.blocks.forEach((position, material) -> {
                if (TreeSpecies.isLog(material)) {
                    distance.put(position, 0);
                    pending.add(position);
                }
            });
            while (!pending.isEmpty()) {
                StructurePos current = pending.removeFirst();
                for (int[] offset : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    StructurePos next = current.add(offset[0], offset[1], offset[2]);
                    if (TreeSpecies.isLeaves(access.getType(next.x(), next.y(), next.z()))
                            && !distance.containsKey(next)) {
                        distance.put(next, distance.get(current) + 1);
                        pending.add(next);
                    }
                }
            }
            access.blocks.forEach((position, material) -> {
                if (TreeSpecies.isLeaves(material)) {
                    Integer value = distance.get(position);
                    assertTrue(value != null && value <= 6, label + " leaf at " + position + " would decay");
                }
            });
        });
    }

    @Test
    void singleSaplingTreesStayCloseToVanillaSize() {
        forEveryShape((label, access, width) -> {
            TreeSpecies species = TreeSpecies.valueOf(label.substring(0, label.indexOf(' ')));
            if (width != 1 || TreeBuilder.isClassic(species)) {
                return;
            }
            int top = access.blocks.keySet().stream().mapToInt(StructurePos::y).max().orElse(0);
            int reach = access.blocks.keySet().stream()
                    .mapToInt(position -> Math.max(Math.abs(position.x()), Math.abs(position.z()))).max().orElse(0);
            assertTrue(top <= 17, label + " is " + top + " blocks high");
            assertTrue(reach <= 7, label + " reaches " + reach + " blocks sideways");
        });
    }

    @Test
    void trunksStayBelowTheHeightThePluginChecks() {
        forEveryShape((label, access, width) -> {
            TreeSpecies species = TreeSpecies.valueOf(label.substring(0, label.indexOf(' ')));
            int limit = switch (width) {
                case 1 -> species.maxHeight;
                case 2 -> species.maxHeight + 10;
                case 3 -> 48;
                default -> 68;
            } + TreeBuilder.heightBonus(species, width);
            int trunkTop = access.blocks.entrySet().stream()
                    .filter(entry -> entry.getValue() == species.log)
                    .map(Map.Entry::getKey)
                    .filter(position -> position.x() >= 0 && position.x() < width
                            && position.z() >= 0 && position.z() < width)
                    .mapToInt(StructurePos::y).max().orElse(0);
            assertTrue(trunkTop <= limit + 1, label + " trunk reaches " + trunkTop + " > " + limit);
        });
    }

    @Test
    void profiledTreesChangeShapeWithoutExceedingExistingTrunkCeilings() {
        for (TreeSpecies species : TreeSpecies.values()) {
            FakeAccess ordinary = new FakeAccess(), compact = new FakeAccess(), tall = new FakeAccess();
            new TreeBuilder(ordinary, new Random(42), species, blockData(species.log), blockData(species.leaves),
                    TreeGrowthProfile.DEFAULT).buildNormal(new StructurePos(0, 0, 0));
            new TreeBuilder(compact, new Random(42), species, blockData(species.log), blockData(species.leaves),
                    new TreeGrowthProfile(.5, .5, TreeGrowthProfile.CrownForm.FOREST)).buildNormal(new StructurePos(0, 0, 0));
            new TreeBuilder(tall, new Random(42), species, blockData(species.log), blockData(species.leaves),
                    new TreeGrowthProfile(1.5, 1.5, TreeGrowthProfile.CrownForm.ANCIENT)).buildNormal(new StructurePos(0, 0, 0));
            assertTrue(!ordinary.blocks.equals(compact.blocks), species + " compact profile must affect geometry");
            int trunkTop = tall.blocks.entrySet().stream().filter(e -> e.getValue() == species.log)
                    .map(Map.Entry::getKey).filter(pos -> pos.x() == 0 && pos.z() == 0)
                    .mapToInt(StructurePos::y).max().orElse(0);
            assertTrue(trunkTop <= species.maxHeight + TreeBuilder.heightBonus(species, 1) + 1, species.toString());
        }
    }

    private interface ShapeCheck {
        void check(String label, FakeAccess access, int width);
    }

    private static void forEveryShape(ShapeCheck check) {
        for (TreeSpecies species : TreeSpecies.values()) {
            for (TreeBuilder.TreeForm form : TreeBuilder.TreeForm.values()) {
                for (int seed = 0; seed < 3; seed++) {
                    for (int width : new int[] {1, 2, 3, 5}) {
                        FakeAccess access = new FakeAccess();
                        TreeBuilder builder = new TreeBuilder(access, new Random(seed * 131L + width), species,
                                blockData(species.log), blockData(species.leaves), form);
                        StructurePos base = new StructurePos(0, 0, 0);
                        switch (width) {
                            case 1 -> builder.buildNormal(base);
                            case 2 -> builder.buildMedium(base);
                            case 3 -> builder.buildGiant(base, 36, 48, 11);
                            default -> builder.buildColossal(base, 52, 68, 17);
                        }
                        check.check(species + " " + form + " width " + width + " seed " + seed, access, width);
                    }
                }
            }
        }
    }

    private static boolean isWood(Material material) {
        return TreeSpecies.isLog(material) || material == Material.MANGROVE_ROOTS;
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(),
                new Class<?>[] {BlockData.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "clone" -> proxy;
                    case "getMaterial" -> material;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString", "getAsString" -> material.name();
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }

    private static final class FakeAccess implements StructureAccess {
        private final Map<StructurePos, Material> blocks = new HashMap<>();

        @Override
        public Material getType(int x, int y, int z) {
            return blocks.getOrDefault(new StructurePos(x, y, z), Material.AIR);
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            blocks.put(new StructurePos(x, y, z), material);
        }

        @Override
        public void setBlockData(int x, int y, int z, BlockData data) {
            blocks.put(new StructurePos(x, y, z), data.getMaterial());
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y >= 0 && y <= 320;
        }
    }
}
