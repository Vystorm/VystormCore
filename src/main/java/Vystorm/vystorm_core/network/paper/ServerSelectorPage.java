package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.compat.InventoryCompat;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Main-menu page "Servers": one item per network server; a click asks the proxy to move the player.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ServerSelectorPage implements InventoryHolder, Listener {
    private static final int MAX_SLOTS = 54;
    private final List<ServerSelectorModel.Entry> entries;
    private Inventory inventory;

    ServerSelectorPage(List<ServerSelectorModel.Entry> entries) { this.entries = entries; }

    @Override public Inventory getInventory() { return inventory; }

    /** Registers the click listener and the main-menu entry (visible only while connected). */
    public static void register(Plugin core, Vystorm.vystorm_core.API api) {
        Lang lang = Localization.get().core();
        core.getServer().getPluginManager().registerEvents(new ServerSelectorPage(List.of()), core);
        api.gui().registerMenu(core, "servers", lang.ref("network.selector-title"), Material.COMPASS,
                p -> PaperNetwork.network().connected(), p -> open(core, p), "", lang.ref("commands.vnet.description"));
    }

    public static void open(Plugin core, Player player) {
        Lang lang = Localization.get().core();
        var network = PaperNetwork.network();
        List<ServerSelectorModel.Entry> entries = ServerSelectorModel.entries(network.servers(), network.serverName(),
                options(core.getConfig().getConfigurationSection("network.servers")));
        ServerSelectorPage page = new ServerSelectorPage(entries);
        int rows = Math.max(1, Math.min(6, (entries.size() + 8) / 9));
        page.inventory = Bukkit.createInventory(page, rows * 9, lang.text(player, "network.selector-title"));
        for (int slot = 0; slot < entries.size() && slot < MAX_SLOTS; slot++) {
            page.inventory.setItem(slot, item(lang, player, entries.get(slot)));
        }
        player.openInventory(page.inventory);
    }

    @SuppressWarnings("deprecation") // legacy ItemMeta setters work on Paper and Spigot alike
    private static ItemStack item(Lang lang, Player player, ServerSelectorModel.Entry entry) {
        Material material = Material.matchMaterial(entry.icon());
        if (material == null || !material.isItem() || material.isAir()) material = Material.COMPASS;
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, entry.players())));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(entry.display())));
            List<String> lore = new ArrayList<>();
            lore.add(lang.text(player, "network.selector-players", "players", entry.players()));
            lore.add(lang.text(player, switch (entry.state()) {
                case CURRENT -> "network.selector-current";
                case OFFLINE -> "network.selector-offline";
                case AVAILABLE -> "network.selector-click";
            }));
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    static ServerSelectorModel.Options options(ConfigurationSection section) {
        return new ServerSelectorModel.Options() {
            @Override public String display(String server) {
                String value = section == null ? null : section.getString(server + ".display");
                return value == null || value.isBlank() ? "<white>" + MiniMessage.miniMessage().escapeTags(server) : value;
            }
            @Override public String icon(String server) { return section == null ? null : section.getString(server + ".icon"); }
            @Override public boolean hidden(String server) { return section != null && section.getBoolean(server + ".hidden", false); }
        };
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // menuHolder: a chest or barrel in the world is recognised by its location, without copying its block state
        if (!(InventoryCompat.menuHolder(event.getInventory()) instanceof ServerSelectorPage page)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= page.entries.size()) return;
        ServerSelectorModel.Entry entry = page.entries.get(slot);
        if (!entry.clickable()) return;
        Lang lang = Localization.get().core();
        player.closeInventory();
        lang.send(player, "network.selector-moving", "server", entry.server());
        PaperNetwork.network().sendPlayer(player.getUniqueId(), entry.server()).thenAccept(ok -> {
            if (!ok && player.isOnline()) {
                Plugin core = Vystorm.vystorm_core.main.getPlugin();
                if (core.isEnabled()) Bukkit.getScheduler().runTask(core, () -> lang.send(player, "network.selector-failed", "server", entry.server()));
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (InventoryCompat.menuHolder(event.getInventory()) instanceof ServerSelectorPage) event.setCancelled(true);
    }
}
