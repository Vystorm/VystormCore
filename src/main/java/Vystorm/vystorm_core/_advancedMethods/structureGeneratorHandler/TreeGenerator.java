package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.List;

/** Stateful tree builder. Obtain a fresh instance from {@link StructureGeneratorInterface#tree}
 * for each generation; calls write synchronously through the supplied {@link StructureAccess}. */
public interface TreeGenerator {
    void buildNormal(StructurePos pos);
    void buildMedium(StructurePos pos);
    void buildGiant(StructurePos pos, int minHeight, int maxHeight, int radius);
    void buildColossal(StructurePos pos, int minHeight, int maxHeight, int radius);
    void buildConnectedPlant(List<StructurePos> positions, boolean hedge);
}
