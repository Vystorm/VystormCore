package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.region.Region;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Alles, was der Besuchsdienst vom Server braucht (im Test ersetzt; Umsetzung {@link BukkitVisitEnv}). */
public interface VisitEnv {
    boolean primaryThread();
    /** A previous provider's snapshot still needs migration; never overwrite its inventory state. */
    default boolean pendingMigration(UUID player) { return false; }
    default String legacy(UUID player, String action) { return "nothing"; }
    long now();
    /** Im nächsten Tick auf dem Server-Thread. */
    void nextTick(Runnable task);
    /** Schedule a main-thread deadline (ticks); implementations predating async entry may omit it. */
    default void later(long ticks, Runnable task) { }
    /** Slow durable writes are operational warnings, not ordinary debug messages. */
    default void warn(String message) { log(message); }
    CompletableFuture<Boolean> teleportAsync(Player player, Location to);
    boolean teleport(Player player, Location to);
    Location mainSpawn();
    /** Welt nach Namen ({@code null} = nicht geladen); darf asynchron gerufen werden. */
    World world(String name);
    Player player(UUID id);
    /** Regionen an der Position; darf werfen (dann gilt die Position als gesperrt). */
    List<Region> regionsAt(Location location);
    boolean sameParty(UUID a, UUID b);
    /** Hauptname des registrierten Befehls zum Label oder {@code null}. */
    String commandName(String label);
    default boolean sameCommand(String label, String primaryLabel) { return false; }
    void hide(Player viewer, Player other);
    void show(Player viewer, Player other);
    /** Besuchs-Marker im PDC des Spielers (wird mit den Spielerdaten gespeichert). */
    boolean hasMarker(Player player);
    void setMarker(Player player, String worldId);
    void clearMarker(Player player);
    /** Besuchs-Werkzeug von Core oder (bei {@code legacyKey != null}) mit diesem alten PDC-Schlüssel. */
    boolean isTool(ItemStack stack, String legacyKey);
    /** Markierte Kopie: Besuchs-Werkzeug der Welt und nicht handelbar. */
    ItemStack markTool(ItemStack stack, String worldId);
    ItemCodec codec();
    void message(Player player, String key, Object... placeholders);
    void log(String message);
    void drop(Location at, ItemStack stack);
}
