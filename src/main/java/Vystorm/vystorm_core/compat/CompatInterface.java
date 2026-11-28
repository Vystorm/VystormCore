package Vystorm.vystorm_core.compat;

/**
 * Compatibility manager, reached through {@code api.compat()}: everything that differs between Paper 1.20.6 to 26.x
 * and Spigot, grouped the way the static {@code *Compat} classes are.
 * <p>
 * Each group mirrors one static class method for method, so {@code PlatformCompat.sendMessage(p, text)} becomes
 * {@code api.compat().platform().sendMessage(p, text)}. All groups have delegating defaults; a replacement set with
 * {@code api.overrideCompatHandler(...)} overrides only the groups it wants to change.
 *
 * @since 0.64.0
 */
public interface CompatInterface {
    /** Text, items, inventories, chunks, scoreboards and server facts ({@link PlatformCompat}). */
    default PlatformCompatInterface platform() { return PlatformCompatInterface.DEFAULT; }

    /** Feature detection and renamed registry constants ({@link ServerCompat}). */
    default ServerCompatInterface server() { return ServerCompatInterface.DEFAULT; }

    /** Top and bottom inventory of a view ({@link InventoryCompat}). */
    default InventoryCompatInterface inventory() { return InventoryCompatInterface.DEFAULT; }

    /** Attributes, damage, game rules and entity state ({@link GameplayCompat}). */
    default GameplayCompatInterface gameplay() { return GameplayCompatInterface.DEFAULT; }

    /** Worlds, biomes, view distance and world creation ({@link WorldCompat}). */
    default WorldCompatInterface world() { return WorldCompatInterface.DEFAULT; }

    /** Byte serialization of items and entities ({@link StorageCompat}). */
    default StorageCompatInterface storage() { return StorageCompatInterface.DEFAULT; }

    /** Item model keys and item array serialization ({@link ItemCompat}). */
    default ItemCompatInterface items() { return ItemCompatInterface.DEFAULT; }

    /** Mob pathfinding ({@link MobNavigation}). */
    default MobNavigationInterface navigation() { return MobNavigationInterface.DEFAULT; }

    /** Teleporting a vehicle together with its passengers ({@link PassengerTeleport}). */
    default PassengerTeleportInterface passengers() { return PassengerTeleportInterface.DEFAULT; }

    /** Strict JSON reading across Gson versions ({@link JsonCompat}). */
    default JsonCompatInterface json() { return JsonCompatInterface.DEFAULT; }

    /** Click callbacks for chat components ({@link ClickActions}). */
    default ClickActionsInterface clicks() { return ClickActionsInterface.DEFAULT; }

    /** Which chunks a client has received ({@link ClientEvents}). */
    default ClientEventsInterface clientEvents() { return ClientEventsInterface.DEFAULT; }

    /** Game rules across Paper 1.20.6, Spigot and Paper/Purpur 26.x, labels and rule claims. @since 0.73.0 */
    default Vystorm.vystorm_core.compat.gamerule.GameRulesInterface gameRules() { return Vystorm.vystorm_core.compat.gamerule.GameRulesInterface.DEFAULT; }
}
