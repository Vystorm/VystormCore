package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.main;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * First-run setups from each plugin's {@code setup.yml}: join hint, question dialogs, preview, confirmed write. @since 0.54.0
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class FirstRunSetup implements Listener {
    private static final int PREVIEW_LINES = 20, VALUE = 40;
    private static SetupState state;
    private static Plugin core;
    private static final Map<String, SetupDefinition> definitions = new HashMap<>();

    private FirstRunSetup() {}
    private static Lang lang() { return Localization.get().core(); }

    /** Call first in Core's enable: dependents have not written their configs yet, so new installs can be told apart. */
    public static void snapshot(Plugin corePlugin) {
        core = corePlugin;
        state = new SetupState(corePlugin.getDataFolder().toPath().resolve("setup-state.yml"));
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            try (InputStream in = plugin.getResource(SetupDefinition.FILE)) {
                if (in != null) state.observe(plugin.getName(), Files.exists(plugin.getDataFolder().toPath().resolve("config.yml")));
            } catch (IOException | RuntimeException failure) { corePlugin.getLogger().warning("Setup state of " + plugin.getName() + ": " + failure); }
        }
    }
    public static void start(Plugin corePlugin) {
        Bukkit.getPluginManager().registerEvents(new FirstRunSetup(), corePlugin);
        // First tick: every plugin is enabled, so broken setup.yml files are reported once at startup.
        Bukkit.getScheduler().runTask(corePlugin, () -> {
            List<String> open = new ArrayList<>();
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) if (plugin.isEnabled() && definition(plugin) != null && pending(plugin.getName())) open.add(plugin.getName());
            if (!open.isEmpty()) corePlugin.getLogger().info("First-run setup waiting for an admin to join: " + String.join(", ", open));
        });
    }

    private static Plugin plugin(String name) {
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) if (p.getName().equalsIgnoreCase(name) && p.isEnabled()) return p;
        return null;
    }
    private static SetupDefinition definition(Plugin plugin) {
        String key = plugin.getName().toLowerCase(java.util.Locale.ROOT);
        if (definitions.containsKey(key)) return definitions.get(key);
        SetupDefinition parsed = null;
        try (InputStream in = plugin.getResource(SetupDefinition.FILE)) {
            if (in != null) parsed = SetupDefinition.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), target -> {
                YamlConfiguration defaults = ConfigEditorService.defaults(plugin, target.file());
                return defaults == null ? null : defaults.get(target.path());
            }, ConfigEditorService::secret);
        } catch (IOException | SetupDefinition.Invalid | RuntimeException failure) {
            plugin.getLogger().warning("setup.yml ignored: " + failure.getMessage());
        }
        definitions.put(key, parsed);
        return parsed;
    }
    static boolean has(String name) { Plugin p = plugin(name); return p != null && definition(p) != null; }
    static boolean pending(String name) { return state != null && state.pending(name) && has(name); }
    private static List<Plugin> available(Player p, boolean onlyPending) {
        List<Plugin> out = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins())
            if (plugin.isEnabled() && p.hasPermission(ConfigEditor.permission(plugin)) && definition(plugin) != null && (!onlyPending || pending(plugin.getName()))) out.add(plugin);
        out.sort(java.util.Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }
    static boolean anyPending(Player p) { return setupOn() && !available(p, true).isEmpty(); }

    /** setup.enabled (0.65.0): off = no join hint, no setup list, no setup button. */
    static boolean setupOn() { return Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.SETUP); }

    @EventHandler public void join(PlayerJoinEvent event) {
        if (!setupOn()) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(core, () -> {
            if (!p.isOnline()) return;
            List<Plugin> open = available(p, true);
            if (open.isEmpty()) return;
            boolean one = open.size() == 1;
            Component line = one ? lang().component(p, "setup-flow.hint-one", "plugin", open.getFirst().getName()) : lang().component(p, "setup-flow.hint-many", "count", open.size());
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(p, line.clickEvent(ClickEvent.runCommand("/vadmin setup" + (one ? " " + open.getFirst().getName() : "")))
                    .hoverEvent(lang().component(p, "setup-flow.hint-hover")));
        }, 60L);
    }

    /** {@code name == null}: the list of setups this admin may run. */
    public static void open(Player p, String name) {
        if (!setupOn()) { lang().send(p, "feature-off"); return; }
        if (name != null) {
            Plugin plugin = plugin(name);
            if (plugin == null || !p.hasPermission(ConfigEditor.permission(plugin)) || definition(plugin) == null) { lang().send(p, "setup-flow.unknown", "plugin", name); return; }
            p.closeInventory();
            intro(p, plugin);
            return;
        }
        List<Plugin> list = available(p, false);
        if (list.isEmpty()) { lang().send(p, "setup-flow.none"); return; }
        p.closeInventory();
        List<DialogButton> buttons = new ArrayList<>();
        for (Plugin plugin : list) buttons.add(new DialogButton(lang().component(p, pending(plugin.getName()) ? "setup-flow.list-open" : "setup-flow.list-done", "plugin", plugin.getName()), (v, r) -> intro(v, plugin)));
        show(p, null, lang().component(p, "setup-flow.list-title"), List.of(lang().component(p, "setup-flow.list-text")), List.of(), buttons, 2);
    }
    private static boolean valid(Player v, Plugin plugin) { return v.isOnline() && (plugin == null || (plugin.isEnabled() && v.hasPermission(ConfigEditor.permission(plugin)))); }
    private static void show(Player p, Plugin plugin, Component title, List<Component> body, List<DialogInput> inputs, List<DialogButton> buttons, int columns) {
        main.getApi().dialogs().open(core, p, new DialogRequest(title, body.stream().map(c -> (DialogBody) DialogBody.plainMessage(c, 300)).toList(), inputs, buttons,
                v -> valid(v, plugin), () -> {}, lang().text(p, "common.close"), columns));
    }
    private static String language(Player p) { return Localization.get().language(p); }

    private static void intro(Player p, Plugin plugin) {
        SetupDefinition d = definition(plugin);
        if (d == null) return;
        List<DialogButton> buttons = new ArrayList<>(Arrays.asList(
                new DialogButton(lang().component(p, "setup-flow.recommended"), (v, r) -> preview(v, plugin, d.recommended())),
                new DialogButton(lang().component(p, "setup-flow.questions"), (v, r) -> question(v, plugin, d, 0, new LinkedHashMap<>())),
                new DialogButton(lang().component(p, "setup-flow.later"), (v, r) -> lang().send(v, "setup-flow.later-note", "plugin", plugin.getName())),
                new DialogButton(lang().component(p, "setup-flow.skip"), (v, r) -> finish(v, plugin, SetupState.Status.SKIPPED, "setup-flow.skipped"))));
        List<Component> body = new ArrayList<>();
        if (!d.intro().isEmpty()) body.add(Component.text(SetupDefinition.text(d.intro(), language(p))));
        body.add(lang().component(p, "setup-flow.intro-note"));
        show(p, plugin, lang().component(p, "setup-flow.intro-title", "plugin", plugin.getName()), body, List.of(), buttons, 2);
    }

    private static void question(Player p, Plugin plugin, SetupDefinition d, int index, Map<String, String> answers) {
        if (index >= d.questions().size()) { preview(p, plugin, d.changes(answers)); return; }
        SetupDefinition.Question q = d.questions().get(index);
        String language = language(p);
        Component title = lang().component(p, "setup-flow.question-title", "plugin", plugin.getName(), "index", index + 1, "total", d.questions().size());
        List<Component> body = List.of(Component.text(SetupDefinition.text(q.text(), language)));
        List<DialogButton> buttons = new ArrayList<>();
        List<DialogInput> inputs = new ArrayList<>();
        if (q.type() == SetupDefinition.Type.CHOICE) {
            for (var option : q.options()) {
                Component label = Component.text(SetupDefinition.text(option.label(), language));
                if (option.id().equals(q.recommended())) label = label.append(Component.text(" ★"));
                buttons.add(new DialogButton(label, (v, r) -> { answers.put(q.id(), option.id()); question(v, plugin, d, index + 1, answers); }));
            }
        } else {
            Object current = ConfigEditor.service().value(plugin, q.target().file(), q.target().path());
            String shown = current instanceof Number n ? ConfigEditorService.plainNumber(n.doubleValue()) : current == null ? "" : String.valueOf(current);
            inputs.add(DialogInput.text("value", Component.text(SetupDefinition.text(q.text(), language))).initial(shown).maxLength(256).build());
            buttons.add(new DialogButton(lang().component(p, "setup-flow.next"), (v, r) -> {
                String text = r.getText("value");
                if (q.type() == SetupDefinition.Type.NUMBER && q.number(text) == null) { lang().send(v, "setup-flow.invalid"); question(v, plugin, d, index, answers); return; }
                answers.put(q.id(), text);
                question(v, plugin, d, index + 1, answers);
            }));
        }
        buttons.add(new DialogButton(lang().component(p, "setup-flow.keep"), (v, r) -> { answers.remove(q.id()); question(v, plugin, d, index + 1, answers); }));
        show(p, plugin, title, body, inputs, buttons, 2);
    }

    private static void preview(Player p, Plugin plugin, Map<SetupDefinition.Target, Object> changes) {
        Map<SetupDefinition.Target, Object> effective = new LinkedHashMap<>();
        List<Component> body = new ArrayList<>();
        for (var e : changes.entrySet()) {
            Object before = ConfigEditor.service().value(plugin, e.getKey().file(), e.getKey().path());
            if (ConfigHistory.same(before, e.getValue())) continue;
            effective.put(e.getKey(), e.getValue());
            if (effective.size() <= PREVIEW_LINES) body.add(lang().component(p, "setup-flow.preview-line", "target", e.getKey().path(), "before", ConfigHistory.show(before, VALUE), "after", ConfigHistory.show(e.getValue(), VALUE)));
        }
        if (effective.size() > PREVIEW_LINES) body.add(lang().component(p, "setup-flow.preview-more", "count", effective.size() - PREVIEW_LINES));
        List<DialogButton> buttons = new ArrayList<>();
        if (effective.isEmpty()) {
            body.add(lang().component(p, "setup-flow.unchanged"));
            buttons.add(new DialogButton(lang().component(p, "setup-flow.finish"), (v, r) -> finish(v, plugin, SetupState.Status.DONE, "setup-flow.done")));
        } else {
            body.addFirst(lang().component(p, "setup-flow.preview-text"));
            buttons.add(new DialogButton(lang().component(p, "setup-flow.apply"), (v, r) -> {
                if (ConfigEditor.service().applyBatch(v, plugin.getName(), effective)) finish(v, plugin, SetupState.Status.DONE, "setup-flow.done");
            }));
        }
        buttons.add(new DialogButton(lang().component(p, "setup-flow.back"), (v, r) -> intro(v, plugin)));
        show(p, plugin, lang().component(p, "setup-flow.preview-title", "plugin", plugin.getName()), body, List.of(), buttons, 2);
    }

    private static void finish(Player p, Plugin plugin, SetupState.Status status, String message) {
        try { Objects.requireNonNull(state).mark(plugin.getName(), status); }
        catch (IOException | RuntimeException failure) { core.getLogger().warning("Setup state of " + plugin.getName() + " not saved: " + failure); }
        lang().send(p, message, "plugin", plugin.getName());
    }
}
