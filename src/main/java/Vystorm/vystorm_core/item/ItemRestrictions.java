package Vystorm.vystorm_core.item;

import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Core-eigene Sperre für Items, die nie gehandelt, eingelagert oder verkauft werden dürfen (z. B. Besuchs-Werkzeuge der
 * Plugin-Welten). Markiert über den PDC-Schlüssel {@code vystorm_core:restricted} (Wert = Grund). Gesperrte Items prüft
 * {@link ItemServices#validate} als {@link ItemValidation.Status#QUARANTINED} mit der Meldung {@code restricted:<grund>} –
 * damit lehnen Markt (Handelsregeln) und Lager (Deep Inventory) sie ab, auch ohne Item-Provider.
 *
 * <p>Preferred access: {@code api.items().restrictions()}.
 *
 * @since 0.26.0 (Capability {@code item-restrictions})
 */
public final class ItemRestrictions {
    /** Grund für Werkzeuge in Plugin-Welten. */
    public static final String VISIT_TOOL = "visit-tool";
    private static final NamespacedKey KEY = new NamespacedKey("vystorm_core", "restricted");
    private static final NamespacedKey LEGACY_VISIT_TOOL = new NamespacedKey("vystormskills", "tw_item");

    private ItemRestrictions() {}

    /** Markiert das Item (in place) und liefert es zurück; leere Items bleiben unverändert. */
    public static ItemStack mark(ItemStack stack, String reason) {
        if (stack == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(stack)) return stack;
        String value = reason == null || reason.isBlank() ? "restricted" : reason;
        Vystorm.vystorm_core.compat.PlatformCompat.editMeta(stack, meta -> meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, value));
        return stack;
    }

    public static boolean restricted(ItemStack stack) { return reason(stack).isPresent(); }

    /** Sperrgrund oder leer. */
    public static Optional<String> reason(ItemStack stack) {
        if (stack == null) return Optional.empty();
        try {
            if (Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(stack)) return Optional.empty();
            var pdc = stack.getItemMeta().getPersistentDataContainer();
            if (pdc == null) return Optional.empty();
            String reason = pdc.get(KEY, PersistentDataType.STRING);
            if (reason != null) return Optional.of(reason);
            return pdc.has(LEGACY_VISIT_TOOL, PersistentDataType.STRING) ? Optional.of(VISIT_TOOL) : Optional.empty();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
