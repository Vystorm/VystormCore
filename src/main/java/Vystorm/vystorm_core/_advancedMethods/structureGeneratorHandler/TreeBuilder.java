package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Hangable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;

import org.bukkit.block.data.type.Leaves;

/**
 * Species tree shapes in Minecraft's own block language. Single saplings grow close to the vanilla
 * trees (blob and fancy oaks, birches, spruces and pines, jungle trees, forked acacias, dark and pale
 * oaks, mangroves on roots, cherries); 2×2, 3×3 and 5×5 saplings reuse the same foliage rules with larger
 * radii: fancy-oak clusters, layered mega-spruce rings, flat acacia pads, dark-oak slabs, mega-jungle
 * discs, cherry domes with hanging leaves and random-spread mangrove crowns. Decorations follow the
 * vanilla decorators (trunk and leaf vines, cocoa, pale hanging moss, hanging propagules).
 */
final class TreeBuilder implements TreeGenerator {
    /** Leaves decay at distance 7; 6 is the farthest a leaf may be from a log. */
    private static final int MAX_LEAF_DISTANCE = 6;
    private static final Set<Material> ANY_LOG = EnumSet.noneOf(Material.class);
    private static final Set<Material> ANY_LEAVES = EnumSet.noneOf(Material.class);
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] EIGHT_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[][] CARDINAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final BlockFace[] SIDES = {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH};

    static {
        // Name based instead of Tag.LOGS/Tag.LEAVES, so shapes can be built without a running server.
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.startsWith("LEGACY_")) {
                continue;
            }
            if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                    || name.endsWith("_HYPHAE")) {
                ANY_LOG.add(material);
            } else if (name.endsWith("_LEAVES")) {
                ANY_LEAVES.add(material);
            }
        }
    }

    private final StructureAccess access;
    private final Random random;
    private final TreeSpecies species;
    private final BlockData initialLog;
    private final BlockData initialLeaves;
    private final TreeForm form;
    private TreeGrowthProfile profile = TreeGrowthProfile.DEFAULT;
    private int profileHeightCeiling;
    private final int[] dominantDirection;
    private final Set<StructurePos> placedLeaves = new LinkedHashSet<>();
    private final Set<StructurePos> placedLogs = new LinkedHashSet<>();
    private final Map<Integer, StructurePos> trunkCenters = new HashMap<>();

    private boolean trunkVines;
    private double leafVineChance;
    private double cocoaChance;
    private double mossChance;
    private double propaguleChance;

    /** Anchor of a leaf row: centre, or the lower corner plus {@code span} 1 for a 2×2 trunk. */
    private record Anchor(int x, int y, int z, int span) {
    }

    /** Decides per row cell (distances from the anchor, row radius) whether to leave it empty. */
    private interface RowRule {
        boolean skip(int localX, int localZ, int range);
    }

    TreeBuilder(StructureAccess access, Random random, TreeSpecies species) {
        this(access, random, species, species.log.createBlockData(), species.leaves.createBlockData());
    }

    TreeBuilder(StructureAccess access, Random random, TreeSpecies species, TreeGrowthProfile profile) {
        this(access, random, species, species.log.createBlockData(), species.leaves.createBlockData(),
                profile.crownForm() == TreeGrowthProfile.CrownForm.RANDOM ? null : TreeForm.valueOf(profile.crownForm().name()));
        this.profile = profile;
    }

    TreeBuilder(StructureAccess access, Random random, TreeSpecies species,
            BlockData initialLog, BlockData initialLeaves, TreeGrowthProfile profile) {
        this(access, random, species, initialLog, initialLeaves,
                profile.crownForm() == TreeGrowthProfile.CrownForm.RANDOM ? null : TreeForm.valueOf(profile.crownForm().name()));
        this.profile = profile;
    }

    TreeBuilder(StructureAccess access, Random random, TreeSpecies species,
            BlockData initialLog, BlockData initialLeaves) {
        this(access, random, species, initialLog, initialLeaves, (TreeForm) null);
    }

    /** {@code form} null picks one at random; tests and renders pass it to compare the variants. */
    TreeBuilder(StructureAccess access, Random random, TreeSpecies species,
            BlockData initialLog, BlockData initialLeaves, TreeForm form) {
        this.access = access;
        this.random = random;
        this.species = species;
        this.initialLog = initialLog;
        if (initialLeaves instanceof Leaves leaves) {
            leaves.setPersistent(false);
            leaves.setDistance(7);
        }
        this.initialLeaves = initialLeaves;
        TreeForm selected = selectForm(random, species);
        this.form = form != null ? form : selected;
        this.dominantDirection = EIGHT_DIRECTIONS[random.nextInt(EIGHT_DIRECTIONS.length)];
    }

    @Override
    public void buildNormal(StructurePos base) {
        Objects.requireNonNull(base, "pos");
        profileHeightCeiling = species.maxHeight + heightBonus(1);
        logBuild("normal", true);
        if (isClassic(species)) {
            int limit = species.maxHeight + heightBonus(1);
            growClassic(base, 1, Math.min(limit, classicFormHeight(
                    between(species.minHeight, species.maxHeight) + heightBonus(1), 1)),
                    classicFormRadius(classicCanopyRadius(), 1));
            logBuild("normal", false);
            return;
        }
        int height = Math.min(formHeight(between(species.minHeight, species.maxHeight)),
                species.maxHeight + heightBonus(1));
        grow(base, 1, height, formRadius(normalCanopyRadius()));
        logBuild("normal", false);
    }

    @Override
    public void buildMedium(StructurePos base) {
        Objects.requireNonNull(base, "pos");
        profileHeightCeiling = species.maxHeight + 10 + heightBonus(2);
        logBuild("medium", true);
        if (isClassic(species)) {
            int limit = species.maxHeight + 10 + heightBonus(2);
            growClassic(base, 2, Math.min(limit, classicFormHeight(
                    between(species.minHeight + 6, species.maxHeight + 10) + heightBonus(2), 2)),
                    classicFormRadius(classicCanopyRadius() + 2 + random.nextInt(2), 2));
            logBuild("medium", false);
            return;
        }
        int limit = species.maxHeight + 10 + heightBonus(2);
        int height = Math.min(limit, formHeight(stature(
                between(species.minHeight + 6, species.maxHeight + 10) + heightBonus(2))));
        grow(base, 2, height, formRadius(normalCanopyRadius() + 1 + random.nextInt(2)));
        logBuild("medium", false);
    }

    @Override
    public void buildGiant(StructurePos base, int minHeight, int maxHeight, int canopyRadius) {
        Objects.requireNonNull(base, "pos");
        validateDimensions(minHeight, maxHeight, canopyRadius, 16, 96, 5, 20, "Giant");
        profileHeightCeiling = maxHeight + heightBonus(3);
        logBuild("giant", true);
        if (isClassic(species)) {
            growClassic(base, 3, Math.min(maxHeight + heightBonus(3), classicFormHeight(
                    between(minHeight, maxHeight) + heightBonus(3), 3)),
                    classicFormRadius(Math.max(7, canopyRadius + giantRadiusAdjustment() + random.nextInt(3) - 1), 3));
            logBuild("giant", false);
            return;
        }
        int height = Math.min(maxHeight + heightBonus(3),
                formHeight(stature(between(minHeight, maxHeight) + heightBonus(3))));
        int radius = formRadius(Math.max(7, canopyRadius + giantRadiusAdjustment() + random.nextInt(3) - 1));
        grow(base, 3, height, radius);
        logBuild("giant", false);
    }

    @Override
    public void buildColossal(StructurePos base, int minHeight, int maxHeight, int canopyRadius) {
        Objects.requireNonNull(base, "pos");
        validateDimensions(minHeight, maxHeight, canopyRadius, 32, 120, 10, 28, "Colossal");
        profileHeightCeiling = maxHeight + heightBonus(5);
        logBuild("colossal", true);
        if (isClassic(species)) {
            growClassic(base, 5, Math.min(maxHeight + heightBonus(5), classicFormHeight(
                    between(minHeight, maxHeight) + heightBonus(5), 5)),
                    classicFormRadius(Math.max(12, canopyRadius + giantRadiusAdjustment() + random.nextInt(3) - 1), 5));
            logBuild("colossal", false);
            return;
        }
        int height = Math.min(maxHeight + heightBonus(5),
                formHeight(stature(between(minHeight, maxHeight) + heightBonus(5))));
        int radius = formRadius(Math.max(10, canopyRadius + giantRadiusAdjustment() + random.nextInt(3) - 1));
        grow(base, 5, height, radius);
        logBuild("colossal", false);
    }

    @Override
    public void buildConnectedPlant(List<StructurePos> positions, boolean hedge) {
        Objects.requireNonNull(positions, "positions");
        if (positions.isEmpty() || positions.size() > 128 || positions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Connected plants require 1 through 128 positions");
        }
        logBuild("connected plant", true);
        if (hedge) {
            buildHedge(positions);
        } else {
            buildShrubGroup(positions);
        }
        finishLeaves();
        logBuild("connected plant", false);
    }

    private static void validateDimensions(int minHeight, int maxHeight, int radius,
            int minimumHeight, int maximumHeight, int minimumRadius, int maximumRadius, String type) {
        if (minHeight < minimumHeight || maxHeight > maximumHeight || minHeight > maxHeight
                || radius < minimumRadius || radius > maximumRadius) {
            throw new IllegalArgumentException(type + " dimensions are outside the supported range");
        }
    }

    private static void logBuild(String type, boolean begin) {
        DebugMessages.low("[TreeBuilder] Tree build " + (begin ? "begin" : "end") + "; type=" + type);
    }

    private void grow(StructurePos base, int width, int height, int radius) {
        if (!profile.equals(TreeGrowthProfile.DEFAULT)) {
            height = profile.height(height, profileHeightCeiling);
            radius = profile.radius(radius);
        }
        switch (species) {
            case OAK -> buildOak(base, width, height, radius);
            case BIRCH -> buildBirch(base, width, height, radius);
            case SPRUCE -> buildSpruce(base, width, height, radius);
            case JUNGLE, ACACIA, CHERRY -> throw new IllegalStateException("Classic species: " + species);
            case DARK_OAK -> buildDarkOak(base, width, height, radius, false);
            case MANGROVE -> buildMangrove(base, width, height, radius);
            case PALE_OAK -> buildDarkOak(base, width, height, radius, true);
        }
        finishLeaves();
        decorate(base);
    }

    // ------------------------------------------------------------------ classic species

    /**
     * Jungle, acacia and cherry keep the earlier CustomTrees shapes (branching crowns with leaf blobs),
     * lightly extended: jungle trees get vanilla trunk and leaf vines plus cocoa, cherry crowns drop
     * hanging blossom leaves under their edges, acacias may grow a small side fork with its own pad.
     */
    static boolean isClassic(TreeSpecies species) {
        return species == TreeSpecies.JUNGLE || species == TreeSpecies.ACACIA || species == TreeSpecies.CHERRY;
    }

    private void growClassic(StructurePos base, int width, int height, int radius) {
        if (!profile.equals(TreeGrowthProfile.DEFAULT)) {
            height = profile.height(height, profileHeightCeiling);
            radius = profile.radius(radius);
        }
        switch (species) {
            case JUNGLE -> {
                buildJungleShape(base, height, width, radius);
                trunkVines = true;
                leafVineChance = width >= 3 ? 0.04 : 0.08;
                cocoaChance = width <= 2 ? 0.35 : 0;
            }
            case ACACIA -> {
                buildAcaciaShape(base, height, width, radius);
                addAcaciaSideFork(base, height, width, radius);
            }
            case CHERRY -> buildCherryShape(base, height, width, radius);
            default -> throw new IllegalStateException("Not a classic species: " + species);
        }
        classicAccents(base, height, width, radius);
        if (width == 5) {
            classicColossalRoundness(base, height, radius);
        }
        if (species == TreeSpecies.CHERRY) {
            hangBlossoms();
        }
        finishLeaves();
        decorate(base);
    }

    /** Like the vanilla acacia's second fork: a short diagonal side branch low in the crown with a flat pad. */
    private void addAcaciaSideFork(StructurePos base, int height, int width, int radius) {
        if (random.nextDouble() >= (form == TreeForm.FOREST ? 0.3 : 0.65)) {
            return;
        }
        int startY = base.y() + height * (30 + random.nextInt(12)) / 100;
        StructurePos start = trunkCenterAt(startY, new StructurePos(base.x() + width / 2, startY, base.z() + width / 2));
        int[] direction = form == TreeForm.WINDSWEPT
                ? dominantDirection : EIGHT_DIRECTIONS[random.nextInt(EIGHT_DIRECTIONS.length)];
        int steps = 2 + width + random.nextInt(2 + width);
        StructurePos end = traceBranch(start, direction[0] * 1000, direction[1] * 1000,
                steps + width / 2, steps, width >= 3);
        int pad = Math.max(3, radius / 2 + random.nextInt(2));
        leafBlob(end.x(), end.y(), end.z(), pad, 1 + width / 3, 0.12);
        leafDisk(end.x(), end.y() + 1, end.z(), Math.max(2, pad - 1));
    }

    /** Vanilla cherry detail: leaves hang one or two blocks below the outer, lower edge of the crown. */
    private void hangBlossoms() {
        for (StructurePos leaf : List.copyOf(placedLeaves)) {
            if (!isCurrentLeaf(leaf) || !isFree(leaf.x(), leaf.y() - 1, leaf.z()) || !isRowEdge(leaf.x(), leaf.y(), leaf.z())
                    || random.nextDouble() >= 1.0 / 6.0) {
                continue;
            }
            leaves(leaf.x(), leaf.y() - 1, leaf.z());
            if (random.nextDouble() < 1.0 / 3.0 && isFree(leaf.x(), leaf.y() - 2, leaf.z())) {
                leaves(leaf.x(), leaf.y() - 2, leaf.z());
            }
        }
    }

    private void classicColossalRoundness(StructurePos base, int height, int radius) {
        if (species == TreeSpecies.ACACIA || species == TreeSpecies.CHERRY
                || species == TreeSpecies.MANGROVE || species == TreeSpecies.PALE_OAK) {
            return;
        }
        int topY = base.y() + height - 1;
        StructurePos top = trunkCenterAt(topY,
                new StructurePos(base.x() + 2, topY, base.z() + 2));
        int crownRadius = Math.max(8, radius - 3);
        int crownY = top.y() - Math.max(2, crownRadius / 4);
        int crownHeight = species == TreeSpecies.SPRUCE
                ? Math.max(6, crownRadius / 2)
                : Math.max(6, crownRadius * 2 / 3);
        leafBlob(top.x(), crownY, top.z(), crownRadius, crownHeight,
                species == TreeSpecies.SPRUCE ? 0.24 : 0.12);
    }

    private int classicFormHeight(int height, int width) {
        return switch (form) {
            case OPEN_GROWN -> Math.max(7, height - Math.max(1, height / 12));
            case FOREST -> height + Math.max(1, height / 9);
            case WINDSWEPT -> height;
            case ANCIENT -> height + Math.max(width, height / 12);
        };
    }

    private int classicFormRadius(int radius, int width) {
        return switch (form) {
            case OPEN_GROWN -> radius + Math.max(1, width / 2);
            case FOREST -> Math.max(3, radius - 1);
            case WINDSWEPT -> radius + (width >= 3 ? 1 : 0);
            case ANCIENT -> radius + 1 + width / 2;
        };
    }

    private void classicAccents(StructurePos base, int height, int width, int radius) {
        switch (form) {
            case OPEN_GROWN -> architectureBranches(base, height, width, radius,
                    2 + random.nextInt(2), 35, 18, false, false);
            case FOREST -> architectureBranches(base, height, width, Math.max(3, radius - 2),
                    2, 70, 12, false, false);
            case WINDSWEPT -> architectureBranches(base, height, width, radius + 2,
                    3 + random.nextInt(2), 48, 26, width > 1, true);
            case ANCIENT -> architectureBranches(base, height, width, radius + 1,
                    4 + random.nextInt(3), 38, 30, true, false);
        }
    }

    private void architectureBranches(StructurePos base, int height, int width, int radius,
            int count, int startPercent, int spreadPercent, boolean thick, boolean oneSided) {
        double baseAngle = Math.atan2(dominantDirection[1], dominantDirection[0]);
        int trunkTop = trunkCenters.keySet().stream().mapToInt(Integer::intValue).max().orElse(base.y());
        for (int index = 0; index < count; index++) {
            int startY = base.y() + height
                    * (startPercent + random.nextInt(Math.max(2, spreadPercent))) / 100;
            // Forked species end their trunk early; a branch must start on wood, not in the air.
            startY = Math.min(startY, trunkTop - random.nextInt(2));
            StructurePos start = trunkCenterAt(startY,
                    new StructurePos(base.x() + width / 2, startY, base.z() + width / 2));
            double angle = oneSided
                    ? baseAngle + (index - (count - 1) / 2.0) * 0.24
                            + (random.nextDouble() - 0.5) * 0.16
                    : baseAngle + Math.PI * 2.0 * index / count
                            + (random.nextDouble() - 0.5) * 0.55;
            int length = Math.max(3, radius - 1) + random.nextInt(3 + Math.max(1, width));
            if (oneSided) {
                length += Math.max(2, radius / 3);
            }
            int rise = oneSided ? random.nextInt(4) - 1 : 1 + random.nextInt(3 + width);
            StructurePos end = branch(start,
                    (int) Math.round(Math.cos(angle) * 1000),
                    (int) Math.round(Math.sin(angle) * 1000),
                    length, rise, thick);
            int clusterRadius = Math.max(2, radius / 3 + random.nextInt(2 + width / 2));
            leafBlob(end.x(), end.y(), end.z(), clusterRadius,
                    Math.max(2, clusterRadius / 2 + 1), 0.14 + random.nextDouble() * 0.10);
        }
    }

    private void buildJungleShape(StructurePos base, int height, int width, int radius) {
        StructurePos top = leaningSquareTrunk(base, height, width, Math.max(8, 12 - width));
        addButtressRoots(base, width);
        int crownY = top.y() - random.nextInt(2 + width);
        leafBlob(top.x(), crownY, top.z(), radius + 1,
                Math.max(4, radius / 2), 0.18);

        int mainBranches = 5 + random.nextInt(3 + width);
        int branchY = base.y() + height * (66 + random.nextInt(8)) / 100;
        StructurePos branchStart = trunkCenterAt(branchY, top);
        for (int index = 0; index < mainBranches; index++) {
            double angle = Math.PI * 2.0 * index / mainBranches + random.nextDouble() * 0.5;
            int dx = (int) Math.round(Math.cos(angle) * 1000);
            int dz = (int) Math.round(Math.sin(angle) * 1000);
            StructurePos end = branch(branchStart.add(0, random.nextInt(4), 0), dx, dz,
                    radius + width * 2 + random.nextInt(4 + width),
                    Math.max(3, crownY - branchY - random.nextInt(4)), true);
            leafBlob(end.x(), end.y(), end.z(), Math.max(3, radius / 2 + random.nextInt(3)),
                    Math.max(3, radius / 3), 0.16 + random.nextDouble() * 0.10);
            if (random.nextBoolean()) {
                hangingLeaves(end.x(), end.y() - 2, end.z(), 2 + random.nextInt(2 + width));
            }
        }
    }

    private void buildAcaciaShape(StructurePos base, int height, int width, int radius) {
        int trunkHeight = Math.max(5, height * (30 + random.nextInt(13)) / 100);
        StructurePos fork = leaningSquareTrunk(base, trunkHeight, width, Math.max(3, 6 - width));
        if (width > 1) {
            addScaledRoots(base, width);
        }
        int leaders = 2 + random.nextInt(3);
        int targetTop = base.y() + height - 1;
        int flatHeight = 2 + width / 2;
        addForkingLeader(fork, targetTop, width, radius, flatHeight, true);
        for (int index = 0; index < leaders; index++) {
            double angle = Math.PI * 2.0 * index / leaders + random.nextDouble() * 0.65;
            int firstDx = (int) Math.round(Math.cos(angle) * 1000);
            int firstDz = (int) Math.round(Math.sin(angle) * 1000);
            StructurePos bend = branch(fork, firstDx, firstDz,
                    2 + width + random.nextInt(3 + width),
                    Math.max(2, (targetTop - fork.y()) / 2), width > 1);

            double secondAngle = angle + (random.nextBoolean() ? 0.28 : -0.28);
            int secondDx = (int) Math.round(Math.cos(secondAngle) * 1000);
            int secondDz = (int) Math.round(Math.sin(secondAngle) * 1000);
            StructurePos end = branch(bend, secondDx, secondDz,
                    Math.max(3, radius - 1) + random.nextInt(3 + width),
                    Math.max(1, targetTop - bend.y() - random.nextInt(3)), width > 1);
            int crownRadius = Math.max(4, radius - 1 + random.nextInt(3));
            leafBlob(end.x(), end.y(), end.z(), crownRadius, flatHeight,
                    0.14 + random.nextDouble() * 0.08);
            leafDisk(end.x(), end.y() + 1, end.z(), Math.max(3, crownRadius - 1));
        }
    }

    private void buildCherryShape(StructurePos base, int height, int width, int radius) {
        int trunkHeight = Math.max(5, height * (34 + random.nextInt(12)) / 100);
        StructurePos fork = leaningSquareTrunk(base, trunkHeight, width, Math.max(4, 7 - width));
        if (width > 1) {
            addScaledRoots(base, width);
        }

        int mainBranches = 3 + random.nextInt(3);
        int targetTop = base.y() + height - 1;
        addForkingLeader(fork, targetTop, width, radius,
                2 + width / 2, false);
        for (int index = 0; index < mainBranches; index++) {
            double angle = Math.PI * 2.0 * index / mainBranches + random.nextDouble() * 0.42;
            int firstDx = (int) Math.round(Math.cos(angle) * 1000);
            int firstDz = (int) Math.round(Math.sin(angle) * 1000);
            int firstLength = Math.max(2, radius / 3) + random.nextInt(2 + width);
            int firstRise = Math.max(2, (targetTop - fork.y()) * (38 + random.nextInt(18)) / 100);
            StructurePos bend = branch(fork, firstDx, firstDz, firstLength, firstRise, width > 1);

            double bendAngle = angle + (random.nextBoolean() ? 1 : -1)
                    * (0.10 + random.nextDouble() * 0.32);
            int secondDx = (int) Math.round(Math.cos(bendAngle) * 1000);
            int secondDz = (int) Math.round(Math.sin(bendAngle) * 1000);
            int secondLength = Math.max(3, radius / 2) + random.nextInt(3 + width);
            int secondRise = Math.max(2, targetTop - bend.y() - random.nextInt(4));
            StructurePos end = branch(bend, secondDx, secondDz,
                    secondLength, secondRise, width > 1);

            int clusterRadius = Math.max(3, radius / 2 + random.nextInt(3));
            int clusterHeight = 2 + width / 2 + random.nextInt(2);
            leafBlob(end.x(), end.y(), end.z(), clusterRadius, clusterHeight,
                    0.08 + random.nextDouble() * 0.08);
            leafDisk(end.x(), end.y() - clusterHeight, end.z(), Math.max(2, clusterRadius - 1));

            if (width > 1 || random.nextBoolean()) {
                double sideAngle = bendAngle + (random.nextBoolean() ? 0.65 : -0.65);
                int sideDx = (int) Math.round(Math.cos(sideAngle) * 1000);
                int sideDz = (int) Math.round(Math.sin(sideAngle) * 1000);
                StructurePos sideEnd = branch(bend, sideDx, sideDz,
                        Math.max(2, secondLength - 2), Math.max(1, secondRise / 2), false);
                leafBlob(sideEnd.x(), sideEnd.y(), sideEnd.z(),
                        Math.max(3, clusterRadius - 1), Math.max(2, clusterHeight - 1), 0.12);
            }
        }

        int centerY = base.y() + height * 72 / 100;
        leafBlob(fork.x(), centerY, fork.z(), Math.max(3, radius / 2),
                2 + width / 2, 0.10);
    }

    private int classicCanopyRadius() {
        return switch (species) {
            case BIRCH -> 3;
            case OAK -> 4;
            case SPRUCE, JUNGLE, DARK_OAK, MANGROVE, PALE_OAK -> 5;
            case ACACIA, CHERRY -> 6;
        };
    }

    private StructurePos leaningSquareTrunk(StructurePos base, int height, int width, int shiftEvery) {
        int originX = base.x();
        int originZ = base.z();
        int[] direction = form == TreeForm.WINDSWEPT
                ? dominantDirection : EIGHT_DIRECTIONS[random.nextInt(EIGHT_DIRECTIONS.length)];
        for (int y = 0; y < height; y++) {
            int leanStart = form == TreeForm.WINDSWEPT ? height / 4 : height / 3;
            double shiftChance = form == TreeForm.WINDSWEPT ? 0.88 : 0.72;
            if (y > leanStart && y % shiftEvery == 0 && random.nextDouble() < shiftChance) {
                originX += direction[0];
                originZ += direction[1];
                if (form != TreeForm.WINDSWEPT && random.nextDouble() < 0.24) {
                    direction = EIGHT_DIRECTIONS[random.nextInt(EIGHT_DIRECTIONS.length)];
                }
            }
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < width; dz++) {
                    if (isRoundedTrunkCell(dx, dz, width)) {
                        log(originX + dx, base.y() + y, originZ + dz);
                    }
                }
            }
            trunkCenters.put(base.y() + y,
                    new StructurePos(originX + width / 2, base.y() + y, originZ + width / 2));
        }
        return new StructurePos(originX + width / 2, base.y() + height - 1, originZ + width / 2);
    }

    private void addForkingLeader(StructurePos fork, int targetTop, int width,
            int canopyRadius, int canopyHeight, boolean flatCanopy) {
        int availableRise = targetTop - fork.y();
        if (availableRise < 4) {
            return;
        }

        int leaderRise = Math.max(2, availableRise * 2 / 3);
        StructurePos split = traceBranch(fork, 0, 0, 0, leaderRise, false);

        // A second vertical row makes the central leader visibly stronger without
        // turning a single-sapling tree into a full square trunk again.
        int supportX = random.nextBoolean() ? 1 : -1;
        for (int y = fork.y(); y < split.y(); y++) {
            branchLog(fork.x() + supportX, y, fork.z(), Axis.Y);
        }

        double rotation = random.nextDouble() * Math.PI;
        int forkLength = Math.max(3, Math.min(6, canopyRadius / 2 + width));
        int remainingRise = Math.max(1, availableRise - leaderRise);
        int crownRadius = Math.max(2, Math.min(4 + width / 2, canopyRadius / 2 + 1));
        int crownHeight = Math.max(2, canopyHeight);
        for (int index = 0; index < 2; index++) {
            double angle = rotation + Math.PI * index;
            StructurePos end = branch(split,
                    (int) Math.round(Math.cos(angle) * 1000),
                    (int) Math.round(Math.sin(angle) * 1000),
                    forkLength, Math.max(1, remainingRise - random.nextInt(2)), true);
            leafBlob(end.x(), end.y(), end.z(), crownRadius, crownHeight,
                    0.12 + random.nextDouble() * 0.08);
            if (flatCanopy) {
                leafDisk(end.x(), end.y() + 1, end.z(), Math.max(2, crownRadius - 1));
            }
        }
    }

    private void addScaledRoots(StructurePos base, int width) {
        addScaledRootsFromCenter(
                new StructurePos(base.x() + width / 2, base.y(), base.z() + width / 2),
                width);
    }

    private void addScaledRootsFromCenter(StructurePos center, int width) {
        int reach = 1 + width / 2;
        for (int[] direction : CARDINAL_DIRECTIONS) {
            for (int step = 1; step <= reach; step++) {
                int x = center.x() + direction[0] * step;
                int z = center.z() + direction[1] * step;
                int y = center.y() + (step == 1 && width > 1 ? 1 : 0);
                branchLog(x, y, z, axisFor(direction[0], direction[1]));
            }
        }
    }

    private void addButtressRoots(StructurePos base, int width) {
        int centerX = base.x() + width / 2;
        int centerZ = base.z() + width / 2;
        int reach = 1 + width / 2;
        int height = 2 + width;
        for (int[] direction : CARDINAL_DIRECTIONS) {
            for (int step = 1; step <= reach; step++) {
                int x = centerX + direction[0] * step;
                int z = centerZ + direction[1] * step;
                int localHeight = Math.max(0,
                        height - (int) Math.round(step * height / (double) (reach + 1)));
                for (int dy = 0; dy <= localHeight; dy++) {
                    branchLog(x, base.y() + dy, z, axisFor(direction[0], direction[1]));
                }
            }
        }
    }

    private void hangingLeaves(int x, int startY, int z, int length) {
        for (int offset = 0; offset < length; offset++) {
            leaves(x, startY - offset, z);
            if (offset == 0 && length > 2) {
                leaves(x + 1, startY, z);
                leaves(x, startY, z + 1);
            }
        }
    }

    private StructurePos branch(StructurePos start, int dxScaled, int dzScaled, int length, int rise, boolean thick) {
        StructurePos end = traceBranch(start, dxScaled, dzScaled, length, rise, thick);
        addSecondaryBranches(start, end, dxScaled, dzScaled, length, rise);
        return end;
    }

    private StructurePos traceBranch(StructurePos start, int dxScaled, int dzScaled,
            int length, int rise, boolean thick) {
        int endX = start.x() + (int) Math.round(dxScaled / 1000.0 * length);
        int endZ = start.z() + (int) Math.round(dzScaled / 1000.0 * length);
        int endY = start.y() + rise;
        boolean vertical = dxScaled == 0 && dzScaled == 0;
        Axis axis = vertical ? Axis.Y : axisFor(dxScaled, dzScaled);
        int steps = Math.max(length, Math.abs(rise));
        for (int step = 0; step <= steps; step++) {
            double fraction = step / (double) Math.max(1, steps);
            int x = (int) Math.round(start.x() + (endX - start.x()) * fraction);
            int y = (int) Math.round(start.y() + (endY - start.y()) * fraction);
            int z = (int) Math.round(start.z() + (endZ - start.z()) * fraction);
            branchLog(x, y, z, axis);
            if (!vertical && step < steps * 0.72) {
                // Every branch receives one additional supporting log layer.
                branchLog(x, y - 1, z, axis);
            }
            if (thick && step < steps * 0.65) {
                branchLog(x + Integer.signum(dzScaled), y, z - Integer.signum(dxScaled), axis);
                branchLog(x - Integer.signum(dzScaled), y, z + Integer.signum(dxScaled), axis);
            }
        }
        return new StructurePos(endX, endY, endZ);
    }

    private void addSecondaryBranches(StructurePos start, StructurePos end,
            int dxScaled, int dzScaled, int length, int rise) {
        if (length < 4 || (dxScaled == 0 && dzScaled == 0)) {
            return;
        }
        double mainAngle = Math.atan2(dzScaled, dxScaled);
        int sideBranches = length >= 12 ? 3 : length >= 7 ? 2 : 1;
        for (int index = 0; index < sideBranches; index++) {
            double fraction = 0.48 + index * 0.16
                    + (random.nextDouble() - 0.5) * 0.08;
            StructurePos fork = interpolate(start, end, fraction);
            int side = index % 2 == 0 ? 1 : -1;
            double sideAngle = mainAngle + side * (0.48 + random.nextDouble() * 0.42);
            int sideLength = Math.max(2, Math.min(7,
                    length / 3 + random.nextInt(Math.max(2, length / 4 + 1))));
            int sideRise = rise >= 0
                    ? random.nextInt(Math.max(2, 2 + Math.abs(rise) / 4))
                    : -random.nextInt(2);
            StructurePos sideEnd = traceBranch(fork,
                    (int) Math.round(Math.cos(sideAngle) * 1000),
                    (int) Math.round(Math.sin(sideAngle) * 1000),
                    sideLength, sideRise, false);
            if (length >= 9 && sideLength >= 3) {
                double twigAngle = sideAngle - side * (0.42 + random.nextDouble() * 0.34);
                traceBranch(sideEnd,
                        (int) Math.round(Math.cos(twigAngle) * 1000),
                        (int) Math.round(Math.sin(twigAngle) * 1000),
                        Math.max(2, sideLength / 2), random.nextInt(3) - 1, false);
            }
        }

        int terminalForks = length >= 8 ? 2 : 1;
        for (int index = 0; index < terminalForks; index++) {
            int side = index == 0 ? 1 : -1;
            double forkAngle = mainAngle + side * (0.52 + random.nextDouble() * 0.35);
            traceBranch(end,
                    (int) Math.round(Math.cos(forkAngle) * 1000),
                    (int) Math.round(Math.sin(forkAngle) * 1000),
                    Math.max(2, Math.min(5, length / 4 + 1)),
                    random.nextInt(3) - 1, false);
        }
    }

    private static StructurePos interpolate(StructurePos start, StructurePos end, double fraction) {
        return new StructurePos(
                (int) Math.round(start.x() + (end.x() - start.x()) * fraction),
                (int) Math.round(start.y() + (end.y() - start.y()) * fraction),
                (int) Math.round(start.z() + (end.z() - start.z()) * fraction));
    }

    private void leafBlob(int centerX, int centerY, int centerZ,
            int horizontalRadius, int verticalRadius, double edgeHoleChance) {
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    double distance = (dx * dx + dz * dz) / (double) (horizontalRadius * horizontalRadius)
                            + (dy * dy) / (double) (verticalRadius * verticalRadius);
                    if (distance > 1.0 || (distance > 0.70 && random.nextDouble() < edgeHoleChance)) {
                        continue;
                    }
                    leaves(centerX + dx, centerY + dy, centerZ + dz);
                }
            }
        }
        addCanopySupports(centerX, centerY, centerZ, horizontalRadius, verticalRadius);
    }

    private void addCanopySupports(int centerX, int centerY, int centerZ,
            int horizontalRadius, int verticalRadius) {
        if (horizontalRadius < 4) {
            return;
        }
        StructurePos origin = nearbyLog(centerX, centerY, centerZ, 2);
        if (origin == null) {
            return;
        }
        int spokes = Math.min(20, Math.max(4, horizontalRadius));
        int reach = Math.max(2, horizontalRadius - 2);
        int riseRange = Math.max(1, verticalRadius / 3);
        double rotation = random.nextDouble() * Math.PI * 2.0;
        for (int index = 0; index < spokes; index++) {
            double angle = rotation + Math.PI * 2.0 * index / spokes
                    + (random.nextDouble() - 0.5) * 0.24;
            int rise = random.nextInt(riseRange * 2 + 1) - riseRange;
            traceBranch(origin,
                    (int) Math.round(Math.cos(angle) * 1000),
                    (int) Math.round(Math.sin(angle) * 1000),
                    Math.max(2, reach - random.nextInt(2)), rise, false);
        }
        int verticalReach = Math.max(0, verticalRadius - 2);
        for (int offset = 1; offset <= verticalReach; offset++) {
            branchLog(origin.x(), origin.y() + offset, origin.z(), Axis.Y);
            branchLog(origin.x(), origin.y() - offset, origin.z(), Axis.Y);
        }
    }

    private StructurePos nearbyLog(int centerX, int centerY, int centerZ, int radius) {
        StructurePos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (distance >= bestDistance || distance > radius) {
                        continue;
                    }
                    int x = centerX + dx;
                    int y = centerY + dy;
                    int z = centerZ + dz;
                    if (access.contains(x, y, z)
                            && TreeSpecies.isLog(access.getType(x, y, z))) {
                        best = new StructurePos(x, y, z);
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private void leafDisk(int centerX, int y, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius + random.nextInt(3)
                        && random.nextDouble() > 0.08) {
                    leaves(centerX + dx, y, centerZ + dz);
                }
            }
        }
    }

    // ------------------------------------------------------------------ forms and sizes

    TreeForm form() {
        return form;
    }

    static TreeForm selectForm(Random random, TreeSpecies species) {
        int roll = random.nextInt(100);
        int openLimit = switch (species) {
            case OAK, ACACIA, CHERRY, DARK_OAK -> 34;
            case MANGROVE, PALE_OAK -> 28;
            case BIRCH, SPRUCE, JUNGLE -> 22;
        };
        int forestLimit = openLimit + switch (species) {
            case BIRCH, SPRUCE, JUNGLE -> 38;
            default -> 26;
        };
        int windsweptLimit = forestLimit + 22;
        if (roll < openLimit) {
            return TreeForm.OPEN_GROWN;
        }
        if (roll < forestLimit) {
            return TreeForm.FOREST;
        }
        if (roll < windsweptLimit) {
            return TreeForm.WINDSWEPT;
        }
        return TreeForm.ANCIENT;
    }

    enum TreeForm {
        /** Free-standing: a bit lower, wider crown. */
        OPEN_GROWN,
        /** Forest: taller, narrower crown. */
        FOREST,
        /** Wind-bent: trunk and crown lean with the dominant direction. */
        WINDSWEPT,
        /** Old tree: taller, fuller crown, more limbs. */
        ANCIENT
    }

    private int formHeight(int height) {
        return switch (form) {
            case OPEN_GROWN -> Math.max(4, height - Math.max(1, height / 10));
            case FOREST -> height + Math.max(1, height / 10);
            case WINDSWEPT -> height - height / 12;
            case ANCIENT -> height + Math.max(1, height / 12);
        };
    }

    private int formRadius(int radius) {
        return switch (form) {
            case OPEN_GROWN, ANCIENT -> radius + 1;
            case FOREST -> Math.max(2, radius - 1);
            case WINDSWEPT -> radius;
        };
    }

    /** Squat species stay lower than the fit limit allows (vanilla dark oaks and acacias are wide, not tall). */
    private int stature(int height) {
        double factor = switch (species) {
            case DARK_OAK -> 0.45;
            case PALE_OAK -> 0.5;
            case MANGROVE -> 0.65;
            default -> 1.0;
        };
        return Math.max(6, (int) Math.round(height * factor));
    }

    private int normalCanopyRadius() {
        return switch (species) {
            case BIRCH, SPRUCE, ACACIA, DARK_OAK, MANGROVE, PALE_OAK -> 3;
            case OAK, JUNGLE, CHERRY -> 4;
        };
    }

    private int giantRadiusAdjustment() {
        return switch (species) {
            case BIRCH -> -3;
            case OAK, SPRUCE -> 0;
            case JUNGLE, MANGROVE, PALE_OAK -> 1;
            case ACACIA, DARK_OAK, CHERRY -> 2;
        };
    }

    static int heightBonus(TreeSpecies species, int width) {
        return switch (species) {
            case DARK_OAK -> switch (width) {
                case 1 -> 4;
                case 2 -> 6;
                case 3 -> 8;
                default -> 12;
            };
            case BIRCH, SPRUCE -> switch (width) {
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 6;
                default -> 8;
            };
            case JUNGLE -> switch (width) {
                case 1 -> 5;
                case 2 -> 8;
                case 3 -> 12;
                default -> 16;
            };
            case MANGROVE, PALE_OAK -> switch (width) {
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 6;
                default -> 10;
            };
            default -> 0;
        };
    }

    private int heightBonus(int width) {
        return heightBonus(species, width);
    }

    // ------------------------------------------------------------------ oak

    private void buildOak(StructurePos base, int width, int height, int radius) {
        int blobChance = width > 1 ? 0 : switch (form) {
            case OPEN_GROWN -> 60;
            case FOREST -> 45;
            case WINDSWEPT -> 30;
            case ANCIENT -> 0;
        };
        if (random.nextInt(100) < blobChance) {
            blobTree(base, 4 + random.nextInt(3) + (form == TreeForm.FOREST ? 1 : 0));
            return;
        }
        if (form == TreeForm.ANCIENT) {
            leafVineChance = 0.2;
        }
        fancyTree(base, width, height, radius, width <= 2 ? 3 : 4);
    }

    /** Vanilla oak/birch/jungle: straight trunk with the 4-row blob on top. */
    private void blobTree(StructurePos base, int trunkHeight) {
        int[] lean = form == TreeForm.WINDSWEPT ? dominantDirection : null;
        StructurePos top = trunk(base, 1, trunkHeight, trunkHeight - 2, lean, lean == null ? 0 : 1, false);
        blobFoliage(new Anchor(top.x(), top.y() + 1, top.z(), 0), 2);
    }

    /** Vanilla BlobFoliagePlacer: radius-1 rows above radius rows, corners dropped at random, always on top. */
    private void blobFoliage(Anchor anchor, int radius) {
        for (int y = 0; y >= -3; y--) {
            int range = Math.max(radius - 1 - y / 2, 0);
            boolean top = y == 0;
            row(anchor.x(), anchor.y() + y, anchor.z(), range, anchor.span(),
                    (localX, localZ, r) -> localX == r && localZ == r && (top || random.nextInt(2) == 0));
        }
    }

    /**
     * Vanilla fancy oak (FancyTrunkPlacer): the trunk ends at about 62 % of the height, round leaf
     * clusters sit on limbs that rise from the trunk; bigger trees get bigger and fewer clusters.
     */
    private void fancyTree(StructurePos base, int width, int height, int radius, int clusterRadius) {
        double trunkFraction = switch (form) {
            case FOREST -> 0.66;
            case ANCIENT -> 0.55;
            default -> 0.618;
        };
        int trunkHeight = Math.max(3, (int) (height * trunkFraction));
        int[] lean = form == TreeForm.WINDSWEPT ? dominantDirection : null;
        StructurePos top = trunk(base, width, trunkHeight, trunkHeight / 2, lean,
                lean == null ? 0 : 1 + width / 2, width >= 3);
        if (width >= 3) {
            rootFlare(base, width);
        }
        StructurePos trunkTop = center(top, width);
        int layers = clusterRadius + 2;
        double vanillaReach = height / 4.0 * 1.328;
        double widthScale = width == 1 ? 1.0 : Math.max(0.6, (radius - clusterRadius * 0.6) / vanillaReach);
        if (form == TreeForm.OPEN_GROWN) {
            widthScale *= 1.15;
        } else if (form == TreeForm.FOREST) {
            widthScale *= 0.85;
        }
        int yStep = Math.max(1, clusterRadius - 2);
        double density = (1.382 + Math.pow(height / 13.0, 2)) * Math.pow(3.0 / clusterRadius, 3) * yStep;
        density *= width >= 3 ? 0.55 : 1.0;
        int perLayer = Math.max(1, (int) Math.round(density * (form == TreeForm.ANCIENT ? 1.2 : 1.0)));

        StructurePos crown = new StructurePos(trunkTop.x(), Math.max(trunkTop.y(), base.y() + height - layers),
                trunkTop.z());
        limb(trunkTop, crown, width >= 3 ? 2 : 1);
        fancyCluster(crown.x(), crown.y(), crown.z(), clusterRadius);

        double windAngle = Math.atan2(dominantDirection[1], dominantDirection[0]);
        for (int y = height - layers; y >= height * 0.3; y -= yStep) {
            double shape = fancyShape(height, y);
            if (shape < 0) {
                continue;
            }
            for (int index = 0; index < perLayer; index++) {
                double distance = widthScale * shape * (random.nextDouble() + 0.328);
                double angle = form == TreeForm.WINDSWEPT && random.nextInt(10) < 7
                        ? windAngle + (random.nextDouble() - 0.5) * 2.2
                        : random.nextDouble() * Math.PI * 2.0;
                int clusterY = base.y() + y - 1;
                StructurePos stem = trunkCenterAt(Math.min(trunkTop.y(), clusterY), trunkTop);
                int clusterX = stem.x() + (int) Math.round(Math.cos(angle) * distance);
                int clusterZ = stem.z() + (int) Math.round(Math.sin(angle) * distance);
                double horizontal = Math.hypot(clusterX - stem.x(), clusterZ - stem.z());
                int branchY = (int) Math.min(trunkTop.y(), clusterY - horizontal * 0.381);
                if (branchY - base.y() < height * 0.2) {
                    continue;
                }
                int thickness = width >= 5 && horizontal > 8 ? 3 : width >= 2 && horizontal > 5 ? 2 : 1;
                limb(trunkCenterAt(branchY, trunkTop), new StructurePos(clusterX, clusterY, clusterZ), thickness);
                fancyCluster(clusterX, clusterY, clusterZ, clusterRadius);
            }
        }
    }

    /** Vanilla FancyTrunkPlacer.treeShape: half-circle profile over the upper 70 % of the tree. */
    private static double fancyShape(int height, int y) {
        if (y < height * 0.3) {
            return -1;
        }
        double half = height / 2.0;
        double adjacent = half - y;
        if (adjacent == 0) {
            return half * 0.5;
        }
        if (Math.abs(adjacent) >= half) {
            return 0;
        }
        return Math.sqrt(half * half - adjacent * adjacent) * 0.5;
    }

    /** Vanilla fancy-oak cluster (radius 3: plus, 3 full rows, plus) scaled by radius; bottom row at {@code y}. */
    private void fancyCluster(int x, int y, int z, int radius) {
        int layers = radius + 2;
        double half = (layers - 1) / 2.0 + 0.6;
        for (int layer = 0; layer < layers; layer++) {
            double t = (layer - (layers - 1) / 2.0) / half;
            roundRow(x, y + layer, z, (radius + 0.3) * Math.sqrt(1 - t * t), 0);
        }
    }

    // ------------------------------------------------------------------ birch

    private void buildBirch(StructurePos base, int width, int height, int radius) {
        if (width == 1 && (form == TreeForm.OPEN_GROWN || form == TreeForm.WINDSWEPT
                || (form == TreeForm.FOREST && random.nextBoolean()))) {
            blobTree(base, Math.max(5, height - 2));
            return;
        }
        columnTree(base, width, height, radius);
    }

    /** Tall birch: the blob stretched into a narrow column along the upper half of the trunk. */
    private void columnTree(StructurePos base, int width, int height, int radius) {
        int[] lean = form == TreeForm.WINDSWEPT ? dominantDirection : null;
        StructurePos top = trunk(base, width, height, height * 2 / 3, lean, lean == null ? 0 : 1, width >= 3);
        if (width >= 3) {
            rootFlare(base, width);
        }
        StructurePos topCenter = center(top, width);
        int maxRadius = width == 1 ? 2 : Math.max(2, (int) Math.round(radius * 0.55));
        double bottomFraction = switch (form) {
            case FOREST -> 0.55;
            case OPEN_GROWN -> 0.38;
            case WINDSWEPT -> 0.45;
            case ANCIENT -> 0.4;
        };
        int bottom = base.y() + (int) (height * bottomFraction);
        int topRow = top.y() + 1;
        int rows = topRow - bottom + 1;
        int wobble = 0;
        for (int y = topRow; y >= bottom; y--) {
            int fromTop = topRow - y;
            int range;
            if (width == 1) {
                range = Math.min(maxRadius, 1 + fromTop / 2);
                int fromBottom = y - bottom;
                if (fromBottom < 2 && range > 1) {
                    range = Math.max(1, range - (2 - fromBottom));
                }
            } else {
                // Elongated egg: narrow top, full middle, pulled-in bottom; neighbouring rows wobble together.
                if (fromTop % 2 == 0) {
                    wobble = random.nextInt(3) - 1;
                }
                double t = (fromTop + 0.5) / rows;
                range = Math.max(1, (int) Math.round(maxRadius * Math.pow(Math.sin(Math.PI * t), 0.55)) + wobble);
            }
            boolean topFlag = fromTop == 0;
            Anchor anchor = anchorAt(y, width, topCenter);
            row(anchor.x(), y, anchor.z(), range, anchor.span(), (localX, localZ, r) ->
                    (localX == r && localZ == r && (topFlag || random.nextInt(2) == 0))
                            || (r >= 3 && square(localX + 0.5) + square(localZ + 0.5) > square(r + 0.3)));
            if (maxRadius > 4 && fromTop > 1 && fromTop % 3 == 0 && y <= top.y()) {
                spokes(trunkCenterAt(y, topCenter), 0, range - 3, 3 + random.nextInt(3), width);
            }
        }
        if (width > 1) {
            // Side lumps break the column silhouette, like the uneven crown of an old birch.
            int lumps = 3 + width;
            int lumpRadius = Math.max(2, maxRadius / 2);
            for (int index = 0; index < lumps; index++) {
                int y = bottom + 2 + random.nextInt(Math.max(1, rows - 5));
                double angle = random.nextDouble() * Math.PI * 2.0;
                double out = maxRadius - 1 + random.nextInt(2) + width / 2.0;
                StructurePos from = trunkCenterAt(Math.min(y - 1, top.y()), topCenter);
                StructurePos end = new StructurePos(from.x() + (int) Math.round(Math.cos(angle) * out), y - 1,
                        from.z() + (int) Math.round(Math.sin(angle) * out));
                limb(from, end, 1);
                fancyCluster(end.x(), end.y(), end.z(), lumpRadius);
            }
        }
    }

    // ------------------------------------------------------------------ spruce

    private void buildSpruce(StructurePos base, int width, int height, int radius) {
        int[] lean = form == TreeForm.WINDSWEPT ? dominantDirection : null;
        if (width > 1) {
            megaSpruce(base, width, height, radius, lean);
            return;
        }
        StructurePos top = trunk(base, 1, height, height * 2 / 3, lean, lean == null ? 0 : 1, false);
        Anchor tip = new Anchor(top.x(), top.y() + 1, top.z(), 0);
        if (form == TreeForm.FOREST && random.nextBoolean()) {
            pineFoliage(tip, height > 9 ? 2 : 1, 3 + random.nextInt(2));
            return;
        }
        int bare = form == TreeForm.OPEN_GROWN ? 1 : 1 + random.nextInt(2);
        int maxRadius = form == TreeForm.OPEN_GROWN || form == TreeForm.ANCIENT ? 3 : 2 + random.nextInt(2);
        spruceFoliage(tip, random.nextInt(2), Math.max(4, height - bare), maxRadius);
    }

    /** Vanilla SpruceFoliagePlacer: rings grow by one per row and restart, giving the layered cone. */
    private void spruceFoliage(Anchor tip, int offset, int foliageHeight, int maxRadius) {
        int range = random.nextInt(2);
        int limit = 1;
        int first = 0;
        StructurePos fallback = new StructurePos(tip.x(), tip.y(), tip.z());
        for (int y = offset; y >= -foliageHeight; y--) {
            StructurePos center = trunkCenterAt(tip.y() + y, fallback);
            row(center.x(), tip.y() + y, center.z(), range, 0,
                    (localX, localZ, r) -> r > 0 && localX == r && localZ == r);
            if (range >= limit) {
                range = first;
                first = 1;
                limit = Math.min(limit + 1, maxRadius);
            } else {
                range++;
            }
        }
    }

    /** Vanilla PineFoliagePlacer: a short tuft at the top of a long bare trunk. */
    private void pineFoliage(Anchor tip, int radius, int foliageHeight) {
        int range = 0;
        for (int y = 1; y >= 1 - foliageHeight; y--) {
            row(tip.x(), tip.y() + y, tip.z(), range, 0,
                    (localX, localZ, r) -> r > 0 && localX == r && localZ == r);
            if (range >= 1 && y == 2 - foliageHeight) {
                range--;
            } else if (range < radius) {
                range++;
            }
        }
    }

    /** Vanilla MegaPineFoliagePlacer: round rows widening towards the bottom, every other row bumped out. */
    private void megaSpruce(StructurePos base, int width, int height, int radius, int[] lean) {
        StructurePos top = trunk(base, width, height, height * 3 / 4, lean, lean == null ? 0 : 1, width >= 3);
        if (width >= 3) {
            rootFlare(base, width);
        }
        StructurePos topCenter = center(top, width);
        double crownFraction = switch (form) {
            case OPEN_GROWN -> 0.8;
            case FOREST -> 0.55;
            case WINDSWEPT -> 0.65;
            case ANCIENT -> 0.75;
        };
        int crownHeight = Math.max(6, (int) (height * crownFraction));
        double maxRadius = width == 2
                ? (form == TreeForm.OPEN_GROWN || form == TreeForm.ANCIENT ? 4.0 : 3.5)
                : Math.max(4.5, radius * 0.62);
        int tipY = top.y() + 1;
        int previous = 0;
        for (int fromTop = 0; fromTop <= crownHeight; fromTop++) {
            int y = tipY - fromTop;
            int level = (int) Math.floor(fromTop / (double) crownHeight * maxRadius);
            boolean bump = fromTop > 0 && level == previous && (y & 1) == 0;
            int range = Math.max(fromTop > 0 ? 1 : 0, bump ? level + 1 : level) + (width >= 5 ? 1 : 0);
            previous = level;
            Anchor anchor = anchorAt(y, width, topCenter);
            if (bump && maxRadius > 5.5 && y < top.y() - 1) {
                spokes(trunkCenterAt(y, topCenter), -1, range - 3, maxRadius > 8 ? 12 : 8, width);
            }
            int soften = width >= 5 ? range : 0;
            row(anchor.x(), y, anchor.z(), range, anchor.span(),
                    (localX, localZ, r) -> localX * localX + localZ * localZ > r * r + soften);
        }
    }

    // ------------------------------------------------------------------ dark oak and pale oak

    private void buildDarkOak(StructurePos base, int width, int height, int radius, boolean pale) {
        if (pale) {
            mossChance = 0.15;
        }
        // Vanilla DarkOakTrunkPlacer: straight trunk that shifts diagonally once near the top.
        int[] direction = form == TreeForm.WINDSWEPT
                ? cardinal(dominantDirection) : CARDINAL_DIRECTIONS[random.nextInt(4)];
        int bendStart = height - 1 - random.nextInt(width >= 3 ? Math.max(2, height / 5) : 4);
        int bendLength = form == TreeForm.WINDSWEPT ? 2 + width / 2
                : width >= 3 ? random.nextInt(2 + width / 2) : 2 - random.nextInt(3);
        StructurePos top = trunk(base, width, height, bendStart, direction, bendLength, false);
        if (width >= 3) {
            trunk(base.add(-1, 0, -1), width + 2, Math.max(2, height / 6), 0, null, 0, false);
            rootFlare(base, width);
        }
        StructurePos topCenter = center(top, width);
        int range = width == 1 ? 3 : width == 2
                ? (form == TreeForm.OPEN_GROWN || form == TreeForm.ANCIENT ? 4 : 3) : radius;
        if (pale && width >= 2) {
            range = Math.max(3, range - 1);
        }
        boolean large = range >= 5;
        if (width >= 3) {
            darkOakLimbs(topCenter, top.y(), width, range);
        } else {
            darkOakStubs(top, width);
        }
        Anchor anchor = anchorAt(top.y() + 1, width, topCenter);
        RowRule round = (localX, localZ, r) -> square(localX + 0.5) + square(localZ + 0.5) > square(r + 0.5);
        int ay = anchor.y();
        if (large) {
            row(anchor.x(), ay - 2, anchor.z(), range - 3, anchor.span(), round);
        }
        row(anchor.x(), ay - 1, anchor.z(), range - 1, anchor.span(), large ? round
                : (localX, localZ, r) -> width == 1 && localX == r && localZ == r);
        row(anchor.x(), ay, anchor.z(), range, anchor.span(), large ? round
                : (localX, localZ, r) -> localX == r && localZ == r);
        row(anchor.x(), ay + 1, anchor.z(), range - 1, anchor.span(),
                (localX, localZ, r) -> localX + localZ > r * 2 - 2 || (large && round.skip(localX, localZ, r)));
        if (large || random.nextBoolean() || pale) {
            int capRange = large ? range - 3 : pale ? 1 : 0;
            row(anchor.x(), ay + 2, anchor.z(), capRange, anchor.span(), large ? round : (localX, localZ, r) -> false);
        }
        if (large && (form == TreeForm.ANCIENT || range >= 9)) {
            row(anchor.x(), ay + 3, anchor.z(), range / 3, anchor.span(), round);
        }
    }

    /** Vanilla dark oak: short logs hanging down around the trunk top, each with a small leaf clump. */
    private void darkOakStubs(StructurePos top, int width) {
        for (int dx = -1; dx <= width; dx++) {
            for (int dz = -1; dz <= width; dz++) {
                boolean inside = dx >= 0 && dx < width && dz >= 0 && dz < width;
                if (inside || random.nextInt(3) != 0) {
                    continue;
                }
                int x = top.x() + dx;
                int z = top.z() + dz;
                int length = 2 + random.nextInt(3);
                for (int step = 0; step < length; step++) {
                    log(x, top.y() - step, z);
                }
                row(x, top.y(), z, 2, 0, (localX, localZ, r) -> localX == r && localZ == r);
                row(x, top.y() + 1, z, 1, 0, (localX, localZ, r) -> false);
            }
        }
    }

    /** Big dark oaks: radial limbs carry the wide slab; their ends drop stubs and leaf lumps underneath. */
    private void darkOakLimbs(StructurePos topCenter, int topY, int width, int range) {
        int limbs = 6 + width + (form == TreeForm.ANCIENT ? 2 : 0);
        double rotation = random.nextDouble() * Math.PI * 2.0;
        for (int index = 0; index < limbs; index++) {
            double angle = rotation + Math.PI * 2.0 * index / limbs + (random.nextDouble() - 0.5) * 0.5;
            double reach = (range - 3) * (0.55 + random.nextDouble() * 0.45) + width / 2.0;
            StructurePos from = trunkCenterAt(topY - 2 - random.nextInt(3), topCenter);
            StructurePos end = new StructurePos(from.x() + (int) Math.round(Math.cos(angle) * reach),
                    topY - 1, from.z() + (int) Math.round(Math.sin(angle) * reach));
            limb(from, end, width >= 5 ? 3 : 2);
            if (random.nextInt(3) == 0) {
                int length = 2 + random.nextInt(3);
                for (int step = 1; step <= length; step++) {
                    branchLog(end.x(), end.y() - step, end.z(), Axis.Y);
                }
            }
            int clump = 2 + random.nextInt(2);
            roundRow(end.x(), end.y() - 1, end.z(), clump + 0.5, 0);
            roundRow(end.x(), end.y(), end.z(), clump + 1.5, 0);
        }
    }

    // ------------------------------------------------------------------ mangrove

    private void buildMangrove(StructurePos base, int width, int height, int radius) {
        leafVineChance = 0.125;
        propaguleChance = 0.14;
        int rise = width == 1 ? 1 + random.nextInt(3) : width == 2 ? 2 + random.nextInt(2)
                : 2 + width + random.nextInt(2);
        StructurePos trunkBase = base.add(0, rise, 0);
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                if (!isRoundedTrunkCell(dx, dz, width)) {
                    continue;
                }
                for (int y = base.y(); y < trunkBase.y(); y++) {
                    placeIfReplaceable(base.x() + dx, y, base.z() + dz, Material.MANGROVE_ROOTS);
                }
            }
        }
        mangroveRoots(base, trunkBase.y(), width, radius);

        int trunkHeight = Math.max(3, height - rise);
        int[] lean = form == TreeForm.WINDSWEPT ? dominantDirection : null;
        StructurePos top = trunk(trunkBase, width, trunkHeight, trunkHeight / 2, lean,
                lean == null ? 0 : 1 + width / 2, width >= 3);
        StructurePos topCenter = center(top, width);
        List<StructurePos> heads = new ArrayList<>();
        heads.add(topCenter.add(0, 1, 0));

        // Vanilla UpwardsBranchingTrunkPlacer: branches climb diagonally away from the trunk.
        double chance = width == 1 ? 0.5 : width == 2 ? 0.45 : 0.55;
        int step = width >= 3 ? 2 : 1;
        for (int k = Math.max(1, trunkHeight * (width >= 3 ? 5 : 3) / 10); k < trunkHeight - 1; k += step) {
            if (random.nextDouble() >= chance) {
                continue;
            }
            int[] direction = width == 1 ? CARDINAL_DIRECTIONS[random.nextInt(4)]
                    : EIGHT_DIRECTIONS[random.nextInt(EIGHT_DIRECTIONS.length)];
            int steps = width == 1 ? 1 + random.nextInt(4) : width == 2 ? 2 + random.nextInt(4)
                    : Math.max(3, radius / 3) + random.nextInt(Math.max(2, radius / 4));
            StructurePos from = trunkCenterAt(trunkBase.y() + k, topCenter);
            int out = steps + width / 2;
            StructurePos end = new StructurePos(from.x() + direction[0] * out, from.y() + steps,
                    from.z() + direction[1] * out);
            limb(from, end, width >= 3 ? 2 : 1);
            if (random.nextBoolean()) {
                branchLog(end.x(), end.y() + 1, end.z(), Axis.Y);
                end = end.add(0, 1, 0);
            }
            heads.add(end.add(0, 1, 0));
        }
        if (width >= 3) {
            // A ring of crown branches spreads the canopy of big mangroves over the roots.
            int crownBranches = 4 + width / 2 + random.nextInt(3);
            double rotation = random.nextDouble() * Math.PI * 2.0;
            for (int index = 0; index < crownBranches; index++) {
                double angle = rotation + Math.PI * 2.0 * index / crownBranches + (random.nextDouble() - 0.5) * 0.4;
                double reach = radius * (0.4 + random.nextDouble() * 0.25) + width / 2.0;
                StructurePos from = trunkCenterAt(top.y() - 2 - random.nextInt(4), topCenter);
                StructurePos end = new StructurePos(from.x() + (int) Math.round(Math.cos(angle) * reach),
                        from.y() + 2 + random.nextInt(4), from.z() + (int) Math.round(Math.sin(angle) * reach));
                limb(from, end, 2);
                heads.add(end.add(0, 1, 0));
            }
        }

        // Vanilla RandomSpreadFoliagePlacer: many random leaves around each branch end.
        int spread = width == 1 ? 3 : width == 2 ? 4 : Math.min(6, Math.max(4, (int) Math.round(radius * 0.4)));
        int spreadHeight = width == 1 ? 2 : 3;
        int attempts = (int) (70 * Math.pow(spread / 3.0, 2) * spreadHeight / 2.0);
        for (StructurePos head : heads) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                leaves(head.x() + random.nextInt(spread) - random.nextInt(spread),
                        head.y() + random.nextInt(spreadHeight) - random.nextInt(spreadHeight),
                        head.z() + random.nextInt(spread) - random.nextInt(spread));
            }
        }
    }

    /** Vanilla MangroveRootPlacer: roots arch out of the raised trunk base and down to the ground. */
    private void mangroveRoots(StructurePos base, int trunkBaseY, int width, int radius) {
        int reach = width == 1 ? 3 : width == 2 ? 4 : Math.max(5, (int) Math.round(radius * 0.55));
        for (int[] direction : EIGHT_DIRECTIONS) {
            boolean diagonal = direction[0] != 0 && direction[1] != 0;
            int count = diagonal ? (width >= 3 ? 1 : random.nextInt(2)) : width == 1 ? 1 : width == 2 ? 2 : width - 1;
            for (int index = 0; index < count; index++) {
                if (random.nextInt(5) == 0) {
                    continue;
                }
                int lateral = count == 1 ? width / 2 : index * (width - 1) / Math.max(1, count - 1);
                int x = direction[0] > 0 ? base.x() + width - 1 : direction[0] < 0 ? base.x() : base.x() + lateral;
                int z = direction[1] > 0 ? base.z() + width - 1 : direction[1] < 0 ? base.z() : base.z() + lateral;
                if (diagonal) {
                    x = direction[0] > 0 ? base.x() + width - 1 : base.x();
                    z = direction[1] > 0 ? base.z() + width - 1 : base.z();
                }
                int y = trunkBaseY - random.nextInt(Math.min(2, Math.max(1, trunkBaseY - base.y())));
                int length = reach - (diagonal ? 1 : 0) + random.nextInt(2);
                for (int travelled = 1; travelled <= length + 2 && y >= base.y(); travelled++) {
                    x += direction[0];
                    z += direction[1];
                    if (!diagonal && random.nextInt(4) == 0) {
                        int side = random.nextBoolean() ? 1 : -1;
                        x += direction[1] * side;
                        z += direction[0] * side;
                    }
                    root(x, y, z, width >= 3 && travelled < length / 2, direction);
                    int drop = y - base.y();
                    int remaining = length - travelled;
                    if (drop > 0 && (remaining <= drop || random.nextBoolean())) {
                        y--;
                        root(x, y, z, width >= 3 && travelled < length / 2, direction);
                    } else if (drop == 0) {
                        break;
                    }
                }
            }
        }
    }

    private void root(int x, int y, int z, boolean thick, int[] direction) {
        placeIfReplaceable(x, y, z, Material.MANGROVE_ROOTS);
        if (thick) {
            placeIfReplaceable(x + Math.abs(direction[1]), y, z + Math.abs(direction[0]), Material.MANGROVE_ROOTS);
        }
    }

    private boolean isRowEdge(int x, int y, int z) {
        for (int[] direction : CARDINAL_DIRECTIONS) {
            if (!isCurrentLeaf(new StructurePos(x + direction[0], y, z + direction[1]))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ shrubs and hedges

    /** Vanilla bush: one log with a 5×5, a 3×3 and a single leaf row above it. */
    private void buildShrubGroup(List<StructurePos> positions) {
        for (StructurePos position : positions) {
            int x = position.x();
            int z = position.z();
            log(x, position.y(), z);
            row(x, position.y(), z, 2, 0, (localX, localZ, r) -> localX == r && localZ == r && random.nextBoolean());
            row(x, position.y() + 1, z, 1, 0, (localX, localZ, r) -> localX == r && localZ == r && random.nextBoolean());
            leaves(x, position.y() + 2, z);
        }
    }

    private void buildHedge(List<StructurePos> positions) {
        Set<StructurePos> members = new HashSet<>(positions);
        for (StructurePos position : positions) {
            int x = position.x();
            int z = position.z();
            log(x, position.y(), z);
            branchLog(x, position.y() + 1, z, hedgeAxis(position, members));
            if (Math.floorMod(x * 31 + z, 3) == 1) {
                log(x, position.y() + 2, z);
            }

            int topY = position.y() + 2 + (random.nextDouble() < 0.24 ? 1 : 0);
            for (int y = position.y(); y <= topY; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0 && y <= position.y() + 1) {
                            continue;
                        }
                        boolean outerCorner = Math.abs(dx) + Math.abs(dz) == 2;
                        if ((outerCorner && random.nextDouble() < 0.38)
                                || (y == topY && (dx != 0 || dz != 0)
                                && random.nextDouble() < 0.34)) {
                            continue;
                        }
                        leaves(x + dx, y, z + dz);
                    }
                }
            }
        }
    }

    private Axis hedgeAxis(StructurePos position, Set<StructurePos> members) {
        int xConnections = 0;
        int zConnections = 0;
        for (int[] direction : EIGHT_DIRECTIONS) {
            if (!members.contains(position.add(direction[0], 0, direction[1]))) {
                continue;
            }
            xConnections += Math.abs(direction[0]);
            zConnections += Math.abs(direction[1]);
        }
        if (xConnections == zConnections) {
            return Math.floorMod(position.x() + position.z(), 2) == 0 ? Axis.X : Axis.Z;
        }
        return xConnections > zConnections ? Axis.X : Axis.Z;
    }

    // ------------------------------------------------------------------ trunks and limbs

    /**
     * Builds a width×width trunk (round from width 5). From {@code leanFrom} on it steps one block per
     * layer along {@code lean} while {@code shifts} remain, like the vanilla forking and dark-oak bends.
     * Tapered trunks lose one ring in their top fifth. Returns the lower corner of the top layer.
     */
    private StructurePos trunk(StructurePos base, int width, int height, int leanFrom, int[] lean,
            int shifts, boolean taper) {
        int x = base.x();
        int z = base.z();
        int remaining = shifts;
        for (int k = 0; k < height; k++) {
            if (lean != null && k >= leanFrom && remaining > 0) {
                x += lean[0];
                z += lean[1];
                remaining--;
            }
            int layerWidth = taper && width >= 3 && k >= height * 0.8 ? width - 2 : width;
            int inset = (width - layerWidth) / 2;
            int y = base.y() + k;
            for (int dx = 0; dx < layerWidth; dx++) {
                for (int dz = 0; dz < layerWidth; dz++) {
                    if (isRoundedTrunkCell(dx, dz, layerWidth)) {
                        log(x + inset + dx, y, z + inset + dz);
                    }
                }
            }
            trunkCenters.putIfAbsent(y, new StructurePos(x + width / 2, y, z + width / 2));
        }
        return new StructurePos(x, base.y() + height - 1, z);
    }

    private static boolean isRoundedTrunkCell(int dx, int dz, int width) {
        if (width < 5) {
            return true;
        }
        double center = (width - 1) / 2.0;
        double radius = width / 2.0;
        double offsetX = dx - center;
        double offsetZ = dz - center;
        return offsetX * offsetX + offsetZ * offsetZ <= radius * radius;
    }

    private static StructurePos center(StructurePos corner, int width) {
        return new StructurePos(corner.x() + width / 2, corner.y(), corner.z() + width / 2);
    }

    private StructurePos trunkCenterAt(int y, StructurePos fallback) {
        return trunkCenters.getOrDefault(y, new StructurePos(fallback.x(), y, fallback.z()));
    }

    private Anchor anchorAt(int y, int width, StructurePos fallbackCenter) {
        StructurePos center = trunkCenterAt(y, fallbackCenter);
        return width == 2
                ? new Anchor(center.x() - 1, y, center.z() - 1, 1)
                : new Anchor(center.x(), y, center.z(), 0);
    }

    /** Buttress roots around a 3×3/5×5 base: short, falling log runs in up to eight directions. */
    private void rootFlare(StructurePos base, int width) {
        int centerX = base.x() + width / 2;
        int centerZ = base.z() + width / 2;
        int reach = 1 + width / 2;
        for (int[] direction : EIGHT_DIRECTIONS) {
            if (random.nextInt(4) == 0) {
                continue;
            }
            boolean diagonal = direction[0] != 0 && direction[1] != 0;
            int length = reach - (diagonal ? 1 : 0) + random.nextInt(2);
            int edge = width / 2 - (diagonal && width >= 5 ? 1 : 0);
            for (int step = 1; step <= length; step++) {
                int x = centerX + direction[0] * (edge + step);
                int z = centerZ + direction[1] * (edge + step);
                int rise = length - step;
                for (int dy = 0; dy <= rise; dy++) {
                    branchLog(x, base.y() + dy, z, dy < rise ? Axis.Y : axisFor(direction[0], direction[1]));
                }
            }
        }
    }

    /**
     * A straight run of logs from {@code from} to {@code to} (axis along its main direction, vertical only
     * when it is clearly steep). Thickness 2 adds a parallel run, 3 a cross, both on the inner part only.
     */
    private void limb(StructurePos from, StructurePos to, int thickness) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int dz = to.z() - from.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
        Axis axis = Math.abs(dy) > horizontal * 2 ? Axis.Y : Math.abs(dx) >= Math.abs(dz) ? Axis.X : Axis.Z;
        int[][] double2 = switch (axis) {
            case Y -> new int[][] {{1, 0, 0}, {0, 0, 1}, {1, 0, 1}};
            case X -> new int[][] {{0, -1, 0}, {0, 0, 1}, {0, -1, 1}};
            case Z -> new int[][] {{0, -1, 0}, {1, 0, 0}, {1, -1, 0}};
        };
        int[][] cross3 = switch (axis) {
            case Y -> new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            case X -> new int[][] {{0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            case Z -> new int[][] {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}};
        };
        for (int step = 0; step <= steps; step++) {
            double fraction = steps == 0 ? 0 : step / (double) steps;
            int x = from.x() + (int) Math.round(dx * fraction);
            int y = from.y() + (int) Math.round(dy * fraction);
            int z = from.z() + (int) Math.round(dz * fraction);
            branchLog(x, y, z, axis);
            int[][] extra = thickness >= 3 && fraction < 0.5 ? cross3
                    : thickness >= 2 && fraction < 0.6 ? double2 : null;
            if (extra != null) {
                for (int[] offset : extra) {
                    branchLog(x + offset[0], y + offset[1], z + offset[2], axis);
                }
            }
        }
    }

    /** Hidden horizontal limbs from a trunk centre, so wide leaf rows stay within leaf distance. */
    private void spokes(StructurePos center, int dy, int length, int count, int width) {
        if (length < 1) {
            return;
        }
        double rotation = random.nextDouble() * Math.PI * 2.0;
        for (int index = 0; index < count; index++) {
            double angle = rotation + Math.PI * 2.0 * index / count + (random.nextDouble() - 0.5) * 0.3;
            double reach = length + width / 2.0;
            StructurePos from = center.add(0, dy, 0);
            limb(from, new StructurePos(from.x() + (int) Math.round(Math.cos(angle) * reach),
                    from.y() - (length > 5 ? 1 : 0), from.z() + (int) Math.round(Math.sin(angle) * reach)), 1);
        }
    }

    private static int[] cardinal(int[] direction) {
        return direction[0] != 0 ? new int[] {direction[0], 0} : new int[] {0, direction[1]};
    }

    // ------------------------------------------------------------------ leaf rows

    private static int local(int offset, int span) {
        return offset < 0 ? -offset : offset > span ? offset - span : 0;
    }

    /** A square leaf row of {@code range} around the anchor (widened by {@code span}), filtered by the rule. */
    private void row(int x, int y, int z, int range, int span, RowRule rule) {
        if (range < 0) {
            return;
        }
        for (int dx = -range; dx <= range + span; dx++) {
            for (int dz = -range; dz <= range + span; dz++) {
                if (!rule.skip(local(dx, span), local(dz, span), range)) {
                    leaves(x + dx, y, z + dz);
                }
            }
        }
    }

    /** A round leaf row: cells whose far corner lies within {@code radius} (vanilla fancy-oak test). */
    private void roundRow(int x, int y, int z, double radius, int span) {
        if (radius < 0.5) {
            return;
        }
        int range = (int) Math.ceil(radius);
        double limit = radius * radius;
        for (int dx = -range; dx <= range + span; dx++) {
            for (int dz = -range; dz <= range + span; dz++) {
                if (square(local(dx, span) + 0.5) + square(local(dz, span) + 0.5) <= limit) {
                    leaves(x + dx, y, z + dz);
                }
            }
        }
    }

    private static double square(double value) {
        return value * value;
    }

    // ------------------------------------------------------------------ decorations

    private void decorate(StructurePos base) {
        if (trunkVines) {
            for (StructurePos log : List.copyOf(placedLogs)) {
                if (!isOwnLog(log)) {
                    continue;
                }
                for (BlockFace side : SIDES) {
                    if (random.nextInt(3) > 0) {
                        vine(log.x() + side.getModX(), log.y(), log.z() + side.getModZ(), side.getOppositeFace(), 1);
                    }
                }
            }
        }
        if (cocoaChance > 0 && random.nextDouble() < cocoaChance) {
            for (StructurePos log : List.copyOf(placedLogs)) {
                if (log.y() - base.y() > 2 || !isOwnLog(log)) {
                    continue;
                }
                for (BlockFace side : SIDES) {
                    if (random.nextDouble() <= 0.25) {
                        cocoa(log.x() + side.getModX(), log.y(), log.z() + side.getModZ(), side.getOppositeFace());
                    }
                }
            }
        }
        List<StructurePos> leaves = placedLeaves.stream().filter(this::isCurrentLeaf).toList();
        if (leafVineChance > 0) {
            for (StructurePos leaf : leaves) {
                for (BlockFace side : SIDES) {
                    if (random.nextDouble() < leafVineChance) {
                        vine(leaf.x() + side.getModX(), leaf.y(), leaf.z() + side.getModZ(), side.getOppositeFace(), 4);
                    }
                }
            }
        }
        if (mossChance > 0) {
            for (StructurePos leaf : leaves) {
                if (isFree(leaf.x(), leaf.y() - 1, leaf.z()) && random.nextDouble() < mossChance) {
                    int length = 1;
                    while (length < 5 && random.nextBoolean() && isFree(leaf.x(), leaf.y() - length - 1, leaf.z())) {
                        length++;
                    }
                    for (int step = 1; step <= length; step++) {
                        BlockData data = createData(Vystorm.vystorm_core.compat.ServerCompat.material("PALE_HANGING_MOSS", Material.VINE));
                        Vystorm.vystorm_core.compat.internal.BlockAdapters.tip(data, step == length);
                        place(leaf.x(), leaf.y() - step, leaf.z(), Vystorm.vystorm_core.compat.ServerCompat.material("PALE_HANGING_MOSS", Material.VINE), data);
                    }
                }
            }
        }
        if (propaguleChance > 0) {
            Set<StructurePos> propagules = new HashSet<>();
            for (StructurePos leaf : leaves) {
                StructurePos below = leaf.add(0, -1, 0);
                if (!isFree(below.x(), below.y(), below.z()) || !isFree(below.x(), below.y() - 1, below.z())
                        || random.nextDouble() >= propaguleChance || nearOther(below, propagules)) {
                    continue;
                }
                BlockData data = createData(Material.MANGROVE_PROPAGULE);
                if (data instanceof Hangable hangable) {
                    hangable.setHanging(true);
                }
                if (data instanceof Ageable ageable) {
                    ageable.setAge(random.nextInt(ageable.getMaximumAge() + 1));
                }
                place(below.x(), below.y(), below.z(), Material.MANGROVE_PROPAGULE, data);
                propagules.add(below);
            }
        }
    }

    private static boolean nearOther(StructurePos position, Set<StructurePos> others) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (others.contains(position.add(dx, 0, dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOwnLog(StructurePos position) {
        return access.contains(position.x(), position.y(), position.z())
                && access.getType(position.x(), position.y(), position.z()) == species.log;
    }

    /** A vine attached to {@code face} of its cell, hanging down up to {@code length} free blocks. */
    private void vine(int x, int y, int z, BlockFace face, int length) {
        for (int step = 0; step < length && isFree(x, y - step, z); step++) {
            BlockData data = createData(Material.VINE);
            if (data instanceof MultipleFacing facing) {
                facing.setFace(face, true);
            }
            place(x, y - step, z, Material.VINE, data);
        }
    }

    private void cocoa(int x, int y, int z, BlockFace towardsLog) {
        if (!isFree(x, y, z)) {
            return;
        }
        BlockData data = createData(Material.COCOA);
        if (data instanceof Directional directional) {
            directional.setFacing(towardsLog);
        }
        if (data instanceof Ageable ageable) {
            ageable.setAge(random.nextInt(3));
        }
        place(x, y, z, Material.COCOA, data);
    }

    private boolean isFree(int x, int y, int z) {
        return access.contains(x, y, z) && isAir(access.getType(x, y, z));
    }

    private void place(int x, int y, int z, Material material, BlockData data) {
        if (data != null) {
            access.setBlockData(x, y, z, data);
        } else {
            access.setType(x, y, z, material);
        }
    }

    /** Block data for decorations; null without a server (unit tests), then the plain type is placed. */
    private static BlockData createData(Material material) {
        try {
            return material.createBlockData();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    // ------------------------------------------------------------------ block access

    private void log(int x, int y, int z) {
        if (!access.contains(x, y, z)) {
            return;
        }
        Material current = access.getType(x, y, z);
        if (isTrunkReplaceable(current)) {
            access.setType(x, y, z, species.log);
            placedLogs.add(new StructurePos(x, y, z));
        }
    }

    private void branchLog(int x, int y, int z, Axis axis) {
        if (!access.contains(x, y, z)) {
            return;
        }
        Material current = access.getType(x, y, z);
        if (!isTrunkReplaceable(current) || current == species.log) {
            return;
        }
        BlockData data = initialLog.clone();
        if (data instanceof Orientable orientable) {
            orientable.setAxis(axis);
        }
        access.setBlockData(x, y, z, data);
        placedLogs.add(new StructurePos(x, y, z));
    }

    private static Axis axisFor(int dx, int dz) {
        return Math.abs(dx) >= Math.abs(dz) ? Axis.X : Axis.Z;
    }

    private void leaves(int x, int y, int z) {
        if (!access.contains(x, y, z)) {
            return;
        }
        Material current = access.getType(x, y, z);
        if (isLeafReplaceable(current)) {
            access.setBlockData(x, y, z, initialLeaves.clone());
            placedLeaves.add(new StructurePos(x, y, z));
        }
    }

    /** Sets leaf distances like the game would; leaves farther than 6 from a log are removed. */
    private void finishLeaves() {
        Map<StructurePos, Integer> distances = new HashMap<>();
        ArrayDeque<StructurePos> pending = new ArrayDeque<>();
        for (StructurePos position : placedLeaves) {
            if (!isCurrentLeaf(position)) {
                continue;
            }
            int distance = touchesLog(position) ? 1 : MAX_LEAF_DISTANCE + 1;
            distances.put(position, distance);
            if (distance == 1) {
                pending.add(position);
            }
        }

        while (!pending.isEmpty()) {
            StructurePos current = pending.removeFirst();
            int nextDistance = distances.get(current) + 1;
            if (nextDistance > MAX_LEAF_DISTANCE) {
                continue;
            }
            for (int[] direction : NEIGHBORS) {
                StructurePos neighbor = current.add(direction[0], direction[1], direction[2]);
                Integer previous = distances.get(neighbor);
                if (previous != null && nextDistance < previous && isCurrentLeaf(neighbor)) {
                    distances.put(neighbor, nextDistance);
                    pending.addLast(neighbor);
                }
            }
        }

        for (Map.Entry<StructurePos, Integer> entry : distances.entrySet()) {
            StructurePos position = entry.getKey();
            if (!isCurrentLeaf(position)) {
                continue;
            }
            if (entry.getValue() > MAX_LEAF_DISTANCE) {
                access.setType(position.x(), position.y(), position.z(), Material.AIR);
                continue;
            }
            BlockData data = initialLeaves.clone();
            if (data instanceof Leaves leaves) {
                leaves.setPersistent(false);
                leaves.setDistance(entry.getValue());
            }
            access.setBlockData(position.x(), position.y(), position.z(), data);
        }
    }

    private boolean touchesLog(StructurePos position) {
        for (int[] direction : NEIGHBORS) {
            StructurePos neighbor = position.add(direction[0], direction[1], direction[2]);
            if (access.contains(neighbor.x(), neighbor.y(), neighbor.z())
                    && ANY_LOG.contains(access.getType(neighbor.x(), neighbor.y(), neighbor.z()))) {
                return true;
            }
        }
        return false;
    }

    private boolean isCurrentLeaf(StructurePos position) {
        return access.contains(position.x(), position.y(), position.z())
                && TreeSpecies.isLeaves(access.getType(position.x(), position.y(), position.z()));
    }

    private void placeIfReplaceable(int x, int y, int z, Material material) {
        if (access.contains(x, y, z) && isTrunkReplaceable(access.getType(x, y, z))) {
            access.setType(x, y, z, material);
        }
    }

    private static boolean isTrunkReplaceable(Material material) {
        return isAir(material) || ANY_LOG.contains(material) || ANY_LEAVES.contains(material)
                || material == Material.VINE || material == Material.SHORT_GRASS
                || material == Material.TALL_GRASS || material == Material.FERN
                || material == Material.LARGE_FERN || TreeSpecies.fromSapling(material) != null;
    }

    private static boolean isLeafReplaceable(Material material) {
        if (ANY_LOG.contains(material)) {
            return false;
        }
        return isAir(material) || ANY_LEAVES.contains(material) || material == Material.VINE
                || material == Material.SHORT_GRASS || material == Material.FERN;
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }

    private int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
