package Vystorm.vystorm_core.lifecycle;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Core's own feature switches (0.65.0) in Core's {@code config.yml}, next to the older {@code enabled} keys (player
 * menu, news, inbox, profile, polls, config history, screens, menus, web). Every switch is on by default, so a config
 * written before a key existed behaves as before; the reload hook of Core's config reads them again, so they apply at
 * once. The base that other plugins need (main menu, settings, config editor, language system, {@code /vhelp}, event
 * order, placeholder manager, the loot, world and region services) has no switch.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CoreSwitches {
    /** The switches that are new in 0.65.0. */
    public enum Switch {
        /** {@code /vtrade}. */
        TRADE_CHAT("trade-chat.enabled"),
        /** Coordinates in chat lines can be clicked (staff teleport). */
        COORDINATES("clickable-coordinates.enabled"),
        /** The tutorials of all plugins and the first-steps guide in {@code /vmenu}. */
        TUTORIALS("tutorials.enabled"),
        /** The setup wizard and the first-run setups ({@code /vadmin setup}, the join hint, the button in the config menu). */
        SETUP("setup.enabled"),
        /** {@code /vadmin diagnostics}, {@code /vmenu access}, {@code /vadmin migration} and {@code /vadmin trace}. */
        DIAGNOSTICS("diagnostics.enabled"),
        /** Core's loot rules (mob-level luck, pity) and {@code /vadmin loot}; the tables stay for the plugins. */
        LOOT("loot.enabled"),
        /** {@code /vworlds}; visits of plugin worlds keep their inventory protection. */
        PLUGIN_WORLDS("plugin-worlds.enabled"),
        /** {@code /vregion} and the {@code region} placeholders; the register stays for the plugins. */
        REGIONS("region-register.enabled");

        public final String path;

        Switch(String path) { this.path = path; }

        /** Language key of the one-line description in {@code /vadmin features}. */
        public String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    /**
     * Older keys that were switchable but not on the feature screen (nested keys with other names, or {@code metrics} on
     * Core's housekeeping list), with the language key of their description. Declared as features in 0.65.0.
     */
    public static final List<String[]> DECLARED = List.of(
            new String[] {"menu.sneak-click-shortcut", "switches.menu-shortcut"},
            new String[] {"messages.show-source", "switches.show-source"},
            new String[] {"screens.panels", "switches.screen-panels"},
            new String[] {"screens.inventories", "switches.screen-inventories"},
            new String[] {"screens.tutorials", "switches.screen-tutorials"},
            new String[] {"screens.dialogs", "switches.screen-dialogs"},
            new String[] {"screens.dialog-inputs", "switches.screen-dialog-inputs"},
            new String[] {"metrics", "switches.metrics"},
            new String[] {"update-check.enabled", "switches.update-check"},
            new String[] {"review-reminder.enabled", "switches.review-reminder"});

    /**
     * 0.66.1: the older {@code enabled} keys that {@code /vadmin features} lists by themselves, with the id of their
     * texts. Before, they were shown with names made from the key path ("Web › Client › Ui") and the config comment.
     */
    public static final List<String[]> LISTED = List.of(
            new String[] {"database.shared.enabled", "shared-database"},
            new String[] {"web.enabled", "web"},
            new String[] {"web.client.enabled", "web-client"},
            new String[] {"web.client.ui.enabled", "web-client-ui"},
            new String[] {"web.client.panels.enabled", "web-client-panels"},
            new String[] {"web.audit.enabled", "web-audit"},
            new String[] {"player-menu.enabled", "player-menu"},
            new String[] {"news.enabled", "news"},
            new String[] {"profile.enabled", "profile"},
            new String[] {"inbox.enabled", "inbox"},
            new String[] {"config-history.enabled", "config-history"},
            new String[] {"polls.enabled", "polls"},
            new String[] {"screens.enabled", "screens"},
            new String[] {"menus.enabled", "file-menus"});

    /** Receives one switch of Core: its path, the readable name and the description (language references). */
    @FunctionalInterface
    public interface Declaration {
        /** {@code declared}: a key the feature screen would not list by itself (it is declared as a feature). */
        void accept(String path, String labelRef, String descriptionRef, boolean declared);
    }

    /**
     * 0.66.1: every switch of Core with its name ({@code switch-names.<id>}) and description ({@code switches.<id>})
     * in all seven languages, so {@code /vadmin features vystorm_core} shows no key paths. {@code ref} turns a key of
     * Core's language bundle into a reference ({@code Lang.ref}).
     */
    public static void declare(java.util.function.UnaryOperator<String> ref, Declaration out) {
        for (Switch s : Switch.values()) {
            String id = s.description().substring("switches.".length());
            out.accept(s.path, ref.apply("switch-names." + id), ref.apply(s.description()), true);
        }
        for (String[] declared : DECLARED) {
            String id = declared[1].substring("switches.".length());
            out.accept(declared[0], ref.apply("switch-names." + id), ref.apply(declared[1]), true);
        }
        for (String[] listed : LISTED) out.accept(listed[0], ref.apply("switch-names." + listed[1]), ref.apply("switches." + listed[1]), false);
    }

    private static volatile Set<Switch> off = EnumSet.noneOf(Switch.class);

    private CoreSwitches() {}

    /** Reads the switches from Core's config (start and every reload). */
    public static void configure(ConfigurationSection config) {
        Set<Switch> next = EnumSet.noneOf(Switch.class);
        if (config != null) for (Switch s : Switch.values()) if (!config.getBoolean(s.path, true)) next.add(s);
        off = next;
    }

    /** Whether a switch is on; everything is on until Core read its config (and in tests). */
    public static boolean on(Switch s) { return !off.contains(s); }

    /**
     * Whether Core's plugin.yml command {@code label} belongs to a switch that is off ({@code vtrade}, {@code vregion},
     * {@code vworlds}): {@code /vhelp} leaves it out; the command itself answers the shared off message.
     */
    public static boolean commandOff(String label) {
        if (label == null) return false;
        Switch s = switch (label.toLowerCase(Locale.ROOT)) {
            case "vtrade" -> Switch.TRADE_CHAT;
            case "vregion" -> Switch.REGIONS;
            case "vworlds" -> Switch.PLUGIN_WORLDS;
            default -> null;
        };
        return s != null && !on(s);
    }

    /** The switched-off ones, for tests and diagnostics. */
    public static Set<Switch> off() { return off.isEmpty() ? EnumSet.noneOf(Switch.class) : EnumSet.copyOf(off); }
}
