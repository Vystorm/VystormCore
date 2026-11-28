package Vystorm.vystorm_core.blocks.internal;

import Vystorm.vystorm_core.blocks.StateTable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;

/**
 * Core's {@link StateTable}: two lock-free maps (block data and text to id) in front of an array of entries that is
 * copied on every add under a lock and published through a volatile field.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class SharedStateTable implements StateTable {
    private static final String AIR_TEXT = "minecraft:air";
    private static final Set<String> POI = Set.of("BELL", "BLAST_FURNACE", "SMOKER", "CARTOGRAPHY_TABLE", "BREWING_STAND",
            "COMPOSTER", "BARREL", "FLETCHING_TABLE", "LECTERN", "STONECUTTER", "LOOM", "SMITHING_TABLE", "GRINDSTONE",
            "BEEHIVE", "BEE_NEST", "NETHER_PORTAL", "LODESTONE");
    /** Name parts of block entity materials, for servers or tests where {@code createBlockState} gives no answer. */
    private static final String[] BLOCK_ENTITY_PARTS = {"CHEST", "SIGN", "SHULKER_BOX", "BANNER", "FURNACE", "SMOKER",
            "HOPPER", "DISPENSER", "DROPPER", "SPAWNER", "BEACON", "BARREL", "BREWING_STAND", "ENCHANTING_TABLE",
            "END_PORTAL", "END_GATEWAY", "JUKEBOX", "LECTERN", "BELL", "CAMPFIRE", "BEEHIVE", "BEE_NEST", "CONDUIT",
            "COMMAND_BLOCK", "STRUCTURE_BLOCK", "JIGSAW", "DAYLIGHT_DETECTOR", "COMPARATOR", "SKULL", "_HEAD", "CRAFTER",
            "VAULT", "DECORATED_POT", "CHISELED_BOOKSHELF", "SCULK_SENSOR", "SCULK_SHRIEKER", "SCULK_CATALYST",
            "SUSPICIOUS_", "CREAKING_HEART", "TEST_BLOCK", "SHELF", "MOVING_PISTON", "STATUE"};

    private record Entry(String text, BlockData data, Material material, boolean air, boolean solid, boolean blockEntity, boolean poi) {}

    private final Function<String, BlockData> parser;
    private final ConcurrentHashMap<BlockData, Integer> byData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> byText = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile Entry[] entries;

    private final Predicate<Material> solidRule;

    /** @param parser turns a state text into block data ({@code Bukkit::createBlockData} on a server) */
    public SharedStateTable(Function<String, BlockData> parser) { this(parser, Material::isSolid); }

    /**
     * @param parser turns a state text into block data
     * @param solidRule answers {@link #solid}; {@code Material::isSolid} on a server (it needs the server's registry)
     */
    public SharedStateTable(Function<String, BlockData> parser, Predicate<Material> solidRule) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.solidRule = Objects.requireNonNull(solidRule, "solidRule");
        entries = new Entry[]{new Entry(AIR_TEXT, null, Material.AIR, true, false, false, false)};
        byText.put(AIR_TEXT, AIR);
    }

    @Override
    public int id(BlockData data) {
        Objects.requireNonNull(data, "data");
        Integer known = byData.get(data);
        if (known != null) return known;
        String text = data.getAsString();
        known = byText.get(text);
        if (known == null) return add(text, data);
        if (known == AIR) fillAir(data);
        byData.putIfAbsent(copy(data), known);
        return known;
    }

    @Override
    public int id(String blockData) {
        Objects.requireNonNull(blockData, "blockData");
        Integer known = byText.get(blockData);
        if (known != null) return known;
        BlockData parsed = parser.apply(blockData);
        if (parsed == null) throw new IllegalArgumentException("Not a block state: " + blockData);
        int id = id(parsed);
        // the parser may write the text in its own form (default properties added): remember the caller's form too
        byText.putIfAbsent(blockData, id);
        return id;
    }

    @Override
    public BlockData data(int id) {
        Entry entry = entry(id);
        if (entry.data() != null) return entry.data();
        fillAir(parser.apply(AIR_TEXT));
        return entry(AIR).data();
    }

    @Override public String text(int id) { return entry(id).text(); }
    @Override public Material material(int id) { return entry(id).material(); }
    @Override public int size() { return entries.length; }
    @Override public boolean air(int id) { return entry(id).air(); }
    @Override public boolean solid(int id) { return entry(id).solid(); }
    @Override public boolean hasBlockEntity(int id) { return entry(id).blockEntity(); }
    @Override public boolean poi(int id) { return entry(id).poi(); }

    private Entry entry(int id) {
        Entry[] current = entries;
        if (id < 0 || id >= current.length) throw new IllegalArgumentException("Unknown state id " + id + " (table holds " + current.length + ")");
        return current[id];
    }

    private int add(String text, BlockData data) {
        BlockData stored = copy(data);
        Entry entry = describe(text, stored);
        synchronized (lock) {
            Integer raced = byText.get(text);
            if (raced != null) { byData.putIfAbsent(stored, raced); return raced; }
            Entry[] current = entries;
            Entry[] grown = Arrays.copyOf(current, current.length + 1);
            int id = current.length;
            grown[id] = entry;
            entries = grown;
            byText.put(text, id);
            byData.putIfAbsent(stored, id);
            return id;
        }
    }

    private void fillAir(BlockData air) {
        if (air == null || entries[AIR].data() != null) return;
        BlockData stored = copy(air);
        synchronized (lock) {
            Entry[] current = entries;
            if (current[AIR].data() != null) return;
            Entry[] replaced = current.clone();
            Entry old = current[AIR];
            replaced[AIR] = new Entry(old.text(), stored, old.material(), true, false, false, false);
            entries = replaced;
            byData.putIfAbsent(stored, AIR);
        }
    }

    private Entry describe(String text, BlockData data) {
        Material material = data.getMaterial();
        String name = material == null ? "" : material.name();
        boolean air = material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
        boolean solid = false;
        try { solid = material != null && solidRule.test(material); } catch (RuntimeException | LinkageError unknown) { }
        return new Entry(text, data, material, air, solid, blockEntity(data, name), poi(name));
    }

    private static boolean blockEntity(BlockData data, String name) {
        try {
            if (data.createBlockState() instanceof TileState) return true;
        } catch (RuntimeException | LinkageError noAnswer) {
            // Paper 1.20.6 and Spigot have createBlockState; anything else falls back to the name below
        }
        if (name.endsWith("_BED")) return true;
        for (String part : BLOCK_ENTITY_PARTS) if (name.contains(part)) return true;
        return false;
    }

    private static boolean poi(String name) {
        return name.endsWith("_BED") || name.contains("CAULDRON") || name.contains("LIGHTNING_ROD") || POI.contains(name.toUpperCase(Locale.ROOT));
    }

    private static BlockData copy(BlockData data) {
        try {
            BlockData clone = data.clone();
            return clone == null ? data : clone;
        } catch (RuntimeException unsupported) {
            return data;
        }
    }
}
