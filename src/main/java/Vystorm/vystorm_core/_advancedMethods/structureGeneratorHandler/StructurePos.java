package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

public record StructurePos(int x, int y, int z) {
    public StructurePos add(int dx, int dy, int dz) {
        return new StructurePos(x + dx, y + dy, z + dz);
    }
}
