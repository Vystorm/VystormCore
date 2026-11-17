package Vystorm.vystorm_core.profile;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Player profile card ({@code /vmenu profile [player]}, main menu, player menu): one page about a player to which
 * every plugin can add a few lines – an XP balance, owned plots, the strongest pet. Core shows the basics (first
 * join, last seen, play time) and then one section per plugin.
 *
 * <pre>{@code
 * try {
 *     if (CoreApi.has(CoreApi.Capability.PROFILES))
 *         Profiles.register(this, (viewer, target) -> List.of(lang.component(viewer, "profile.balance", "xp", balance(target))));
 * } catch (LinkageError olderCore) {}
 * }</pre>
 *
 * Providers run on the server thread when a card is opened; return at most {@link #MAX_LINES} short lines (empty =
 * the section is left out). A provider that throws is skipped and logged. Registrations end when the plugin is
 * disabled.
 *
 * <p>Preferred access: {@code api.gui().profiles()}.
 *
 * @since 0.56.0
 */
public final class Profiles {
    /** Open your own and other players' profile cards (everyone by default). */
    public static final String PERMISSION = "vystorm_core.profile";
    public static final int MAX_LINES = 6;

    /** Lines about {@code target} for {@code viewer}. */
    @FunctionalInterface
    public interface Provider {
        List<Component> lines(Player viewer, OfflinePlayer target);
    }

    private static final Map<Plugin, Provider> PROVIDERS = new ConcurrentHashMap<>();

    private Profiles() {}

    /** Adds (or replaces) the plugin's section. */
    public static void register(Plugin plugin, Provider provider) {
        PROVIDERS.put(Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(provider, "provider"));
    }

    public static void unregister(Plugin plugin) { if (plugin != null) PROVIDERS.remove(plugin); }

    /** Sections for a card: plugin name and its lines, sorted by plugin name; failing or empty providers are left out. */
    public static List<Map.Entry<String, List<Component>>> sections(Player viewer, OfflinePlayer target) {
        List<Map.Entry<String, List<Component>>> out = new ArrayList<>();
        List<Plugin> plugins = new ArrayList<>(PROVIDERS.keySet());
        plugins.sort(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER));
        for (Plugin plugin : plugins) {
            if (!plugin.isEnabled()) continue;
            Provider provider = PROVIDERS.get(plugin);
            if (provider == null) continue;
            try {
                List<Component> lines = provider.lines(viewer, target);
                if (lines == null || lines.isEmpty()) continue;
                List<Component> clean = new ArrayList<>();
                for (Component line : lines) if (line != null && clean.size() < MAX_LINES) clean.add(line);
                if (!clean.isEmpty()) out.add(Map.entry(plugin.getName(), clean));
            } catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report("Profiles", plugin.getName(), "lines", failure);
            }
        }
        return out;
    }

    /** Number of registered sections (for diagnostics and tests). */
    public static int size() { return PROVIDERS.size(); }
}
