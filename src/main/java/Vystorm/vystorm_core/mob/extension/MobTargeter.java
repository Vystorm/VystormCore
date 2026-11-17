package Vystorm.vystorm_core.mob.extension;

import java.util.List;

/** Liefert Kandidaten; Filter, Sortierung und Limits wendet die Pipeline danach an. */
@FunctionalInterface
public interface MobTargeter {
    List<SkillTarget> select(SkillCall call);

    @FunctionalInterface
    interface Factory { MobTargeter create(ExtensionArgs args); }
}
