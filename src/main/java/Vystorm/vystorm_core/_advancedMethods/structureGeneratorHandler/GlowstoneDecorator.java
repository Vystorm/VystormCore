package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;

final class GlowstoneDecorator {
    private static final int MINIMUM_SPACING_SQUARED = 16;

    private GlowstoneDecorator() {
    }

    static int decorate(StructureAccess access, Set<StructurePos> changedPositions, TreeSpecies species,
            Random random, int leavesPerBlock, int maxBlocks, int darkLightLevel) {
        List<StructurePos> darkLeaves = new ArrayList<>();
        List<StructurePos> otherLeaves = new ArrayList<>();
        for (StructurePos position : changedPositions) {
            if (access.getType(position.x(), position.y(), position.z()) != species.leaves) {
                continue;
            }
            if (access.getLightLevel(position.x(), position.y(), position.z()) <= darkLightLevel) {
                darkLeaves.add(position);
            } else {
                otherLeaves.add(position);
            }
        }

        int leafCount = darkLeaves.size() + otherLeaves.size();
        if (leafCount == 0) {
            return 0;
        }
        int target = Math.min(maxBlocks, Math.max(1,
                (leafCount + leavesPerBlock - 1) / leavesPerBlock));
        Collections.shuffle(darkLeaves, random);
        Collections.shuffle(otherLeaves, random);
        darkLeaves.addAll(otherLeaves);

        List<StructurePos> placed = new ArrayList<>(target);
        for (StructurePos candidate : darkLeaves) {
            if (placed.size() >= target) {
                break;
            }
            if (farEnough(candidate, placed)) {
                access.setType(candidate.x(), candidate.y(), candidate.z(), Material.GLOWSTONE);
                placed.add(candidate);
            }
        }
        return placed.size();
    }

    private static boolean farEnough(StructurePos candidate, List<StructurePos> placed) {
        for (StructurePos existing : placed) {
            long dx = candidate.x() - existing.x();
            long dy = candidate.y() - existing.y();
            long dz = candidate.z() - existing.z();
            if (dx * dx + dy * dy + dz * dz < MINIMUM_SPACING_SQUARED) {
                return false;
            }
        }
        return true;
    }
}
