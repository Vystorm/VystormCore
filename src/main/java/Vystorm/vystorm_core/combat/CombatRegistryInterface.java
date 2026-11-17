package Vystorm.vystorm_core.combat;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import Vystorm.vystorm_core.combat.CombatRegistry.*;

/**
 * Manager view of {@link CombatRegistry}, reached through {@code api.runtime().combat()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link CombatRegistry}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface CombatRegistryInterface {
    /** The delegating default. Plugins use {@code api.runtime().combat()}. */
    CombatRegistryInterface DEFAULT = new CombatRegistryInterface() {};

    /** See {@link CombatRegistry#register}. */
    default void register(Plugin owner, Policy policy) { CombatRegistry.register(owner, policy); }

    /** See {@link CombatRegistry#unregister}. */
    default boolean unregister(Plugin owner, String id) { return CombatRegistry.unregister(owner, id); }

    /** See {@link CombatRegistry#unregisterAll}. */
    default void unregisterAll(Plugin owner) { CombatRegistry.unregisterAll(owner); }

    /** See {@link CombatRegistry#evaluate}. */
    default Decision evaluate(Player attacker, Player target) { return CombatRegistry.evaluate(attacker, target); }

    /** @since 0.26.4 */
    default Decision evaluate(Player attacker, Player target, Kind kind) { return CombatRegistry.evaluate(attacker, target, kind); }

    /**
     * {@link Kind#SUPPORT} if no effect is harmful (Paper's {@code PotionEffectTypeCategory.HARMFUL}), else
     * {@link Kind#HARM}; an empty list counts as harm (e.g. a plain splash of water damaging an enderman is not support).
     *
     * @since 0.26.4
     */
    default Kind kindOf(Iterable<PotionEffect> effects) { return CombatRegistry.kindOf(effects); }

    /** See {@link CombatRegistry#evaluate}. */
    default Decision evaluate(Context context) { return CombatRegistry.evaluate(context); }

    /** See {@link CombatRegistry#policies}. */
    default List<String> policies() { return CombatRegistry.policies(); }
}
