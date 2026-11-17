package Vystorm.vystorm_core._uiMethods.menu;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Installs the "Vystorm-Menü" dialog into the pause menu (ESC) and the quick-actions hotkey through a
 * small world data pack. Its buttons send {@code vystorm_core:pause/<target>} custom clicks which open the
 * native Core menus. New or changed pack files take effect after the next server restart.
 */
public final class PauseMenuPack {
    public static final String PACK = "vystorm_core_menu";
    static final List<String> FILES = List.of(
            "pack.mcmeta",
            "data/vystorm_core/dialog/menu.json",
            "data/minecraft/tags/dialog/pause_screen_additions.json",
            "data/minecraft/tags/dialog/quick_actions.json");

    private PauseMenuPack() {}

    /** @return true when files were written or removed (a restart is needed to apply them) */
    public static boolean sync(Plugin core, boolean enabled) { return sync(core, enabled, Localization.AUTO); }

    /**
     * Like {@link #sync(Plugin, boolean)}; the pack texts use {@code language} ({@code menu.yml} {@code pause-menu-language}).
     * {@code auto}: Core's {@code language.default} if it is a fixed code, else the language of the pack already
     * installed in the world, else English. (A data pack cannot follow each player's language.)
     *
     * @since 0.22.0
     */
    public static boolean sync(Plugin core, boolean enabled, String language) {
        if (!Vystorm.vystorm_core.compat.ServerCompat.hasDialogs()) return false;
        if (Bukkit.getWorlds().isEmpty()) return false;
        // Data packs live in the level folder (level-name), not in a dimension folder.
        Path target = Bukkit.getWorldContainer().toPath().resolve(Bukkit.getWorlds().getFirst().getName())
                .resolve("datapacks").resolve(PACK);
        try {
            String version = null;
            if (enabled) {
                try (InputStream in = Bukkit.getServer().getClass().getResourceAsStream("/version.json")) {
                    if (in == null) throw new IOException("server version.json missing; data pack not changed");
                    version = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            boolean changed = enabled ? write(core, target, packLanguage(core, target, language), version) : remove(target);
            if (changed) DebugMessages.high("[PauseMenuPack] Pause menu data pack " + (enabled ? "written to " : "removed from ")
                    + target + "; takes effect after the next server restart.");
            return changed;
        } catch (IOException failure) {
            DebugMessages.high("[PauseMenuPack] Pause menu data pack could not be updated: " + failure.getMessage());
            return false;
        }
    }

    static boolean write(Plugin core, Path target) throws IOException { return write(core, target, "de"); }

    static boolean write(Plugin core, Path target, String language) throws IOException {
        return write(core, target, language, null);
    }

    private static boolean write(Plugin core, Path target, String language, String serverVersion) throws IOException {
        boolean changed = false;
        for (String file : FILES) {
            String content = template(core, file);
            if (file.equals("pack.mcmeta") && serverVersion != null) content = packMetadata(content, serverVersion);
            byte[] bundled = render(content, language);
            Path destination = target.resolve(file);
            if (Files.isRegularFile(destination) && Arrays.equals(Files.readAllBytes(destination), bundled)) continue;
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".vystorm-", ".tmp");
            try {
                Files.write(temporary, bundled);
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            changed = true;
        }
        return changed;
    }

    /** Use the running server's data-pack format, not the format of the API used to compile Core. */
    static String packMetadata(String template, String serverVersion) throws IOException {
        try {
            JsonObject formats = JsonParser.parseString(serverVersion).getAsJsonObject().getAsJsonObject("pack_version");
            JsonObject metadata = JsonParser.parseString(template).getAsJsonObject();
            JsonObject pack = metadata.getAsJsonObject("pack");
            pack.remove("pack_format");
            pack.remove("min_format");
            pack.remove("max_format");
            pack.remove("supported_formats");
            if (formats.has("data_major")) {
                int major = formats.get("data_major").getAsInt();
                int minor = formats.get("data_minor").getAsInt();
                if (major < 1 || minor < 0) throw new IllegalArgumentException("invalid data-pack format");
                JsonArray exact = new JsonArray();
                exact.add(major); exact.add(minor);
                pack.add("min_format", exact);
                pack.add("max_format", exact.deepCopy());
            } else {
                int format = formats.get("data").getAsInt();
                if (format < 1) throw new IllegalArgumentException("invalid data-pack format");
                pack.addProperty("pack_format", format);
            }
            return metadata.toString();
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot determine the server data-pack format; data pack not changed", invalid);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([a-z0-9.\\-]+)}}");

    private static String template(Plugin core, String file) throws IOException {
        try (InputStream in = core.getResource("pausemenu/" + file)) {
            if (in == null) throw new IOException("resource missing: " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Fills {@code {{key}}} tokens with Core texts of {@code language} (plain, JSON-escaped). */
    static byte[] render(String template, String language) {
        // Keep generated pack bytes identical for Windows and Unix source checkouts.
        template = template.replace("\r\n", "\n");
        Lang lang = Localization.get().core();
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(json(lang.plain(language, m.group(1)))));
        m.appendTail(out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.toString();
    }

    /** See {@link #sync(Plugin, boolean, String)}. */
    static String packLanguage(Plugin core, Path target, String setting) {
        Localization service = Localization.get();
        Lang lang = service.core();
        String wanted = Localization.normalize(setting);
        if (wanted.isEmpty() || wanted.equals(Localization.AUTO)) wanted = service.defaultLanguage();
        if (!wanted.equals(Localization.AUTO)) return lang.resolveLanguage(wanted);
        Path installed = target.resolve(FILES.get(1));
        if (Files.isRegularFile(installed)) {
            try {
                byte[] current = Files.readAllBytes(installed);
                String menu = template(core, FILES.get(1));
                for (String code : lang.languages()) if (Arrays.equals(current, render(menu, code))) return code;
            } catch (IOException ignored) {
                // fall through to English
            }
        }
        return Localization.FALLBACK;
    }

    static boolean remove(Path target) throws IOException {
        if (!Files.exists(target)) return false;
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        return true;
    }

    /** Handles a custom click from the pack's dialog; false when the key is not ours. */
    public static boolean handle(Player player, Key key) {
        if (!key.namespace().equals("vystorm_core") || !key.value().startsWith("pause/")) return false;
        var api = Vystorm.vystorm_core.main.getApi();
        switch (key.value().substring("pause/".length())) {
            case "main" -> api.gui().openDirectory(player, 0);
            case "settings" -> api.settings().openOverview(player);
            case "tutorials" -> api.gui().openDirectory(player, 0, MenuCategory.TUTORIALS.group());
            default -> { return false; }
        }
        return true;
    }
}
