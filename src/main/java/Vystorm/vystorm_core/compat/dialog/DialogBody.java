package Vystorm.vystorm_core.compat.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/** Server-independent dialog body. */
public record DialogBody(Component contents, int width, ItemStack item) {
    public static DialogBody plainMessage(Component text) { return plainMessage(text, 300); }
    public static DialogBody plainMessage(Component text, int width) { return new DialogBody(text, width, null); }
    public static DialogBody item(ItemStack item) { return new DialogBody(Component.empty(), 300, item.clone()); }
    public DialogBody build() { return this; }
}
