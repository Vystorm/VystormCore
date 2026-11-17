package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

/** Relative shape controls for newly generated trees; never edits existing trees. @since 0.26.5 */
public record TreeGrowthProfile(double heightScale, double crownScale, CrownForm crownForm) {
    public enum CrownForm { RANDOM, OPEN_GROWN, FOREST, WINDSWEPT, ANCIENT }
    public static final TreeGrowthProfile DEFAULT = new TreeGrowthProfile(1, 1, CrownForm.RANDOM);
    public TreeGrowthProfile {
        if (!Double.isFinite(heightScale) || heightScale < 0.5 || heightScale > 1.5
                || !Double.isFinite(crownScale) || crownScale < 0.5 || crownScale > 1.5)
            throw new IllegalArgumentException("Tree scales must be finite and between 0.5 and 1.5");
        java.util.Objects.requireNonNull(crownForm, "crownForm");
    }
    int height(int selected, int ceiling) { return Math.max(4, Math.min(ceiling, (int) Math.round(selected * heightScale))); }
    int radius(int selected) { return Math.max(2, Math.min(40, (int) Math.round(selected * crownScale))); }
}
