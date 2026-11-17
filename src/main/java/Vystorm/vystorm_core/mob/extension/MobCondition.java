package Vystorm.vystorm_core.mob.extension;

/** Bedingung für Caster ({@code target == null}) oder ein Ziel. */
@FunctionalInterface
public interface MobCondition {
    boolean test(SkillCall call, SkillTarget target);

    @FunctionalInterface
    interface Factory { MobCondition create(ExtensionArgs args); }
}
