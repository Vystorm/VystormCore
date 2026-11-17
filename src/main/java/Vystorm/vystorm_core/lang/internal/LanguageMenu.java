package Vystorm.vystorm_core.lang.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.CoreLanguage;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.lang.PlayerLanguageChangeEvent;
import Vystorm.vystorm_core.lang.TextWidth;
import Vystorm.vystorm_core.main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /language} without arguments: a chest menu with an info item, "Automatic (client language)" and one
 * flag-like banner per offered language (own name, code, coverage, current choice marked). A click stores the choice
 * (same storage as {@code /vsettings}) and reopens the menu in the new language. Server thread. Core-internal.
 */
public final class LanguageMenu {
    static final int INFO_SLOT = 4;
    static final int AUTO_SLOT = 9;
    static final int MAX_LANGUAGES = 44;

    private final Plugin core;
    private final UUID viewer;
    private final Map<Integer, String> slots = new HashMap<>();
    private Inventory inventory;
    private boolean busy;

    private LanguageMenu(Plugin core, Player viewer) { this.core = core; this.viewer = viewer.getUniqueId(); }

    /** Rows for the info row plus "auto" and {@code languages} entries (2..6), as slots. */
    static int size(int languages) {
        int entries = 1 + Math.min(languages, MAX_LANGUAGES);
        return (1 + (entries + 8) / 9) * 9;
    }

    /** Opens the menu; false when Core's GUI is not available. */
    public static boolean open(Player player) {
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || main.getApi() == null) return false;
        return new LanguageMenu(core, player).show(player);
    }

    private boolean show(Player player) {
        Lang lang = Localization.get().core();
        List<LanguageChoices.Option> options = LanguageChoices.of(Localization.get()).options();
        try {
            inventory = main.getApi().gui().createInventory(core, null, viewer, size(options.size()),
                    lang.text(player, "language-select.menu-title"), new GuiCallbacks() {
                        @Override public void click(InventoryClickEvent event) { onClick(event); }
                    });
        } catch (RuntimeException failure) {
            DebugMessages.high("[Localization] Language menu could not be created: " + failure);
            return false;
        }
        fill(player, options);
        player.openInventory(inventory);
        return true;
    }

    private void fill(Player player, List<LanguageChoices.Option> options) {
        Localization service = Localization.get();
        Lang lang = service.core();
        String choice = CoreLanguage.choice(player);
        String current = service.language(player);
        inventory.clear();
        slots.clear();

        ItemStack info = new ItemStack(Material.BOOK);
        lang.name(info, player, "language-select.info-name");
        lore(info, wrapped(lang, player, "language-select.info-lore", "language", CoreLanguage.nativeName(current) + " (" + current + ")",
                "choice", choice.equals(Localization.AUTO) ? lang.plain(player, "settings.language.auto") : CoreLanguage.nativeName(choice)));
        inventory.setItem(INFO_SLOT, info);

        ItemStack auto = new ItemStack(Material.COMPASS);
        lang.name(auto, player, "language-select.auto-name");
        String client = Localization.clientLocale(player);
        List<Component> autoLore = new ArrayList<>(wrapped(lang, player, "language-select.auto-lore", "client", client.isEmpty() ? "?" : client,
                "language", CoreLanguage.nativeName(lang.resolveLanguage(client))));
        autoLore.add(Component.empty());
        autoLore.add(lang.component(player, choice.equals(Localization.AUTO) ? "language-select.selected" : "language-select.click"));
        lore(auto, autoLore);
        glint(auto, choice.equals(Localization.AUTO));
        inventory.setItem(AUTO_SLOT, auto);
        slots.put(AUTO_SLOT, Localization.AUTO);

        int slot = AUTO_SLOT + 1;
        for (LanguageChoices.Option option : options) {
            if (slot >= inventory.getSize()) break;
            boolean selected = option.code().equals(choice);
            ItemStack flag = LanguageFlags.item(option.code());
            try { Vystorm.vystorm_core.compat.PlatformCompat.editMeta(flag, meta -> Vystorm.vystorm_core.compat.PlatformCompat.displayName(meta, Component.text(option.name()).color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false))); }
            catch (RuntimeException ignored) { /* name stays the banner's */ }
            List<Component> lore = new ArrayList<>();
            lore.add(lang.component(player, "language-select.code", "code", option.code()));
            lore.add(lang.component(player, "language-select.coverage", "coverage", option.percent()));
            if (option.code().equals(current) && !selected) lore.add(lang.component(player, "language-select.in-use"));
            lore.add(Component.empty());
            lore.add(lang.component(player, selected ? "language-select.selected" : "language-select.click"));
            lore(flag, lore);
            glint(flag, selected);
            inventory.setItem(slot, flag);
            slots.put(slot++, option.code());
        }
    }

    private static List<Component> wrapped(Lang lang, Player player, String key, Object... placeholders) {
        return lang.wrappedLines(player, key, TextWidth.LORE_COLUMNS, placeholders);
    }

    private static void lore(ItemStack item, List<Component> lines) {
        List<Component> lore = lines.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList();
        try { Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> Vystorm.vystorm_core.compat.PlatformCompat.lore(meta, lore)); } catch (RuntimeException ignored) { /* no lore */ }
    }

    private static void glint(ItemStack item, boolean on) {
        if (!on) return;
        try { Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> meta.setEnchantmentGlintOverride(true)); } catch (RuntimeException | LinkageError ignored) { /* older API */ }
    }

    private void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (busy || event.getClickedInventory() != inventory) return;
        String code = slots.get(event.getRawSlot());
        if (code == null) return;
        busy = true;
        Bukkit.getScheduler().runTask(core, () -> {
            busy = false;
            Player player = Bukkit.getPlayer(viewer);
            if (player == null || !isOpen(player)) return;
            LanguageCommand.apply(player, player, code, PlayerLanguageChangeEvent.Cause.COMMAND);
            // The title is part of the inventory: reopen in the new language.
            new LanguageMenu(core, player).show(player);
        });
    }

    private boolean isOpen(Player player) {
        try { return player.isOnline() && Vystorm.vystorm_core.compat.InventoryCompat.top(player.getOpenInventory()) == inventory; }
        catch (RuntimeException failure) { return false; }
    }
}
