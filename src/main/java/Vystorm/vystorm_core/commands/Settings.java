package Vystorm.vystorm_core.commands;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Core's {@code commands:} section of config.yml. Immutable snapshot; {@link #current()} is swapped on reload. */
record Settings(AliasPolicy policy, Set<String> disabledAliases, int helpPageSize, boolean paper) {
    static final Settings DEFAULTS = new Settings(AliasPolicy.ALL, Set.of(), 8, true);
    private static volatile Settings current = DEFAULTS;

    static Settings current() { return current; }

    static void set(Settings settings) { current = settings == null ? DEFAULTS : settings; }

    static Settings of(ConfigurationSection section) {
        if (section == null) return DEFAULTS;
        Set<String> disabled = new LinkedHashSet<>();
        for (String s : section.getStringList("disabled-aliases")) if (s != null && !s.isBlank()) disabled.add(s.trim().toLowerCase(Locale.ROOT));
        int size = Math.max(3, Math.min(20, section.getInt("help-page-size", 8)));
        boolean paper = !"bukkit".equalsIgnoreCase(section.getString("registration", "auto"));
        return new Settings(AliasPolicy.parse(section.getString("localized-aliases"), AliasPolicy.ALL), Set.copyOf(disabled), size, paper);
    }

    /** Whether an alias is switched off: by its name or as {@code <plugin>:<alias>}. */
    boolean disabled(String plugin, String alias) {
        String a = alias.toLowerCase(Locale.ROOT);
        return disabledAliases.contains(a) || disabledAliases.contains(plugin.toLowerCase(Locale.ROOT) + ":" + a);
    }

    List<String> disabledList() { return List.copyOf(disabledAliases); }
}
