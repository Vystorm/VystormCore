package Vystorm.vystorm_core.mythic;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Optionaler Zugriff auf MMOItems per Reflection ({@code mmoitems:TYPE:ID}). Nur Rückfall hinter den Vystorm-Providern
 * in {@code item.ItemRefs}; fehlt MMOItems, liefern alle Methoden "nicht verfügbar".
 *
 * <p>Preferred access: {@code api.items().mmoItems()}.
 *
 * @since 0.12.0
 */
public final class MmoItemsBridge {
    private MmoItemsBridge() {}

    public static boolean available() {
        if (Bukkit.getServer() == null) return false;
        Plugin mmo = Bukkit.getPluginManager().getPlugin("MMOItems");
        return mmo != null && mmo.isEnabled();
    }

    /** {@code TYPE:ID} als Kopie mit {@code amount}; leer ohne MMOItems, bei falschem Format oder unbekannter ID. */
    public static Optional<ItemStack> item(String typeAndId, int amount) {
        if (typeAndId == null || !available()) return Optional.empty();
        int colon = typeAndId.indexOf(':');
        if (colon <= 0 || colon == typeAndId.length() - 1) return Optional.empty();
        Plugin mmo = Bukkit.getPluginManager().getPlugin("MMOItems");
        try {
            ClassLoader loader = mmo.getClass().getClassLoader();
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type", true, loader);
            Object type = typeClass.getMethod("get", String.class).invoke(null, typeAndId.substring(0, colon).toUpperCase(Locale.ROOT));
            if (type == null) return Optional.empty();
            Object plugin = mmo.getClass().getField("plugin").get(null);
            Method getItem = plugin.getClass().getMethod("getItem", typeClass, String.class);
            Object item = getItem.invoke(plugin, type, typeAndId.substring(colon + 1).toUpperCase(Locale.ROOT));
            if (!(item instanceof ItemStack stack) || stack.getType().isAir()) return Optional.empty();
            ItemStack copy = stack.clone();
            copy.setAmount(Math.max(1, amount));
            return Optional.of(copy);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    /**
     * {@code TYPE:ID} (Großbuchstaben) eines MMOItems-Stacks; leer ohne MMOItems, für Vanilla-Items oder bei Fehlern.
     *
     * @since 0.26.4
     */
    public static Optional<String> idOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || !available()) return Optional.empty();
        Plugin mmo = Bukkit.getPluginManager().getPlugin("MMOItems");
        try {
            Class<?> main = Class.forName("net.Indyuce.mmoitems.MMOItems", true, mmo.getClass().getClassLoader());
            Object type = main.getMethod("getTypeName", ItemStack.class).invoke(null, stack);
            Object id = main.getMethod("getID", ItemStack.class).invoke(null, stack);
            if (type == null || id == null || type.toString().isBlank() || id.toString().isBlank()) return Optional.empty();
            return Optional.of(type.toString().toUpperCase(Locale.ROOT) + ":" + id.toString().toUpperCase(Locale.ROOT));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }
}
