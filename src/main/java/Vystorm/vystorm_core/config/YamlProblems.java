package Vystorm.vystorm_core.config;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Tells whether a YAML file on disk can be read (0.66.1). Bukkit's {@code reloadConfig()} only logs a syntax error and
 * leaves an empty configuration; a {@code saveConfig()} after that writes only the few values set since, and the
 * admin's whole file is gone. Code that saves a config it did not just read successfully asks here first.
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class YamlProblems {
    private static final Pattern MARK = Pattern.compile("line (\\d+), column (\\d+)");

    private YamlProblems() {}

    /** Null when the file is missing or valid YAML; otherwise "line L, column C: problem" (or the reader's message). */
    public static String problem(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            new YamlConfiguration().load(file);
            return null;
        } catch (InvalidConfigurationException invalid) {
            return describe(invalid.getMessage());
        } catch (IOException | RuntimeException unreadable) {
            return String.valueOf(unreadable.getMessage());
        }
    }

    /**
     * True (and the sender and the console are told why) when the plugin's {@code config.yml} has a YAML error, so a
     * command must not change and save it: {@code saveConfig()} would replace the admin's file with the few values set.
     */
    public static boolean refusedSave(org.bukkit.command.CommandSender sender, org.bukkit.plugin.Plugin plugin) {
        String broken = problem(new File(plugin.getDataFolder(), "config.yml"));
        if (broken == null) return false;
        plugin.getLogger().severe("config.yml could not be read (" + broken + "); nothing was saved, the file is left unchanged.");
        try { Vystorm.vystorm_core.lang.Localization.get().core().send(sender, "config-broken-not-saved", "file", "config.yml", "problem", broken); }
        catch (RuntimeException | LinkageError noLang) { if (sender != null) sender.sendMessage("config.yml could not be read (" + broken + "); nothing was saved."); }
        return true;
    }

    /** SnakeYAML's message reduced to the position of the problem (its last mark) and the first line of the problem. */
    static String describe(String message) {
        if (message == null) return "unreadable YAML";
        // "<what>\n in 'reader', line L, column C:\n <snippet>\n ^" once for the context and once for the problem
        String at = null, problem = null, before = null;
        for (String line : message.split("\\R")) {
            String t = line.strip();
            Matcher m = MARK.matcher(t);
            if (m.find()) { at = "line " + m.group(1) + ", column " + m.group(2); problem = before; }
            if (!t.isEmpty()) before = t;
        }
        if (problem != null && problem.matches("^[\\w.$]+(Exception|Error): .*")) problem = problem.substring(problem.indexOf(": ") + 2);
        if (at == null) return problem == null ? message.strip() : problem;
        return problem == null ? at : at + ": " + problem;
    }
}
