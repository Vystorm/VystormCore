package Vystorm.vystorm_core.lang;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Source of the bundled {@code lang/<code>.yml} files of a plugin (code → YAML text). */
@FunctionalInterface
interface LangResources {
    /** Probed when the jar cannot be listed. */
    List<String> COMMON = List.of("en", "de", "fr", "es", "it", "nl", "pl", "pt", "pt_br", "ru", "tr", "cs", "sv",
            "da", "fi", "no", "hu", "uk", "ja", "ko", "zh_cn", "zh_tw");

    Map<String, String> load();

    static LangResources of(Plugin plugin) {
        return () -> {
            Map<String, String> files = new LinkedHashMap<>();
            for (String code : listJar(plugin)) read(plugin.getResource("lang/" + code + ".yml"), code, files);
            if (files.isEmpty()) for (String code : COMMON) read(plugin.getResource("lang/" + code + ".yml"), code, files);
            return files;
        };
    }

    static LangResources classpath(ClassLoader loader) {
        return () -> {
            Map<String, String> files = new LinkedHashMap<>();
            for (String code : COMMON) read(loader.getResourceAsStream("lang/" + code + ".yml"), code, files);
            return files;
        };
    }

    static LangResources of(Map<String, String> yamlByCode) {
        Map<String, String> copy = new LinkedHashMap<>();
        yamlByCode.forEach((code, yaml) -> copy.put(Localization.normalize(code), yaml));
        return () -> copy;
    }

    private static void read(InputStream in, String code, Map<String, String> into) {
        if (in == null) return;
        try (in) { into.put(Localization.normalize(code), new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        catch (IOException failure) { DebugMessages.high("[Localization] Bundled lang/" + code + ".yml unreadable: " + failure); }
    }

    /** Codes of all {@code lang/*.yml} entries in the plugin jar; empty if the jar cannot be listed. */
    private static List<String> listJar(Plugin plugin) {
        try {
            URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(location.toURI());
            if (!jar.isFile()) return List.of();
            List<String> codes = new java.util.ArrayList<>();
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("lang/") && name.endsWith(".yml") && name.indexOf('/', 5) < 0 && name.length() > 9)
                        codes.add(name.substring(5, name.length() - 4));
                }
            }
            return codes;
        } catch (Exception | LinkageError failure) {
            return List.of();
        }
    }
}
