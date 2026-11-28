package Vystorm.vystorm_core.survey.internal;

import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Core's block classification for the survey, one class per block type (states such as open or closed doors do not
 * matter):
 *
 * <ul>
 *   <li>air, cave air, void air: {@link CellClass#AIR};</li>
 *   <li>water, lava, bubble columns: {@link CellClass#FLUID};</li>
 *   <li>doors and trapdoors ({@code Tag.DOORS}, {@code Tag.TRAPDOORS}): see-through with the door flag;</li>
 *   <li>a block the server calls occluding (a full opaque cube): {@link CellClass#OPAQUE}, unless it is on the
 *   study's see-through list (glass, leaves, ice, slime and honey blocks, spawners, chests ...);</li>
 *   <li>everything else (plants, slabs, stairs, fences, walls, panes, torches, rails ...): see-through.</li>
 * </ul>
 *
 * Changing this list means raising {@code SurveyRule.CLASSIFICATION_VERSION}. Built once on the server thread; the
 * lookups are read-only and safe on any thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class BlockClasses {
    /** Names the study (survey.js) treats as see-through although some are full blocks. */
    static final Pattern SEE_THROUGH = Pattern.compile(
            "leaves|glass|pane|door|fence|_bars|torch|lantern|rail|carpet|sign|banner|button|pressure_plate|sapling|^short_grass$|^grass$|^tall_grass$|fern|bush|vine|lichen|kelp|seagrass|sea_pickle|coral(?!_block)|^snow$|ladder|scaffolding|^chain$|lever|redstone_wire|tripwire|cobweb|sugar_cane|^bamboo$|cactus|pointed_dripstone|amethyst_cluster|_bud$|candle|_head$|skull|flower_pot|potted_|_bed$|chest|slab|stairs|_wall$|end_rod|^azalea$|flowering_azalea$|hanging_roots|spore_blossom|_roots$|sweet_berry|cave_vines|dripleaf|frogspawn|lily_pad|(pumpkin|melon)_stem|wheat|carrots|potatoes|beetroots|^light$|barrier|spawner|^ice$|frosted_ice|honey_block|slime_block|beacon|conduit|enchanting_table|anvil|brewing_stand|cauldron|hopper|lectern|grindstone|bell|campfire|composter|_mushroom$|_fungus$|nether_sprouts|weeping|twisting|petals|pitcher|torchflower|dandelion|poppy|orchid|allium|azure_bluet|tulip|oxeye|cornflower|lily_of_the_valley|wither_rose|sunflower|lilac|peony|eyeblossom|leaf_litter|chorus|end_portal$|nether_portal|^fire$|soul_fire|structure_void|piston_head|moving_piston|decorated_pot|vault|trial_spawner|heavy_core|daylight_detector|comparator|repeater|stonecutter|lightning_rod|sculk_sensor|sculk_shrieker|sculk_vein|pale_hanging_moss|resin_clump|dried_ghast|grate|end_gateway|dragon_egg|brush|glow_berries|large_fern|small_flowers|cactus_flower|bush$");

    private final byte[] byOrdinal;

    /** A table indexed by {@code Material.ordinal()} (tests build their own). */
    BlockClasses(byte[] byOrdinal) { this.byOrdinal = byOrdinal; }

    /** Builds the table for every block type of the running server. */
    public static BlockClasses build() {
        Material[] all = Material.values();
        byte[] table = new byte[all.length];
        for (Material m : all) {
            byte c;
            try { c = m.isBlock() ? classify(m) : CellClass.OPAQUE; }
            catch (RuntimeException | LinkageError legacy) { c = CellClass.OPAQUE; }
            table[m.ordinal()] = c;
        }
        return new BlockClasses(table);
    }

    private static byte classify(Material m) {
        if (m.isAir()) return CellClass.AIR;
        String name = m.name().toLowerCase(Locale.ROOT);
        if (name.equals("water") || name.equals("lava") || name.equals("bubble_column")) return CellClass.FLUID;
        if (Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m)) return CellClass.DOOR_CELL;
        if (SEE_THROUGH.matcher(name).find()) return CellClass.SEE_THROUGH;
        return m.isOccluding() ? CellClass.OPAQUE : CellClass.SEE_THROUGH;
    }

    public byte of(Material m) { return m == null ? CellClass.AIR : byOrdinal[m.ordinal()]; }

    /** The classes of a chunk snapshot (worker thread). */
    public ChunkClasses classify(ChunkSnapshot snapshot, int minY, int height) {
        ChunkClasses.Builder b = new ChunkClasses.Builder(minY, height);
        for (int s = 0; s < height >> 4; s++) {
            if (snapshot.isSectionEmpty(s)) {
                b.uniform(s, CellClass.AIR);
                continue;
            }
            byte[] bytes = new byte[4096];
            int y0 = minY + (s << 4);
            for (int ly = 0; ly < 16; ly++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) bytes[ly << 8 | z << 4 | x] = of(snapshot.getBlockType(x, y0 + ly, z));
            b.section(s, bytes);
        }
        return b.build();
    }
}
