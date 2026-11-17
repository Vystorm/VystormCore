package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core._uiMethods.settings.SettingScope;
import Vystorm.vystorm_core.main;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

/** /einstellungen [server|<plugin:bereich>] opens the native settings dialogs. */
public final class SettingsCommand implements TabExecutor {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (!(sender instanceof Player player)) { lang.send(sender, "settings.players-only"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("setup") && main.getApi().settings() instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry registry) {
            Vystorm.vystorm_core._uiMethods.settings.SetupWizard.open(player, registry); return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("profile") && main.getApi().settings() instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry registry) {
            if (!player.hasPermission("vystorm_core.admin")) { lang.send(player, "admin.no-permission"); return true; }
            var folder = main.getPlugin().getDataFolder().toPath().resolve("settings-profiles");
            try {
                if (args[1].equalsIgnoreCase("export")) {
                    int count = Vystorm.vystorm_core._uiMethods.settings.SettingsProfiles.exportProfile(registry, player, folder, args[2]);
                    lang.send(player, "settings.profile.exported", "name", args[2], "count", count);
                } else if (args[1].equalsIgnoreCase("import")) {
                    var draft = Vystorm.vystorm_core._uiMethods.settings.SettingsProfiles.importProfile(registry, player, folder, args[2]);
                    Vystorm.vystorm_core._uiMethods.settings.SettingsDraftDialog.preview(player, registry, draft);
                } else lang.send(player, "settings.profile.usage");
            } catch (java.io.IOException | IllegalArgumentException ex) {
                lang.send(player, "settings.profile.failed");
                main.getPlugin().getLogger().warning("Settings profile: " + ex.getMessage());
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("draft") && main.getApi().settings() instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry registry) {
            Vystorm.vystorm_core._uiMethods.settings.SettingsDraftDialog.open(player, registry); return true;
        }
        var panels = Vystorm.vystorm_core.panel.Panels.of(main.getPlugin());
        if (panels.available(player) && Vystorm.vystorm_core.panel.internal.PanelRegistry
                .find(panels.id(Vystorm.vystorm_core.panel.internal.SettingsPanels.NAME)).isPresent()) {
            String arg = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
            panels.open(player, Vystorm.vystorm_core.panel.internal.SettingsPanels.NAME, arg);
            return true;
        }
        // ohne Mod: bisherige Dialoge (unverändert)
        var settings = main.getApi().settings();
        if (args.length == 0) settings.openOverview(player);
        else if (args[0].equalsIgnoreCase("server")) {
            if (settings.sections(player, SettingScope.SERVER).isEmpty()) lang.send(player, "settings.no-server");
            else settings.openScope(player, SettingScope.SERVER);
        } else if (!settings.openSection(player, args[0])) lang.send(player, "settings.not-found");
        return true;
    }

    /**
     * {@code server}/{@code draft} for admins with server sections, {@code profile export|import <name>} for
     * {@code vystorm_core.admin}, then the section keys (config-editor sections are reached via {@code /vadmin config}).
     */
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) return List.of();
        var settings = main.getApi().settings();
        boolean admin = player.hasPermission("vystorm_core.admin");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (args.length == 1) {
            if (!settings.sections(player, SettingScope.SERVER).isEmpty()) { out.add("server"); out.add("draft"); }
            if (admin && settings instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry) { out.add("profile"); out.add("setup"); }
            var registry = settings instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry r ? r : null;
            for (String key : settings.keys(player)) if (registry == null || registry.group(key).isEmpty()) out.add(key);
        } else if (admin && args[0].equalsIgnoreCase("profile")) {
            if (args.length == 2) out.addAll(List.of("export", "import"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("import")) out.addAll(profiles());
        }
        String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        return out.stream().filter(k -> k.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).distinct().toList();
    }

    /** Names of saved settings profiles (file names only, no parsing). */
    private static List<String> profiles() {
        java.io.File[] files = new java.io.File(main.getPlugin().getDataFolder(), "settings-profiles").listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return List.of();
        return java.util.Arrays.stream(files).map(f -> f.getName().substring(0, f.getName().length() - 4)).sorted().toList();
    }
}
