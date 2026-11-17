package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core.compat.dialog.*;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Dialog frontend for a single draft, held only by the expiring dialog session.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SettingsDraftDialog {
    private SettingsDraftDialog() {}
    public static void open(Player player, SettingsRegistry registry) { overview(player, registry, new SettingsDraft(), 0); }
    private static void overview(Player p, SettingsRegistry registry, SettingsDraft draft, int requested) {
        var lang = Localization.get().core();
        if (draft.expired()) { lang.send(p, "settings.draft.expired"); return; }
        var sections = registry.view(p).stream().filter(v -> v.section().scope() == SettingScope.SERVER).toList();
        int page = Math.max(0, Math.min(requested, Math.max(0, (sections.size() - 1) / 12)));
        List<DialogButton> buttons = new ArrayList<>();
        for (var v : sections.subList(page * 12, Math.min(sections.size(), (page + 1) * 12))) buttons.add(new DialogButton(
                Localization.get().component(p, v.section().title()), (x, r) -> edit(x, registry, draft, v.key())));
        if (page > 0) buttons.add(new DialogButton(lang.component(p, "common.previous"), (x, r) -> overview(x, registry, draft, page - 1)));
        if ((page + 1) * 12 < sections.size()) buttons.add(new DialogButton(lang.component(p, "common.next"), (x, r) -> overview(x, registry, draft, page + 1)));
        buttons.add(new DialogButton(lang.component(p, "settings.draft.preview"), (x, r) -> preview(x, registry, draft)));
        show(p, List.of(lang.component(p, "settings.draft.intro", "count", draft.changes().size())), List.of(), buttons);
    }
    private static void edit(Player p, SettingsRegistry registry, SettingsDraft draft, String key) {
        var section = registry.view(p, key).filter(v -> v.section().scope() == SettingScope.SERVER);
        if (section.isEmpty() || draft.expired()) return;
        var settings = section.get().settings().stream().filter(SettingsRegistry.SettingView::readable).toList();
        List<DialogInput> inputs = new ArrayList<>();
        for (int i = 0; i < settings.size(); i++) {
            var v = settings.get(i);
            Object pending = draft.changes().stream().filter(c -> c.section().equals(key) && c.id().equals(v.setting().id()))
                    .map(SettingsDraft.Change::after).findFirst().orElse(v.value());
            inputs.add(SettingsRegistry.input("v" + i, v.setting(), pending, p));
        }
        var lang = Localization.get().core();
        show(p, List.of(Localization.get().component(p, section.get().section().title())), inputs,
                List.of(new DialogButton(lang.component(p, "settings.draft.stage"), (x, r) -> {
                    try {
                        for (int i = 0; i < settings.size(); i++) {
                            var v = settings.get(i); Object value = SettingsRegistry.read("v" + i, v.setting(), r);
                            if (value == null) { lang.send(x, "settings.invalid", "label", Localization.get().resolve(x, v.setting().label())); return; }
                            draft.stage(key, v.setting().id(), v.value(), value);
                        }
                        overview(x, registry, draft, 0);
                    } catch (IllegalStateException ex) { lang.send(x, "settings.draft.expired"); }
                })));
    }
    public static void preview(Player p, SettingsRegistry registry, SettingsDraft draft) {
        var lang = Localization.get().core();
        List<Component> body = new ArrayList<>();
        body.add(lang.component(p, "settings.draft.warning"));
        for (var c : draft.changes()) {
            var policy = registry.policy(c.section(), c.id());
            body.add(lang.component(p, "settings.draft.line", "setting", c.section() + "/" + c.id(),
                    "before", c.before(), "after", c.after(), "activation", lang.text(p, "settings.draft.activation-" + policy.activation().name().toLowerCase(Locale.ROOT))));
        }
        List<String> conflicts = draft.conflicts(registry, p);
        if (!conflicts.isEmpty()) body.add(lang.component(p, "settings.draft.conflicts", "keys", String.join(", ", conflicts)));
        List<DialogButton> buttons = new ArrayList<>();
        if (conflicts.isEmpty() && !draft.changes().isEmpty()) buttons.add(new DialogButton(lang.component(p, "settings.draft.apply"), (x, r) -> {
            try {
                var results = draft.apply(registry, x);
                int changed = results.stream().mapToInt(SettingsRegistry.SaveResult::changed).sum();
                lang.send(x, "settings.saved-many", "count", changed);
                for (var result : results) if (!result.found() || result.applyFailed() || !result.problems().isEmpty())
                    lang.send(x, "settings.draft.partial");
            } catch (IllegalStateException ex) { lang.send(x, "settings.draft.conflicts", "keys", String.join(", ", draft.conflicts(registry, x))); }
        }));
        buttons.add(new DialogButton(lang.component(p, "settings.draft.continue"), (x, r) -> overview(x, registry, draft, 0)));
        show(p, body, List.of(), buttons);
    }
    private static void show(Player p, List<Component> body, List<DialogInput> inputs, List<DialogButton> buttons) {
        var lang = Localization.get().core();
        main.getApi().dialogs().open(main.getPlugin(), p, new DialogRequest(lang.component(p, "settings.draft.title"),
                body.stream().map(c -> (DialogBody) DialogBody.plainMessage(c, 300)).toList(), inputs, buttons,
                Player::isOnline, () -> {}, lang.text(p, "common.close"), 2));
    }
}
