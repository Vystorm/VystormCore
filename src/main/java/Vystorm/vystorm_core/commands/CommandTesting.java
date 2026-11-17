package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.plugin.Plugin;

/**
 * Unit tests of command trees without a server: a registration that is not mounted anywhere (no command map, no
 * aliases), so {@link CommandRegistration#execute}, {@link CommandRegistration#suggest}, {@code help} and
 * {@code usage} can be called with mocked senders. Core's own texts (errors, help) are read from Core's jar, not from
 * the test classpath (where {@code lang/en.yml} would be the plugin's file).
 *
 * <pre>{@code
 * Lang lang = Lang.create("VystormRails", Map.of("en", read("en.yml"), "de", read("de.yml")));
 * CommandRegistration r = CommandTesting.detached(mock(Plugin.class), lang, RailsPlugin.commandSpec(null));
 * assertEquals(List.of("ziel", "karte"), r.suggest(germanPlayer, "rails", new String[]{""}));
 * }</pre>
 *
 * @since 0.45.0
 */
public final class CommandTesting {
    private static volatile Lang coreTexts;

    private CommandTesting() {}

    /** A registration that only runs the tree; a mocked plugin should answer {@code isEnabled() = true}. */
    public static CommandRegistration detached(Plugin plugin, Lang lang, CommandSpec spec) {
        return new CommandRegistration(plugin, lang, spec, null, coreTexts());
    }

    /** Core's language bundle read from the jar (or class folder) that contains Core's classes. */
    static Lang coreTexts() {
        Lang cached = coreTexts;
        if (cached != null) return cached;
        Map<String, String> files = new LinkedHashMap<>();
        try {
            File location = new File(Localization.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (location.isFile()) {
                try (ZipFile zip = new ZipFile(location)) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        String name = e.getName();
                        if (name.startsWith("lang/") && name.endsWith(".yml") && name.indexOf('/', 5) < 0)
                            files.put(name.substring(5, name.length() - 4), new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            } else {
                Path dir = location.toPath().resolveSibling("resources").resolve("main").resolve("lang");
                if (!Files.isDirectory(dir)) dir = location.toPath().getParent().getParent().resolve("resources").resolve("main").resolve("lang");
                if (Files.isDirectory(dir)) try (var list = Files.list(dir)) {
                    for (Path p : list.toList()) {
                        String n = p.getFileName().toString();
                        if (n.endsWith(".yml")) files.put(n.substring(0, n.length() - 4), Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception | LinkageError failure) {
            files.clear();
        }
        Lang lang = files.isEmpty() ? Localization.get().core() : Lang.create("vystorm_core", files);
        coreTexts = lang;
        return lang;
    }
}
