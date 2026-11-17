package Vystorm.vystorm_core.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.entity.Mob;

/**
 * Server navigation bridge. CraftBukkit uses the server's real pathfinder, never velocity steering.
 * <p>Preferred access: {@code api.compat().navigation()}.
 */
public final class MobNavigation {
    private MobNavigation() {}
    private static final boolean PAPER = paper();
    private static boolean paper() {
        try { Mob.class.getMethod("getPathfinder"); return true; }
        catch (NoSuchMethodException absent) { return false; }
    }
    private record NativeAccess(Method handle, Method navigation, Field targets) {}
    private static final ClassValue<NativeAccess> ACCESS = new ClassValue<>() {
        @Override protected NativeAccess computeValue(Class<?> type) {
            try {
                Method handle = type.getMethod("getHandle");
                Class<?> entity = handle.getReturnType();
                return new NativeAccess(handle, entity.getMethod("getNavigation"), entity.getField("targetSelector"));
            } catch (ReflectiveOperationException failure) { throw unsupported(failure); }
        }
    };
    public static boolean moveTo(Mob mob, Location target, double speed) {
        if (target == null || target.getWorld() != mob.getWorld()
                || !target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return false;
        if (PAPER) return Paper.moveTo(mob, target, speed);
        try {
            NativeAccess access = ACCESS.get(mob.getClass());
            Object nav = access.navigation.invoke(access.handle.invoke(mob));
            return (boolean) nav.getClass().getMethod("moveTo", double.class, double.class, double.class, double.class)
                    .invoke(nav, target.getX(), target.getY(), target.getZ(), speed);
        } catch (ReflectiveOperationException failure) { throw unsupported(failure); }
    }
    public static boolean moveTo(Mob mob, org.bukkit.entity.LivingEntity target,double speed) {
        return target!=null && moveTo(mob,target.getLocation(),speed);
    }
    public static void stop(Mob mob) {
        if (PAPER) { Paper.stop(mob); return; }
        try {
            NativeAccess access = ACCESS.get(mob.getClass());
            Object nav = access.navigation.invoke(access.handle.invoke(mob));
            nav.getClass().getMethod("stop").invoke(nav);
        } catch (ReflectiveOperationException failure) { throw unsupported(failure); }
    }
    public static void clearTargets(Mob mob) {
        if (PAPER) { Paper.clearTargets(mob); return; }
        try {
            NativeAccess access = ACCESS.get(mob.getClass());
            Object goals = access.targets.get(access.handle.invoke(mob));
            goals.getClass().getMethod("removeAllGoals", Predicate.class).invoke(goals, (Predicate<Object>) goal -> true);
        } catch (ReflectiveOperationException failure) { throw unsupported(failure); }
    }
    private static IllegalStateException unsupported(Exception cause) {
        return new IllegalStateException("Native navigation is unavailable on this server version", cause);
    }
    /** Linked only when Paper's Mob API exists. */
    private static final class Paper {
        static boolean moveTo(Mob mob, Location target, double speed) { return mob.getPathfinder().moveTo(target, speed); }
        static void stop(Mob mob) { mob.getPathfinder().stopPathfinding(); }
        static void clearTargets(Mob mob) {
            org.bukkit.Bukkit.getMobGoals().removeAllGoals(mob, com.destroystokyo.paper.entity.ai.GoalType.TARGET);
        }
    }
}
