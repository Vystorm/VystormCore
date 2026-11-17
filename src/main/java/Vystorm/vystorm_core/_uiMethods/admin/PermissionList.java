package Vystorm.vystorm_core._uiMethods.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /vadmin permissions [plugin] [page]}: permission nodes of Core and of every plugin that depends or soft-depends
 * on {@code vystorm_core}, from their {@code plugin.yml} ({@code PluginDescriptionFile#getPermissions}) plus the
 * plugin's config-editor node. Paged chat output; whether the sender has a node is shown per line. Server thread.
 *
 * @since 0.27.0
 */
public final class PermissionList {
    static final int PER_PAGE = 12;

    private PermissionList() {}

    /** One node: name, default and description. */
    public record Node(String name, PermissionDefault fallback, String description) {}

    private static Vystorm.vystorm_core.lang.Lang lang() { return Vystorm.vystorm_core.lang.Localization.get().core(); }

    /** Core first, then every plugin depending or soft-depending on Core, by name. */
    public static List<Plugin> plugins(PluginManager manager, Plugin core) {
        List<Plugin> out = new ArrayList<>();
        if (core != null) out.add(core);
        for (Plugin plugin : manager.getPlugins()) {
            if (plugin == null || plugin == core) continue;
            var description = plugin.getDescription();
            boolean uses = description.getDepend().stream().anyMatch(d -> d.equalsIgnoreCase("vystorm_core"))
                    || description.getSoftDepend().stream().anyMatch(d -> d.equalsIgnoreCase("vystorm_core"));
            if (uses) out.add(plugin);
        }
        out.subList(core == null ? 0 : 1, out.size()).sort(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Declared nodes of the plugin in plugin.yml order, plus {@code vystorm_core.config.<plugin>} if registered. */
    public static List<Node> nodes(Plugin plugin, PluginManager manager) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Permission permission : plugin.getDescription().getPermissions())
            out.putIfAbsent(permission.getName().toLowerCase(Locale.ROOT), new Node(permission.getName(), permission.getDefault(), permission.getDescription()));
        String config = Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.PERMISSION_PREFIX + plugin.getName().toLowerCase(Locale.ROOT);
        Permission dynamic = manager.getPermission(config);
        if (dynamic != null) out.putIfAbsent(config, new Node(dynamic.getName(), dynamic.getDefault(), dynamic.getDescription()));
        return List.copyOf(out.values());
    }

    /** Sends the plugin overview ({@code plugin == null}) or one page of a plugin's nodes. */
    public static void send(CommandSender sender, PluginManager manager, Plugin core, String plugin, int page) {
        List<Plugin> plugins = plugins(manager, core);
        if (plugin == null || plugin.isBlank()) {
            lang().send(sender, "permissions.plugins");
            for (Plugin p : plugins)
                lang().send(sender, "permissions.plugin-line", "plugin", p.getName(), "version", p.getDescription().getVersion(), "count", nodes(p, manager).size());
            lang().send(sender, "permissions.usage");
            return;
        }
        Plugin target = plugins.stream().filter(p -> p.getName().equalsIgnoreCase(plugin)).findFirst().orElse(null);
        if (target == null) { lang().send(sender, "permissions.unknown-plugin", "plugin", plugin); return; }
        List<Node> nodes = nodes(target, manager);
        int pages = Math.max(1, (nodes.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(1, Math.min(page, pages));
        lang().send(sender, "permissions.header", "plugin", target.getName(), "page", current, "pages", pages);
        for (Node node : nodes.subList((current - 1) * PER_PAGE, Math.min(nodes.size(), current * PER_PAGE))) {
            boolean has = sender.hasPermission(node.name());
            String description = node.description() == null || node.description().isBlank()
                    ? lang().plain(sender, "permissions.no-description") : node.description();
            lang().send(sender, "permissions.line", "mark", has ? Component.text("✔", NamedTextColor.GREEN) : Component.text("✘", NamedTextColor.RED), "node", node.name(),
                    "default", lang().plain(sender, "permissions.default-" + node.fallback().name().toLowerCase(Locale.ROOT)),
                    "description", description);
        }
        if (current < pages) lang().send(sender, "permissions.more", "plugin", target.getName(), "next", current + 1);
    }
}
