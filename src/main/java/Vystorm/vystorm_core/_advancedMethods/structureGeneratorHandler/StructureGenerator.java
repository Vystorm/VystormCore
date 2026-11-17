package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.Random;

@FunctionalInterface
public interface StructureGenerator {
    /** Executes synchronously on the caller's thread. A failure is propagated and writes made
     * before it are not rolled back. */
    void generate(StructureAccess access, StructurePos origin, Random random);
}
