package Vystorm.vystorm_core.item;

import Vystorm.vystorm_core.mythic.MmoItemsBridgeInterface;

/**
 * Item manager, reached through {@code api.items()}: the item provider directly ({@link ItemServices}) and the
 * parts around it.
 *
 * @since 0.64.0
 */
public interface ItemsInterface extends ItemServicesInterface {
    /** Item references such as {@code vystorm:sword}, resolved by namespace ({@link ItemRefs}). */
    default ItemRefsInterface refs() { return ItemRefsInterface.DEFAULT; }

    /** Core lock for items ({@link ItemRestrictions}). */
    default ItemRestrictionsInterface restrictions() { return ItemRestrictionsInterface.DEFAULT; }

    /** MMOItems, if installed ({@link Vystorm.vystorm_core.mythic.MmoItemsBridge}). */
    default MmoItemsBridgeInterface mmoItems() { return MmoItemsBridgeInterface.DEFAULT; }
}
