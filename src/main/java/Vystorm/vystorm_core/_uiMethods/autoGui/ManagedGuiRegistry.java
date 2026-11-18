package Vystorm.vystorm_core._uiMethods.autoGui;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.function.*;

/** Inventory identity, plugin ownership and viewer checks are shared by every managed menu. */
final class ManagedGuiRegistry {
    private record Session(Plugin owner, UUID viewer, GuiCallbacks callbacks) {}
    private record Entry(Plugin owner, String key, Function<Player, String> title, Material icon,
                         Predicate<Player> access, Consumer<Player> opener, String group, Function<Player, String> description) {}
    private final Map<Inventory, Session> sessions = new IdentityHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Function<Player, String>> reasons = new HashMap<>();
    private final Map<String, String> requiredPermissions = new HashMap<>();
    private enum AccessState { ALLOWED, DISABLED, MISSING_PERMISSION, PREDICATE_DENIED, PROVIDER_FAILURE }
    private record AccessDecision(AccessState state, String requiredPermission) {
        boolean allowed() { return state == AccessState.ALLOWED; }
    }

    private static void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("GUI operations require the server thread");
    }
    Inventory create(Plugin owner, InventoryHolder holder, UUID viewer, int size, String title, GuiCallbacks callbacks) {
        thread(); Objects.requireNonNull(owner); Objects.requireNonNull(callbacks);
        if (!owner.isEnabled()) throw new IllegalStateException("GUI owner is disabled");
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        sessions.put(inventory, new Session(owner, viewer, callbacks));
        DebugMessages.low("[ManagedGuiRegistry] Menu created; owner=" + owner.getName());
        return inventory;
    }
    void register(Plugin owner, String key, String title, Material icon, Predicate<Player> access, Consumer<Player> opener) {
        register(owner, key, title, icon, access, opener, "");
    }
    void register(Plugin owner, String key, String title, Material icon, Predicate<Player> access, Consumer<Player> opener, String group) {
        register(owner, key, title, icon, access, opener, group, "");
    }
    void register(Plugin owner, String key, String title, Material icon, Predicate<Player> access, Consumer<Player> opener,
                  String group, String description) {
        Objects.requireNonNull(title);
        String text = description == null ? "" : description;
        register(owner, key, p -> title, icon, access, opener, group, p -> text);
    }
    /** Titles and descriptions per viewer (e.g. localized); either may return a {@code Lang.ref} reference. */
    void register(Plugin owner, String key, Function<Player, String> title, Material icon, Predicate<Player> access, Consumer<Player> opener,
                  String group, Function<Player, String> description) {
        thread(); Objects.requireNonNull(owner); Objects.requireNonNull(title); Objects.requireNonNull(icon);
        Objects.requireNonNull(access); Objects.requireNonNull(opener);
        String id = new NamespacedKey(owner, key).toString();
        if (entries.containsKey(id)) throw new IllegalArgumentException("Menu already registered: " + id);
        entries.put(id, new Entry(owner, id, title, icon, access, opener, Objects.requireNonNull(group), description == null ? p -> "" : description));
        DebugMessages.mid("[ManagedGuiRegistry] Menu registered: " + id);
    }
    List<String> keys(Player player) {
        return entries.values().stream().filter(e -> allowed(e, player)).map(Entry::key).toList();
    }
    private AccessDecision decision(Entry e, Player player) {
        if (!e.owner.isEnabled()) return new AccessDecision(AccessState.DISABLED, null);
        String permission = requiredPermissions.get(e.key);
        if (permission != null && !player.hasPermission(permission)) {
            return new AccessDecision(AccessState.MISSING_PERMISSION, permission);
        }
        try {
            return new AccessDecision(e.access.test(player)
                    ? AccessState.ALLOWED : AccessState.PREDICATE_DENIED, permission);
        } catch (RuntimeException | LinkageError ex) {
            Vystorm.vystorm_core.lifecycle.ProviderFailures.report("Menus", e.owner.getName(), "access", ex);
            return new AccessDecision(AccessState.PROVIDER_FAILURE, permission);
        }
    }
    private boolean allowed(Entry e, Player player) {
        return decision(e, player).allowed();
    }
    List<MenuEntryView> entries(Player player) {
        thread();
        return entries.values().stream().filter(e -> allowed(e, player))
                .map(e -> new MenuEntryView(e.key, text(e.title, player), e.icon, e.group, text(e.description, player))).toList();
    }
    void reason(Plugin owner, String key, Function<Player, String> reason) {
        thread();
        String id = new NamespacedKey(owner, key).toString();
        Entry e = entries.get(id);
        if (e == null || e.owner != owner) throw new IllegalArgumentException("Menu is not registered by owner");
        if (reason == null) reasons.remove(id); else reasons.put(id, reason);
    }

    void requiredPermission(Plugin owner, String key, String permission) {
        thread();
        String id = new NamespacedKey(Objects.requireNonNull(owner), key).toString();
        Entry e = entries.get(id);
        if (e == null || e.owner != owner) throw new IllegalArgumentException("Menu is not registered by owner");
        if (permission == null) {
            requiredPermissions.remove(id);
            return;
        }
        String normalized = permission.trim();
        if (normalized.isEmpty() || normalized.length() > 128
                || normalized.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Menu permission must be nonblank, contain no whitespace, and be at most 128 characters");
        }
        requiredPermissions.put(id, normalized);
    }

    List<MenuAccess> inspect(Player target) {
        thread();
        List<MenuAccess> out = new ArrayList<>();
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        for (Entry e : entries.values()) {
            AccessDecision decision = decision(e, target);
            boolean access = decision.allowed();
            String reason = switch (decision.state()) {
                case DISABLED -> lang.ref("menu.access.disabled");
                case MISSING_PERMISSION -> lang.text(target, "menu.access.missing-permission",
                        "permission", decision.requiredPermission());
                case PREDICATE_DENIED -> lang.ref("menu.access.denied");
                case PROVIDER_FAILURE -> lang.ref("menu.access.provider-failed");
                case ALLOWED -> lang.ref("menu.access.allowed");
            };
            if (decision.state() == AccessState.PREDICATE_DENIED && reasons.containsKey(e.key)) {
                try { String value = reasons.get(e.key).apply(target); if (value != null && !value.isBlank()) reason = value; }
                catch (RuntimeException | LinkageError ex) { Vystorm.vystorm_core.lifecycle.ProviderFailures.report("Menus", e.owner.getName(), "access reason", ex); }
            }
            out.add(new MenuAccess(e.key, e.owner.getName(), e.owner.isEnabled(), access, reason));
        }
        return List.copyOf(out);
    }
    List<MenuEntryView> entries(Player player, String group) {
        return entries.values().stream().filter(e -> e.group.equals(group) && allowed(e, player))
                .map(e -> new MenuEntryView(e.key, text(e.title, player), e.icon, e.group, text(e.description, player))).toList();
    }

    /** True while the group has at least one entry the player may open (used by category entries). */
    boolean hasVisible(Player player, String group) {
        return entries.values().stream().anyMatch(e -> e.group.equals(group) && allowed(e, player));
    }
    boolean open(Player player, String key) {
        thread();
        Entry e = entries.get(key.toLowerCase(Locale.ROOT));
        if (e == null && !key.contains(":")) {
            List<Entry> matches = entries.values().stream().filter(v -> v.key.endsWith(":" + key.toLowerCase(Locale.ROOT))).toList();
            if (matches.size() == 1) e = matches.getFirst();
        }
        if (e == null || !allowed(e, player)) return false;
        try { e.opener.accept(player); }
        catch (RuntimeException | LinkageError ex) { Vystorm.vystorm_core.lifecycle.ProviderFailures.report("Menus", e.owner.getName(), "open", ex); return false; }
        Vystorm.vystorm_core._uiMethods.menu.MenuExplorer.visited(player, e.key);
        Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboarding.markMenu(player, e.key);
        return true;
    }
    void directory(Player player, int requested) {
        directory(player, requested, "");
    }
    void directory(Player player, int requested, String group) {
        thread();
        Objects.requireNonNull(group);
        List<Entry> visible = entries.values().stream().filter(e -> e.group.equals(group) && allowed(e, player)).toList();
        // Reserve space for paging and returning to the main directory.
        final int perPage = 17;
        int pages = Math.max(1, (visible.size() + perPage - 1) / perPage);
        int page = Math.max(0, Math.min(requested, pages - 1));
        List<DialogButton> buttons = new ArrayList<>();
        var i18n = Vystorm.vystorm_core.lang.Localization.get();
        var lang = i18n.core();
        if (group.isEmpty()) buttons.add(new DialogButton(lang.component(player, "menu.explorer.title"),
                (viewer, response) -> Vystorm.vystorm_core._uiMethods.menu.MenuExplorer.open(viewer, main.getApi().gui())));
        for (int index = page * perPage; index < Math.min(visible.size(), (page + 1) * perPage); index++) {
            Entry entry = visible.get(index);
            String description = text(entry.description, player);
            Component tooltip = description.isBlank() ? null : i18n.component(player, description);
            buttons.add(new DialogButton(i18n.component(player, text(entry.title, player)), tooltip, (viewer, response) -> {
                // Access is evaluated again at response time by open(), after the dialog session is consumed.
                if (!open(viewer, entry.key)) lang.send(viewer, "common.menu-unavailable");
            }));
        }
        if (page > 0) buttons.add(new DialogButton(lang.component(player, "common.previous"), (viewer, response) -> directory(viewer, page - 1, group)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang.component(player, "common.next"), (viewer, response) -> directory(viewer, page + 1, group)));
        if (!group.isEmpty()) buttons.add(new DialogButton(lang.component(player, "menu.home"), (viewer, response) -> directory(viewer, 0)));
        String groupName = Vystorm.vystorm_core._uiMethods.menu.MenuCategory.byGroup(group).map(c -> c.title(player)).orElse(group);
        Component title = group.isEmpty() ? lang.component(player, "menu.directory.title")
                : lang.component(player, "menu.directory.group-title", "group", groupName);
        Component text = group.isEmpty() ? lang.component(player, "menu.directory.welcome", "player", player.getName())
                : visible.isEmpty() ? lang.component(player, "menu.directory.empty") : lang.component(player, "menu.directory.choose");
        main.getApi().dialogs().open(main.getPlugin(), player, new DialogRequest(
                pages > 1 ? title.append(Component.text(" " + (page + 1) + "/" + pages)) : title,
                List.of(DialogBody.plainMessage(text)), List.of(), buttons,
                Player::isOnline, () -> { if (!group.isEmpty()) directory(player, 0); },
                group.isEmpty() ? "Schließen" : DialogRequest.DEFAULT_CANCEL, 2));
    }
    private static String text(Function<Player, String> source, Player viewer) {
        try { String value = source.apply(viewer); return value == null ? "" : value; }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[ManagedGuiRegistry] Menu title failed: " + failure); return ""; }
    }
    private static ItemStack icon(Material material, String title) {
        ItemStack item = new ItemStack(material);
        Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> meta.setDisplayName(title));
        return item;
    }
    boolean managed(Inventory inventory) { return sessions.containsKey(inventory); }
    private boolean valid(Session s, org.bukkit.entity.HumanEntity player) {
        return s.owner.isEnabled() && (s.viewer == null || s.viewer.equals(player.getUniqueId()));
    }
    void click(InventoryClickEvent e) {
        Session s = sessions.get(Vystorm.vystorm_core.compat.InventoryCompat.top(e.getView()));
        if (s == null) return;
        if (!valid(s, e.getWhoClicked())) { e.setCancelled(true); return; }
        if (e.isCancelled()) return;
        try { s.callbacks.click(e); }
        catch (RuntimeException ex) { e.setCancelled(true); failure(s, ex); }
    }
    void drag(InventoryDragEvent e) {
        Session s = sessions.get(Vystorm.vystorm_core.compat.InventoryCompat.top(e.getView()));
        if (s == null) return;
        if (!valid(s, e.getWhoClicked())) { e.setCancelled(true); return; }
        if (e.isCancelled()) return;
        try { s.callbacks.drag(e); }
        catch (RuntimeException ex) { e.setCancelled(true); failure(s, ex); }
    }
    void open(InventoryOpenEvent e) {
        Session s = sessions.get(e.getInventory());
        if (s != null && !valid(s, e.getPlayer())) e.setCancelled(true);
    }
    void openResult(InventoryOpenEvent e) {
        Inventory inventory = e.getInventory();
        Session s = sessions.get(inventory);
        if (s == null || s.viewer == null || !s.viewer.equals(e.getPlayer().getUniqueId())) return;
        // Observe the final result after all listeners and Paper's actual open have completed.
        Bukkit.getScheduler().runTask(s.owner, () -> {
            if (sessions.get(inventory) != s || Vystorm.vystorm_core.compat.InventoryCompat.top(e.getPlayer().getOpenInventory()) == inventory) return;
            sessions.remove(inventory);
            try { s.callbacks.discarded(inventory); } catch (RuntimeException ex) { failure(s, ex); }
        });
    }
    void close(InventoryCloseEvent e) {
        Session s = sessions.get(e.getInventory());
        if (s == null) return;
        if (s.viewer != null && !s.viewer.equals(e.getPlayer().getUniqueId())) return;
        // 0.65.0: Core is only changing how the menu is shown (screen -> real chest). The server closes it for a moment
        // on the way; for its owner it stays open, so the session stays (clicks cancelled, callbacks bound) and the
        // close callback waits. If the chest does not come back, the menu is closed for real two ticks later.
        if (s.viewer != null && Vystorm.vystorm_core.screen.internal.ChestHandOver.active(e.getInventory())) {
            Inventory inventory = e.getInventory();
            try {
                Bukkit.getScheduler().runTaskLater(s.owner, () -> {
                    if (sessions.get(inventory) != s || shownTo(e.getPlayer(), inventory)) return;
                    closed(s, e);
                }, 2L);
                return;
            } catch (RuntimeException noTask) { /* the owner is being disabled: close now */ }
        }
        closed(s, e);
    }
    private void closed(Session s, InventoryCloseEvent e) {
        if (s.viewer != null) sessions.remove(e.getInventory());
        try { s.callbacks.close(e); } catch (RuntimeException ex) { failure(s, ex); }
    }
    private static boolean shownTo(org.bukkit.entity.HumanEntity player, Inventory inventory) {
        try {
            return (!(player instanceof Player online) || online.isOnline())
                    && Vystorm.vystorm_core.compat.InventoryCompat.top(player.getOpenInventory()) == inventory;
        } catch (RuntimeException failure) { return false; }
    }
    private static void failure(Session s, RuntimeException ex) {
        DebugMessages.high("[ManagedGuiRegistry] GUI callback failed; owner=" + s.owner.getName() + "; error=" + ex);
    }
    void release(Inventory inventory) {
        thread();
        // Keep callbacks bound until close has persisted shared physical inventories.
        for (var viewer : List.copyOf(inventory.getViewers())) viewer.closeInventory();
        Session s = sessions.remove(inventory);
        if (s != null) {
            try { s.callbacks.discarded(inventory); } catch (RuntimeException ex) { failure(s, ex); }
        }
    }
    void unregister(Plugin owner) {
        thread(); entries.values().removeIf(e -> { if (e.owner != owner) return false; reasons.remove(e.key); requiredPermissions.remove(e.key); return true; });
        List<Inventory> owned = sessions.entrySet().stream().filter(e -> e.getValue().owner == owner).map(Map.Entry::getKey).toList();
        owned.forEach(this::release);
        DebugMessages.mid("[ManagedGuiRegistry] Menus released; owner=" + owner.getName());
    }
    /** Ein Eintrag dieses Besitzers (z. B. {@code Panels.unregister}). */
    boolean unregister(Plugin owner, String key) {
        thread();
        String id = new NamespacedKey(owner, key).toString();
        Entry e = entries.get(id);
        if (e == null || e.owner != owner) return false;
        entries.remove(id);
        reasons.remove(id);
        requiredPermissions.remove(id);
        return true;
    }
    void clear() { thread(); List.copyOf(sessions.keySet()).forEach(this::release); entries.clear(); reasons.clear(); requiredPermissions.clear(); }
}
