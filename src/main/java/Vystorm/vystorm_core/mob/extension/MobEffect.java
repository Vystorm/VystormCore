package Vystorm.vystorm_core.mob.extension;

import org.bukkit.entity.LivingEntity;

/** Langlebiger Zustand (Aura/Status) mit Start-, Tick-, Refresh- und End-Callbacks. */
public interface MobEffect {
    String id();

    default void onStart(LivingEntity holder, int stacks) {}

    default void onTick(LivingEntity holder, int stacks) {}

    default void onRefresh(LivingEntity holder, int stacks) {}

    default void onEnd(LivingEntity holder) {}
}
