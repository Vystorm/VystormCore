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

/**
 * Stabile Erweiterungs-Contracts für Mob-Content: Mechanics, Targeter, Conditions, Trigger, Brains, Traits und
 * Effects werden ohne Kenntnis der Runtime-Klassen registriert. IDs sind case-insensitiv.
 * <p>Preferred access: {@code api.mobs().extensions()}.
 */
public final class MobExtensions {
    public enum Kind { MECHANIC, TARGETER, CONDITION, TRIGGER, BRAIN, TRAIT, EFFECT }

    private record Entry(Plugin owner, Object value) {}
    private record Sink(Plugin owner, TriggerSink sink) {}

    private static final Map<Kind, Map<String, Entry>> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicReference<Sink> SINK = new AtomicReference<>();

    private MobExtensions() {}

    public static void registerMechanic(Plugin owner, String id, MobMechanic.Factory factory) { put(Kind.MECHANIC, owner, id, factory); }

    public static void registerTargeter(Plugin owner, String id, MobTargeter.Factory factory) { put(Kind.TARGETER, owner, id, factory); }

    public static void registerCondition(Plugin owner, String id, MobCondition.Factory factory) { put(Kind.CONDITION, owner, id, factory); }

    public static void registerTrigger(Plugin owner, String id) { put(Kind.TRIGGER, owner, id, id); }

    public static void registerBrain(Plugin owner, MobBrainProfile profile) { put(Kind.BRAIN, owner, profile.id(), profile); }

    public static void registerTrait(Plugin owner, MobTrait trait) { put(Kind.TRAIT, owner, trait.id(), trait); }

    public static void registerEffect(Plugin owner, MobEffect effect) { put(Kind.EFFECT, owner, effect.id(), effect); }

    public static Optional<MobMechanic.Factory> mechanic(String id) { return get(Kind.MECHANIC, id, MobMechanic.Factory.class); }

    public static Optional<MobTargeter.Factory> targeter(String id) { return get(Kind.TARGETER, id, MobTargeter.Factory.class); }

    public static Optional<MobCondition.Factory> condition(String id) { return get(Kind.CONDITION, id, MobCondition.Factory.class); }

    public static Optional<MobBrainProfile> brain(String id) { return get(Kind.BRAIN, id, MobBrainProfile.class); }

    public static Optional<MobTrait> trait(String id) { return get(Kind.TRAIT, id, MobTrait.class); }

    public static Optional<MobEffect> effect(String id) { return get(Kind.EFFECT, id, MobEffect.class); }

    public static boolean triggerKnown(String id) { return id != null && !id.isBlank() && ENTRIES.getOrDefault(Kind.TRIGGER, Map.of()).containsKey(key(id)); }

    public static Set<String> ids(Kind kind) { return Set.copyOf(ENTRIES.getOrDefault(kind, Map.of()).keySet()); }

    /**
     * Von Vystorm-Mobs gesetzt; empfängt {@link #fireTrigger}. Der Besitzer wird aus dem Classloader der Implementierung
     * ermittelt, damit der Sink beim Disable des Plugins freigegeben wird. {@code null} entfernt den Sink.
     */
    public static void sink(TriggerSink sink) { sink(sink == null ? null : ownerOf(sink), sink); }

    /** Wie {@link #sink(TriggerSink)}, mit explizitem Besitzer. {@code null} als Sink entfernt ihn. @since 0.10.3 */
    public static void sink(Plugin owner, TriggerSink sink) { SINK.set(sink == null ? null : new Sink(owner, sink)); }

    /** Löst einen eigenen Trigger (Signal) an einem Mob aus. */
    public static void fireTrigger(UUID mobId, String trigger, Entity cause) {
        Sink sink = SINK.get();
        if (sink == null || mobId == null || trigger == null || trigger.isBlank()) return;
        if (sink.owner() != null && !sink.owner().isEnabled()) return;
        sink.sink().fire(mobId, key(trigger), cause);
    }

    /** Ob ein aktiver Trigger-Sink gesetzt ist. @since 0.10.3 */
    public static boolean hasSink() {
        Sink sink = SINK.get();
        return sink != null && (sink.owner() == null || sink.owner().isEnabled());
    }

    public static void unregisterAll(Plugin owner) {
        for (Map<String, Entry> map : ENTRIES.values()) map.values().removeIf(entry -> entry.owner() == owner);
        SINK.updateAndGet(current -> current != null && current.owner() == owner ? null : current);
    }

    private static Plugin ownerOf(Object implementation) {
        try {
            return org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(implementation.getClass());
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException notPluginClass) {
            return null;
        }
    }

    private static void put(Kind kind, Plugin owner, String id, Object value) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(value, "value");
        Map<String, Entry> map = ENTRIES.computeIfAbsent(kind, ignored -> new ConcurrentHashMap<>());
        Entry previous = map.get(key(id));
        if (previous != null && previous.owner() != owner) throw new IllegalStateException(kind + " already registered: " + id);
        map.put(key(id), new Entry(owner, value));
    }

    private static <T> Optional<T> get(Kind kind, String id, Class<T> type) {
        if (id == null || id.isBlank()) return Optional.empty();
        Entry entry = ENTRIES.getOrDefault(kind, Map.of()).get(key(id));
        if (entry == null || !entry.owner().isEnabled()) return Optional.empty();
        return type.isInstance(entry.value()) ? Optional.of(type.cast(entry.value())) : Optional.empty();
    }

    private static String key(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("extension id missing");
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
