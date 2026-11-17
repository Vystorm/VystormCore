package Vystorm.vystorm_core.mob.extension;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.mob.extension.MobExtensions.*;

/**
 * Manager view of {@link MobExtensions}, reached through {@code api.mobs().extensions()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MobExtensions}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MobExtensionsInterface {
    /** The delegating default. Plugins use {@code api.mobs().extensions()}. */
    MobExtensionsInterface DEFAULT = new MobExtensionsInterface() {};

    /** See {@link MobExtensions#registerMechanic}. */
    default void registerMechanic(Plugin owner, String id, MobMechanic.Factory factory) { MobExtensions.registerMechanic(owner, id, factory); }

    /** See {@link MobExtensions#registerTargeter}. */
    default void registerTargeter(Plugin owner, String id, MobTargeter.Factory factory) { MobExtensions.registerTargeter(owner, id, factory); }

    /** See {@link MobExtensions#registerCondition}. */
    default void registerCondition(Plugin owner, String id, MobCondition.Factory factory) { MobExtensions.registerCondition(owner, id, factory); }

    /** See {@link MobExtensions#registerTrigger}. */
    default void registerTrigger(Plugin owner, String id) { MobExtensions.registerTrigger(owner, id); }

    /** See {@link MobExtensions#registerBrain}. */
    default void registerBrain(Plugin owner, MobBrainProfile profile) { MobExtensions.registerBrain(owner, profile); }

    /** See {@link MobExtensions#registerTrait}. */
    default void registerTrait(Plugin owner, MobTrait trait) { MobExtensions.registerTrait(owner, trait); }

    /** See {@link MobExtensions#registerEffect}. */
    default void registerEffect(Plugin owner, MobEffect effect) { MobExtensions.registerEffect(owner, effect); }

    /** See {@link MobExtensions#mechanic}. */
    default Optional<MobMechanic.Factory> mechanic(String id) { return MobExtensions.mechanic(id); }

    /** See {@link MobExtensions#targeter}. */
    default Optional<MobTargeter.Factory> targeter(String id) { return MobExtensions.targeter(id); }

    /** See {@link MobExtensions#condition}. */
    default Optional<MobCondition.Factory> condition(String id) { return MobExtensions.condition(id); }

    /** See {@link MobExtensions#brain}. */
    default Optional<MobBrainProfile> brain(String id) { return MobExtensions.brain(id); }

    /** See {@link MobExtensions#trait}. */
    default Optional<MobTrait> trait(String id) { return MobExtensions.trait(id); }

    /** See {@link MobExtensions#effect}. */
    default Optional<MobEffect> effect(String id) { return MobExtensions.effect(id); }

    /** See {@link MobExtensions#triggerKnown}. */
    default boolean triggerKnown(String id) { return MobExtensions.triggerKnown(id); }

    /** See {@link MobExtensions#ids}. */
    default Set<String> ids(Kind kind) { return MobExtensions.ids(kind); }

    /** See {@link MobExtensions#sink}. */
    default void sink(TriggerSink sink) { MobExtensions.sink(sink); }

    /** See {@link MobExtensions#sink}. */
    default void sink(Plugin owner, TriggerSink sink) { MobExtensions.sink(owner, sink); }

    /** See {@link MobExtensions#fireTrigger}. */
    default void fireTrigger(UUID mobId, String trigger, Entity cause) { MobExtensions.fireTrigger(mobId, trigger, cause); }

    /** See {@link MobExtensions#hasSink}. */
    default boolean hasSink() { return MobExtensions.hasSink(); }

    /** See {@link MobExtensions#unregisterAll}. */
    default void unregisterAll(Plugin owner) { MobExtensions.unregisterAll(owner); }
}
