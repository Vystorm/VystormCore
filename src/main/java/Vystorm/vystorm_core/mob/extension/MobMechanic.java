package Vystorm.vystorm_core.mob.extension;

/** Wirkung pro Ziel. Rückgabe false = nicht angewandt (z. B. durch Regeln verhindert). */
@FunctionalInterface
public interface MobMechanic {
    boolean apply(SkillCall call, SkillTarget target);

    @FunctionalInterface
    interface Factory { MobMechanic create(ExtensionArgs args); }
}
