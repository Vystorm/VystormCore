package Vystorm.vystorm_core.mythic;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Gemeinsamer, optionaler Zugriff auf MythicMobs per Reflection. MythicMobs wird nie zur harten Abhängigkeit:
 * fehlt es, liefern alle Methoden "nicht verfügbar" und Aufrufer fallen auf Vanilla zurück.
 * <p>
 * Seit 0.12.0 ist MythicMobs nur noch Rückfall: Mobs zuerst über {@code mob.MobRefs} (Vystorm-Mobs), Items über
 * {@code item.ItemRefs} (Vystorm-Items). Diese Klasse direkt nur nutzen, wenn ausdrücklich MythicMobs gemeint ist.
 * <p>Preferred access: {@code api.mobs().mythic()}.
 */
public final class MythicMobsBridge {
    private MythicMobsBridge() {}

    /** Ein Spawn wurde versucht, das Ergebnis ist aber unbekannt: Aufrufer dürfen ihn nicht wiederholen. */
    public static final class UnknownOutcomeException extends RuntimeException {
        public UnknownOutcomeException(String message, Throwable cause) { super(message, cause); }
    }

    public static boolean available() {
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        return mythic != null && mythic.isEnabled();
    }

    /** Wie {@link #available()}, aber {@code false} statt Exception ohne laufenden Server (Tests). @since 0.12.0 */
    public static boolean active() {
        return Bukkit.getServer() != null && available();
    }

    /**
     * Ein MythicMobs-Item ({@code getItemManager().getItemStack(id)}) als Kopie mit {@code amount}; leer, wenn
     * MythicMobs fehlt oder die ID unbekannt ist. Rückfall hinter den Vystorm-Providern in {@code ItemRefs}.
     *
     * @since 0.12.0
     */
    public static Optional<ItemStack> item(String id, int amount) {
        if (id == null || id.isBlank() || !active()) return Optional.empty();
        try {
            Class<?> apiClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, mythicLoader());
            Object api = apiClass.getMethod("inst").invoke(null);
            Object items = api.getClass().getMethod("getItemManager").invoke(api);
            Object stack = items.getClass().getMethod("getItemStack", String.class).invoke(items, id);
            if (!(stack instanceof ItemStack item) || item.getType().isAir()) return Optional.empty();
            ItemStack copy = item.clone();
            copy.setAmount(Math.max(1, amount));
            return Optional.of(copy);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** Ob MythicMobs einen Mob mit dieser ID kennt. */
    public static boolean exists(String mobId) {
        try { return mob(mobId).isPresent(); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return false; }
    }

    /**
     * Spawnt einen MythicMob. Liefert {@code null}, wenn MythicMobs fehlt oder die ID unbekannt ist (sicher, auf
     * Vanilla auszuweichen). Wirft {@link UnknownOutcomeException}, wenn der Spawn-Aufruf selbst scheiterte.
     */
    public static Entity spawn(String mobId, Location location, int level) {
        if (mobId == null || mobId.isBlank() || !available()) return null;
        Object mob;Object mythicLocation;Method spawn;
        try {
            var found = mob(mobId);
            if (found.isEmpty()) return null;
            mob = found.get();
            ClassLoader loader = mythicLoader();
            Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, loader);
            mythicLocation = adapter.getMethod("adapt", Location.class).invoke(null, location);
            spawn = findSpawn(mob.getClass(), mythicLocation.getClass());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw new IllegalStateException("MythicMobs unavailable: " + failure.getMessage(), failure);
        }
        // Ab hier kann eine Entity entstanden sein.
        try {
            Object active = spawn.getParameterCount() == 1
                    ? spawn.invoke(mob, mythicLocation)
                    : spawn.invoke(mob, mythicLocation, numeric(level, spawn.getParameterTypes()[1]));
            Entity entity = extractEntity(active);
            if (entity == null) throw new IllegalStateException("MythicMobs returned no Bukkit entity");
            return entity;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw new UnknownOutcomeException("MythicMobs spawn outcome is unknown", failure);
        }
    }

    /**
     * Installiert eine mitgelieferte MythicMobs-Mob-Datei nach {@code plugins/MythicMobs/Mobs/<fileName>} und lädt
     * MythicMobs danach einmal neu. Eine vorhandene Datei wird nur ersetzt, wenn sie noch dieselbe erste Zeile
     * (Verwaltungsmarke, z. B. {@code # managed-by: SpawnDirector}) trägt; entfernt ein Admin die Marke, bleiben seine
     * Änderungen erhalten. Liefert, ob etwas installiert wurde.
     */
    public static boolean installPack(Plugin owner, String resource, String fileName) throws IOException {
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythic == null) return false;
        Path target = mythic.getDataFolder().toPath().resolve("Mobs").resolve(fileName);
        byte[] bundled;
        try (InputStream in = owner.getResource(resource)) {
            if (in == null) throw new IOException("Resource missing: " + resource);
            bundled = in.readAllBytes();
        }
        if (Files.exists(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (Arrays.equals(existing, bundled)) return false;
            if (!firstLine(existing).equals(firstLine(bundled)) || !firstLine(bundled).startsWith("# managed-by:")) {
                owner.getLogger().info("MythicMobs file " + target + " was edited and is left unchanged.");
                return false;
            }
        }
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(fileName + ".tmp");
        Files.write(temp, bundled);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        owner.getLogger().info("MythicMobs pack installed: " + target);
        if (mythic.isEnabled()) Bukkit.getScheduler().runTask(owner, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mythicmobs reload"));
        return true;
    }

    private static String firstLine(byte[] content) {
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).trim();
    }

    private static ClassLoader mythicLoader() {
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythic == null) throw new IllegalStateException("MythicMobs missing");
        return mythic.getClass().getClassLoader();
    }

    private static Optional<Object> mob(String mobId) throws ReflectiveOperationException {
        if (!available()) return Optional.empty();
        Class<?> apiClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, mythicLoader());
        Object api = apiClass.getMethod("inst").invoke(null);
        Object manager = apiClass.getMethod("getMobManager").invoke(api);
        Object result = manager.getClass().getMethod("getMythicMob", String.class).invoke(manager, mobId);
        return Optional.ofNullable(result instanceof Optional<?> optional ? optional.orElse(null) : result);
    }

    private static Method findSpawn(Class<?> type, Class<?> locationType) throws NoSuchMethodException {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals("spawn"))
                .filter(method -> { Class<?>[] p = method.getParameterTypes(); return (p.length == 1 || p.length == 2 && isNumeric(p[1])) && p[0].isAssignableFrom(locationType); })
                .min(Comparator.comparingInt((Method method) -> method.getParameterCount() == 2 ? 0 : 1)
                        .thenComparingInt(method -> distance(locationType, method.getParameterTypes()[0]))
                        .thenComparingInt(method -> method.getParameterCount() == 2 ? rank(method.getParameterTypes()[1]) : 0)
                        .thenComparing(Method::toGenericString))
                .orElseThrow(() -> new NoSuchMethodException("No compatible MythicMob.spawn method"));
    }

    private static int distance(Class<?> actual, Class<?> accepted) {
        int distance = 0;
        for (Class<?> c = actual; c != null; c = c.getSuperclass(), distance++) if (c == accepted) return distance;
        return 1_000;
    }

    private static Object numeric(int value, Class<?> type) {
        if (type == int.class || type == Integer.class) return value;
        if (type == float.class || type == Float.class) return (float) value;
        if (type == long.class || type == Long.class) return (long) value;
        if (type == double.class || type == Double.class) return (double) value;
        throw new IllegalArgumentException("Unsupported MythicMobs level type: " + type.getName());
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == Integer.class || type == double.class || type == Double.class
                || type == float.class || type == Float.class || type == long.class || type == Long.class;
    }

    private static int rank(Class<?> type) {
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 1;
        if (type == float.class || type == Float.class) return 2;
        if (type == double.class || type == Double.class) return 3;
        return Integer.MAX_VALUE;
    }

    private static Entity extractEntity(Object active) throws ReflectiveOperationException {
        if (active instanceof Entity entity) return entity;
        if (active == null) return null;
        Object abstractEntity = active.getClass().getMethod("getEntity").invoke(active);
        if (abstractEntity instanceof Entity entity) return entity;
        Object bukkit = abstractEntity.getClass().getMethod("getBukkitEntity").invoke(abstractEntity);
        return bukkit instanceof Entity entity ? entity : null;
    }
}
