package Vystorm.vystorm_core.runtime;

import Vystorm.vystorm_core.combat.CombatRegistryInterface;
import Vystorm.vystorm_core.curve.CurveRegistryInterface;
import Vystorm.vystorm_core.threat.ThreatServicesInterface;

/**
 * Runtime manager, reached through {@code api.runtime()}: the cross-plugin hubs in which several plugins decide
 * something together at run time (context, actions, combat policies, threat, curves).
 *
 * @since 0.64.0
 */
public interface RuntimeInterface {
    /** The action hub ({@link RuntimeActionHub#get()}). */
    default RuntimeActionHub actions() { return RuntimeActionHub.get(); }

    /** The context hub ({@link RuntimeContextHub#get()}). */
    default RuntimeContextHub context() { return RuntimeContextHub.get(); }

    /** Player-versus-player policies ({@link Vystorm.vystorm_core.combat.CombatRegistry}). */
    default CombatRegistryInterface combat() { return CombatRegistryInterface.DEFAULT; }

    /** Threat scores ({@link Vystorm.vystorm_core.threat.ThreatServices}). */
    default ThreatServicesInterface threat() { return ThreatServicesInterface.DEFAULT; }

    /** Named curves ({@link Vystorm.vystorm_core.curve.CurveRegistry}). */
    default CurveRegistryInterface curves() { return CurveRegistryInterface.DEFAULT; }
}
