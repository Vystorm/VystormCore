package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Writes block dumps ({@code x y z MATERIAL} per line) of every tree shape for visual review
 * (render with {@code ~/vystorm-tools/treerender.py}). Runs only with {@code TREE_RENDER_DIR} set.
 */
@EnabledIfEnvironmentVariable(named = "TREE_RENDER_DIR", matches = ".+")
class TreeRenderDump {
    private static final int GIANT_MIN = 36;
    private static final int GIANT_MAX = 48;
    private static final int GIANT_RADIUS = 11;
    private static final int COLOSSAL_MIN = 52;
    private static final int COLOSSAL_MAX = 68;
    private static final int COLOSSAL_RADIUS = 17;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void installTagServer() {
        if (Bukkit.getServer() != null) {
            return;
        }
        Server server = Mockito.mock(Server.class);
        Mockito.when(server.getLogger()).thenReturn(Logger.getLogger("TreeRenderDump"));
        Mockito.when(server.getTag(Mockito.anyString(), Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(1);
            String suffix = switch (key.getKey()) {
                case "logs", "logs_that_burn" -> "_LOG";
                case "leaves" -> "_LEAVES";
                default -> "\u0000";
            };
            Tag<Keyed> tag = (Tag<Keyed>) Proxy.newProxyInstance(Tag.class.getClassLoader(),
                    new Class<?>[] {Tag.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "isTagged" -> arguments[0] instanceof Material material
                                && material.name().endsWith(suffix);
                        case "getKey" -> key;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> null;
                    });
            return tag;
        });
        try {
            java.lang.reflect.Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void dumpAllShapes() throws IOException {
        Path directory = Path.of(System.getenv("TREE_RENDER_DIR"));
        Files.createDirectories(directory);
        int seeds = Integer.parseInt(System.getenv().getOrDefault("TREE_RENDER_SEEDS", "3"));
        for (TreeSpecies species : TreeSpecies.values()) {
            String name = species.name().toLowerCase();
            for (int seed = 0; seed < seeds; seed++) {
                for (TreeBuilder.TreeForm form : TreeBuilder.TreeForm.values()) {
                    String suffix = "-" + form.name().toLowerCase() + "-" + seed;
                    long base = seed * 7919L + species.ordinal() * 104729L + form.ordinal() * 31L;
                    dump(directory, name + "-normal" + suffix, species, form, base,
                            builder -> builder.buildNormal(new StructurePos(0, 0, 0)));
                    dump(directory, name + "-medium" + suffix, species, form, base + 1,
                            builder -> builder.buildMedium(new StructurePos(0, 0, 0)));
                    dump(directory, name + "-giant" + suffix, species, form, base + 2,
                            builder -> builder.buildGiant(new StructurePos(0, 0, 0),
                                    GIANT_MIN, GIANT_MAX, GIANT_RADIUS));
                    dump(directory, name + "-colossal" + suffix, species, form, base + 3,
                            builder -> builder.buildColossal(new StructurePos(0, 0, 0),
                                    COLOSSAL_MIN, COLOSSAL_MAX, COLOSSAL_RADIUS));
                }
            }
            dump(directory, name + "-shrub-0", species, null, 11,
                    builder -> builder.buildConnectedPlant(List.of(
                            new StructurePos(0, 0, 0), new StructurePos(1, 0, 0), new StructurePos(1, 0, 1)), false));
            List<StructurePos> hedge = new ArrayList<>();
            for (int x = 0; x < 9; x++) {
                hedge.add(new StructurePos(x, 0, 0));
            }
            for (int z = 1; z < 6; z++) {
                hedge.add(new StructurePos(8, 0, z));
            }
            dump(directory, name + "-hedge-0", species, null, 12,
                    builder -> builder.buildConnectedPlant(hedge, true));
        }
    }

    private static void dump(Path directory, String file, TreeSpecies species, TreeBuilder.TreeForm form,
            long seed, Consumer<TreeBuilder> build) throws IOException {
        MapAccess access = new MapAccess();
        TreeBuilder builder = new TreeBuilder(access, new Random(seed), species,
                blockData(species.log), blockData(species.leaves), form);
        build.accept(builder);
        try (Writer writer = Files.newBufferedWriter(directory.resolve(file + ".txt"), StandardCharsets.UTF_8)) {
            writer.write("# form=" + builder.form() + "\n");
            for (Map.Entry<StructurePos, Material> entry : access.blocks.entrySet()) {
                if (entry.getValue() == Material.AIR) {
                    continue;
                }
                StructurePos position = entry.getKey();
                writer.write(position.x() + " " + position.y() + " " + position.z() + " "
                        + entry.getValue().name() + "\n");
            }
        }
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
                    default -> method.getReturnType() == boolean.class ? false
                            : method.getReturnType() == int.class ? 0 : null;
                });
    }

    private static final class MapAccess implements StructureAccess {
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
            return y >= 0 && y <= 200;
        }
    }
}
