package Vystorm.vystorm_core.item;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Anbieter der Item-Engine (Vystorm-Items). Alle Methoden auf dem Server-Thread aufrufen. */
public interface ItemProvider {
    Optional<ItemView> describe(ItemStack stack);

    Optional<ItemStack> create(ItemCreateSpec spec);

    ItemValidation validate(ItemStack stack);

    /** {@code [aktuell, maximal]} der Item-Haltbarkeit, leer ohne eigene Haltbarkeit. */
    Optional<int[]> durability(ItemStack stack);

    /** Repariert um {@code amount} Punkte und liefert das neue Item (Original unverändert). */
    ItemStack repair(ItemStack stack, int amount, Player player);

    /** Nutzt Haltbarkeit ab (z. B. durch Skill-Effekte); liefert das neue Item oder Luft, wenn es zerbricht. */
    ItemStack damage(ItemStack stack, int amount, Player player);

    /** Server-thread preview only: preserve/sign instance data without committing the ledger or consuming anything.
     * Empty rejects the operation. Original, ingredient and proposed are isolated copies. @since 0.39.0 */
    default Optional<ItemStack> prepareAnvil(ItemStack original, ItemStack ingredient, ItemStack proposed, String rename, Player player) {
        return Optional.empty();
    }

    /** Called only after the server delivered a prepared result and consumed its input. Idempotent ledger commit. @since 0.39.0 */
    default boolean commitAnvil(ItemStack delivered, Player player) { return false; }

    Optional<StoredItem> encode(ItemStack stack);

    ItemStack decode(StoredItem stored);

    List<ItemStack> generateLoot(String profileId, LootContext context);

    /** Aggregierte Stats der aktuell getragenen Ausrüstung (gecachter Snapshot). */
    Map<String, Double> equipmentStats(Player player);

    /** Source breakdown of the equipment stats, empty for older providers. @since 0.35.0 */
    default Map<String, Map<String, Vystorm.vystorm_core.stat.StatSum.Contribution>> equipmentStatContributions(Player player) {
        return Map.of();
    }

    boolean templateExists(String templateId);

    /** Findet den Spieler/Ort einer Instanz nur über die Provenance-Datenbank (keine Weltscans). */
    Optional<String> provenance(UUID instanceId);
}
