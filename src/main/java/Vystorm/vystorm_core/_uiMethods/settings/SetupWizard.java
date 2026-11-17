package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core.compat.dialog.*;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.main;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Guided setup using existing versioned profiles, permission checks and settings drafts. Server thread only. */
public final class SetupWizard {
    private SetupWizard() {}
    private static boolean allowed(Player player) {
        if (player.hasPermission("vystorm_core.admin")) return true;
        Localization.get().core().send(player, "admin.no-permission"); return false;
    }
    private static Path folder() { return main.getPlugin().getDataFolder().toPath().resolve("settings-profiles"); }
    /** Registered, enabled plugins with server settings visible to this administrator. @since 0.52.0 */
    public static List<String> plugins(SettingsRegistry registry, Player viewer) {
        if (!viewer.hasPermission("vystorm_core.admin")) return List.of();
        return registry.view(viewer).stream().filter(v -> v.section().scope() == SettingScope.SERVER)
                .map(v -> v.key().split(":", 2)[0]).distinct().sorted().toList();
    }
    static List<String> profiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p))
                    .map(p -> p.getFileName().toString()).filter(n -> n.matches("[a-z0-9_-]{1,40}\\.yml"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted().toList();
        }
    }
    /** Start the wizard; no setting is written before explicit preview confirmation. @since 0.52.0 */
    public static void open(Player player, SettingsRegistry registry) { page(player, registry, 0); }
    private static void page(Player p, SettingsRegistry registry, int requested) {
        if (!allowed(p)) return;
        var lang = Localization.get().core();
        try {
            List<String> presets = profiles(folder());
            List<String> sections = registry.view(p).stream().filter(v -> v.section().scope() == SettingScope.SERVER
                    && v.settings().stream().anyMatch(s -> s.readable() && (registry.policy(v.key(), s.setting().id()).profileSafe() || registry.group(v.key()).equals(ConfigEditor.GROUP))))
                    .map(SettingsRegistry.SectionView::key).toList();
            int page = Math.clamp(requested, 0, Math.max(0, (presets.size() - 1) / 10));
            List<DialogButton> buttons = new ArrayList<>();
            for (String name : presets.subList(page * 10, Math.min(presets.size(), page * 10 + 10)))
                buttons.add(new DialogButton(Component.text(name), (viewer, response) -> select(viewer, registry, name)));
            if (page > 0) buttons.add(new DialogButton(lang.component(p, "common.previous"), (v, r) -> page(v, registry, page - 1)));
            if ((page + 1) * 10 < presets.size()) buttons.add(new DialogButton(lang.component(p, "common.next"), (v, r) -> page(v, registry, page + 1)));
            buttons.add(new DialogButton(lang.component(p, "setup.capture"), (v, r) -> {
                if (!allowed(v)) return;
                try {
                    Set<String> chosen = selection(r.getText("sections"), sections);
                    int count = SettingsProfiles.exportProfile(registry, v, folder(), r.getText("name"), chosen);
                    lang.send(v, "settings.profile.exported", "name", r.getText("name"), "count", count);
                    page(v, registry, 0);
                } catch (IOException | IllegalArgumentException ex) { lang.send(v, "settings.profile.failed"); }
            }));
            buttons.add(new DialogButton(lang.component(p, "setup.custom"), (v, r) -> { if (allowed(v)) SettingsDraftDialog.open(v, registry); }));
            show(p, List.of(lang.component(p, "setup.intro", "plugins", String.join(", ", plugins(registry, p))),
                    lang.component(p, presets.isEmpty() ? "setup.empty" : "setup.choose"),
                    lang.component(p, "setup.sections", "sections", String.join(", ", sections))),
                    List.of(DialogInput.text("name", lang.component(p, "setup.name")).maxLength(40).build(),
                            DialogInput.text("sections", lang.component(p, "setup.capture-sections")).initial(sections.isEmpty() ? "" : sections.getFirst()).maxLength(2048).build()), buttons);
        } catch (IOException ex) { lang.send(p, "settings.profile.failed"); }
    }
    private static void select(Player p, SettingsRegistry registry, String name) {
        if (!allowed(p)) return;
        var lang = Localization.get().core();
        List<String> available = plugins(registry, p);
        show(p, List.of(lang.component(p, "setup.select", "name", name, "plugins", String.join(", ", available))),
                List.of(DialogInput.text("plugins", lang.component(p, "setup.plugins")).initial(String.join(",", available)).maxLength(2048).build()),
                List.of(new DialogButton(lang.component(p, "settings.draft.preview"), (v, r) -> {
                    if (!allowed(v)) return;
                    try {
                        Set<String> selected = selection(r.getText("plugins"), plugins(registry, v));
                        SettingsDraft draft = SettingsProfiles.importProfile(registry, v, folder(), name, selected);
                        preview(v, registry, draft);
                    } catch (IOException | IllegalArgumentException ex) { lang.send(v, "settings.profile.failed"); }
                })));
    }
    static Set<String> selection(String raw, List<String> available) {
        if (raw == null) throw new IllegalArgumentException("Missing plugin selection");
        Set<String> result = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!available.contains(name)) throw new IllegalArgumentException("Unavailable plugin");
            result.add(name);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty selection");
        return Set.copyOf(result);
    }
    private static void preview(Player p, SettingsRegistry registry, SettingsDraft draft) {
        if (!allowed(p)) return;
        var lang = Localization.get().core();
        List<Component> body = new ArrayList<>();
        body.add(lang.component(p, "settings.draft.warning"));
        for (var c : draft.changes()) body.add(lang.component(p, "settings.draft.line", "setting", c.section() + "/" + c.id(),
                "before", c.before(), "after", c.after(), "activation", lang.text(p, "settings.draft.activation-" + registry.policy(c.section(), c.id()).activation().name().toLowerCase(Locale.ROOT))));
        if (draft.changes().isEmpty()) body.add(lang.component(p, "setup.unchanged"));
        List<DialogButton> buttons = new ArrayList<>();
        if (!draft.changes().isEmpty()) buttons.add(new DialogButton(lang.component(p, "settings.draft.apply"), (v, r) -> {
            if (!allowed(v)) return;
            try {
                var results = draft.apply(registry, v);
                lang.send(v, "settings.saved-many", "count", results.stream().mapToInt(SettingsRegistry.SaveResult::changed).sum());
                for (var result : results) if (!result.found() || result.applyFailed() || !result.problems().isEmpty()) lang.send(v, "settings.draft.partial");
            } catch (IllegalStateException ex) { lang.send(v, "settings.draft.conflicts", "keys", String.join(", ", draft.conflicts(registry, v))); }
        }));
        buttons.add(new DialogButton(lang.component(p, "common.previous"), (v, r) -> page(v, registry, 0)));
        show(p, body, List.of(), buttons);
    }
    private static void show(Player p, List<Component> body, List<DialogInput> inputs, List<DialogButton> buttons) {
        var lang = Localization.get().core();
        main.getApi().dialogs().open(main.getPlugin(), p, new DialogRequest(lang.component(p, "setup.title"),
                body.stream().map(c -> (DialogBody) DialogBody.plainMessage(c, 300)).toList(), inputs, buttons,
                v -> v.isOnline() && v.hasPermission("vystorm_core.admin"), () -> {}, lang.text(p, "common.close"), 2));
    }
}
