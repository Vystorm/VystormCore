package Vystorm.vystorm_core.mob;

/**
 * Kontextvorgaben für die Mob-AI. {@code leashRadius <= 0} heißt: Definition entscheidet.
 * {@code aggressionMultiplier} skaliert die Angriffsbereitschaft (Arena höher, Stadt-Neutralität niedriger).
 */
public record AiRules(double aggressionMultiplier, boolean allowFlee, boolean allowPursuitOutsideRegion, double leashRadius,
                      boolean passiveUnlessProvoked) {
    public static final AiRules DEFAULT = new AiRules(1.0, true, true, 0, false);

    public AiRules {
        if (!Double.isFinite(aggressionMultiplier) || aggressionMultiplier < 0) aggressionMultiplier = 1.0;
    }
}
