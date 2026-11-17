package Vystorm.vystorm_core.mob.extension;

import Vystorm.vystorm_core.mob.CombatRules;
import Vystorm.vystorm_core.mob.MobContext;
import Vystorm.vystorm_core.mob.MobHandle;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * Laufende Skill-Ausführung für Erweiterungen. Zahlen werden über {@link #number} als Formeln aufgelöst
 * (Variablen wie {@code level}, {@code caster.hp}, {@code var.x}). Weltzugriffe nur auf dem Server-Thread.
 */
public interface SkillCall {
    LivingEntity caster();

    Optional<MobHandle> casterMob();

    Location origin();

    Optional<Entity> trigger();

    List<SkillTarget> targets();

    ExtensionArgs args();

    /** Wertet {@code args[key]} als Formel aus. */
    double number(String key, double fallback);

    /** Wertet einen beliebigen Ausdruck im Skill-Kontext aus. */
    double evaluate(String expression, double fallback);

    Object variable(String scopedName);

    void variable(String scopedName, Object value);

    MobContext context();

    default CombatRules rules() { return context().combatRules(); }

    /** Prüft Relations und CombatRules, bevor Schaden/Kontrolle angewandt wird. */
    boolean mayAffect(Entity target, boolean harmful);

    /** Wirkt eine andere Fähigkeit/MetaSkill mit denselben Zielen. */
    void castSkill(String skillId);

    String skillId();
}
