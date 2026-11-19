package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInterface;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogResponseView;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Server-thread registry that renders plugin settings as native dialogs. */
public final class SettingsRegistry implements SettingsInterface {
    private static final int SECTIONS_PER_PAGE = 16;
    record Entry(Plugin owner, String key, SettingsSection section) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, SettingPolicy> policies = new HashMap<>();
    /** Add metadata only for a setting owned by this plugin. Server thread only. @since 0.26.5 */
    public void policy(Plugin owner, String section, String id, SettingPolicy policy) {
        thread();
        Entry e = entries.get(owner.getName().toLowerCase(Locale.ROOT) + ":" + section);
        if (e == null || e.owner != owner || e.section.settings().stream().noneMatch(s -> s.id().equals(id)))
            throw new IllegalArgumentException("Unknown owned setting");
        policies.put(e.key + "/" + id, Objects.requireNonNull(policy));
    }
    /** Missing metadata is explicitly UNKNOWN and cannot be exported. @since 0.26.5 */
    public SettingPolicy policy(String section, String id) { thread(); return policies.getOrDefault(section + "/" + id, SettingPolicy.DEFAULT); }
    /** Active owner version used by explicitly opt-in settings profiles. @since 0.26.5 */
    public String ownerVersion(String section) {
        thread(); Entry e = entries.get(section);
        return e == null || !e.owner.isEnabled() ? "" : e.owner.getDescription().getVersion();
    }

    private final Plugin core;
    private final Supplier<? extends DialogInterface> dialogs;
    private final Consumer<Player> home;

    /** @param home opens the main menu (target of every "Hauptmenü" button) */
    public SettingsRegistry(Plugin core, Supplier<? extends DialogInterface> dialogs, Consumer<Player> home) {
        this.core = Objects.requireNonNull(core); this.dialogs = Objects.requireNonNull(dialogs); this.home = Objects.requireNonNull(home);
    }

    private static Vystorm.vystorm_core.lang.Lang lang() { return Vystorm.vystorm_core.lang.Localization.get().core(); }
    /** Titles, labels and descriptions may be {@code Lang.ref} references (resolved per viewer). */
    private static Component text(Player viewer, String text) { return Vystorm.vystorm_core.lang.Localization.get().component(viewer, text); }
    private static String plain(Player viewer, String text) { return Vystorm.vystorm_core.lang.Localization.get().resolve(viewer, text); }

    private static void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Settings operations require the server thread");
    }

    @Override public void register(Plugin owner, SettingsSection section) {
        thread(); Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(section, "section");
        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + section.id();
        if (entries.containsKey(key)) throw new IllegalArgumentException("Settings section already registered: " + key);
        entries.put(key, new Entry(owner, key, section));
        DebugMessages.mid("[Settings] Section registered: " + key + "; scope=" + section.scope() + "; settings=" + section.settings().size());
    }

    /**
     * Replaces an already registered section of {@code owner} (same id) in place, keeping its position and policies;
     * registers it if it is new. Used by Core for option lists that change at runtime (the language list). Server
     * thread only. @since 0.45.0
     */
    public void replace(Plugin owner, SettingsSection section) {
        thread(); Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(section, "section");
        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + section.id();
        Entry existing = entries.get(key);
        if (existing != null && existing.owner != owner) throw new IllegalArgumentException("Settings section owned by another plugin: " + key);
        entries.put(key, new Entry(owner, key, section));
    }

    @Override public void unregister(Plugin owner) { thread(); entries.values().removeIf(e -> { if (e.owner != owner) return false; policies.keySet().removeIf(k -> k.startsWith(e.key + "/")); groups.remove(e.key); return true; }); }
    @Override public void clear() { thread(); entries.clear(); policies.clear(); groups.clear(); }

    /**
     * Removes one section of {@code owner} (its policies too); false if it is not registered or belongs to another
     * plugin. Server thread only.
     *
     * @since 0.27.0
     */
    public boolean unregister(Plugin owner, String sectionId) {
        thread();
        if (owner == null || sectionId == null) return false;
        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + sectionId;
        Entry e = entries.get(key);
        if (e == null || e.owner != owner) return false;
        entries.remove(key);
        policies.keySet().removeIf(k -> k.startsWith(key + "/"));
        groups.remove(key);
        return true;
    }

    // ------------------------------------------------------------------ grouped sections (0.27.0, ConfigEditor)

    /** Section key → group; grouped sections are listed behind one entry instead of one button each. */
    private final Map<String, String> groups = new HashMap<>();
    private java.util.function.BiConsumer<Player, String> groupOpener;

    /** Marks a registered section as part of the config-editor group (hidden from the flat server list). */
    void group(String key, String group) { thread(); if (entries.containsKey(key)) groups.put(key, Objects.requireNonNull(group)); }

    /** Opens the grouped config sections (set by {@link ConfigEditor}); the key is the section coming back from, or null. */
    void groupOpener(java.util.function.BiConsumer<Player, String> opener) { this.groupOpener = opener; }

    /**
     * The group of a section key ({@code ConfigEditor} sections: {@code "config"}), {@code ""} for an ordinary section.
     * Server thread only.
     *
     * @since 0.27.0
     */
    public String group(String key) { thread(); return key == null ? "" : groups.getOrDefault(key.toLowerCase(Locale.ROOT), ""); }

    private boolean allowed(Entry e, Player player) { return e.owner.isEnabled() && e.section.allowed(player); }

    private List<Entry> visible(Player player, SettingScope scope) {
        return entries.values().stream().filter(e -> e.section.scope() == scope && allowed(e, player)).toList();
    }

    @Override public List<String> keys(Player player) {
        return entries.values().stream().filter(e -> allowed(e, player)).map(Entry::key).toList();
    }

    @Override public List<SettingsSection> sections(Player player, SettingScope scope) {
        return visible(player, scope).stream().map(Entry::section).toList();
    }

    /**
     * Opens Core's settings panel as a screen (0.66.0, set by {@code SettingsPanels}): {@code arg} "" = all,
     * {@code "server"} = server tab, a section key = only that section. Returns false when no screen could open; then the
     * dialogs below follow as before.
     */
    private java.util.function.BiPredicate<Player, String> screen;

    /** Core-internal: where {@link #openOverview}, {@link #openScope} and {@link #openSection} try the settings screen first. */
    public void screenOpener(java.util.function.BiPredicate<Player, String> opener) { this.screen = opener; }

    private boolean screen(Player player, String arg) {
        if (screen == null) return false;
        try { return screen.test(player, arg); }
        catch (RuntimeException failure) { DebugMessages.high("[Settings] settings screen failed: " + failure); return false; }
    }

    @Override public void openOverview(Player player) {
        thread();
        if (screen(player, "")) return;
        openOverviewDialog(player);
    }

    /** The overview as native dialogs (also the settings panel's fallback). Server thread only. @since 0.66.0 */
    public void openOverviewDialog(Player player) {
        thread();
        boolean admin = !visible(player, SettingScope.SERVER).isEmpty();
        if (!admin) { openScope(player, SettingScope.PLAYER); return; }
        List<DialogButton> buttons = new ArrayList<>();
        buttons.add(new DialogButton(lang().component(player, "settings.personal"),
                lang().component(player, "settings.personal-tooltip"), (p, r) -> openScope(p, SettingScope.PLAYER)));
        buttons.add(new DialogButton(lang().component(player, "settings.server"),
                lang().component(player, "settings.server-tooltip"), (p, r) -> openScope(p, SettingScope.SERVER)));
        buttons.add(homeButton(player));
        dialogs.get().open(core, player, new DialogRequest(lang().component(player, "settings.title"),
                List.of(DialogBody.plainMessage(lang().component(player, "settings.choose"))), List.of(), buttons,
                Player::isOnline, () -> {}, "Schließen", 2));
    }

    @Override public void openScope(Player player, SettingScope scope) {
        if (screen(player, scope == SettingScope.SERVER ? "server" : "")) return;
        page(player, scope, 0);
    }

    private void page(Player player, SettingScope scope, int requested) {
        thread();
        List<Entry> all = visible(player, scope);
        List<Entry> visible = all.stream().filter(e -> !groups.containsKey(e.key)).toList();
        boolean grouped = groupOpener != null && all.size() > visible.size();
        int pages = Math.max(1, (visible.size() + SECTIONS_PER_PAGE - 1) / SECTIONS_PER_PAGE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        List<DialogButton> buttons = new ArrayList<>();
        for (Entry e : visible.subList(page * SECTIONS_PER_PAGE, Math.min(visible.size(), (page + 1) * SECTIONS_PER_PAGE))) {
            Component tip = e.section.description().isBlank() ? null : text(player, e.section.description());
            buttons.add(new DialogButton(text(player, e.section.title()), tip, (p, r) -> {
                if (!openSection(p, e.key)) lang().send(p, "common.settings-gone");
            }));
        }
        if (grouped) buttons.add(new DialogButton(lang().component(player, "config.entry"), lang().component(player, "config.entry-tooltip"),
                (p, r) -> groupOpener.accept(p, null)));
        if (scope == SettingScope.SERVER) buttons.add(new DialogButton(lang().component(player, "settings.draft.title"),
                (p, r) -> SettingsDraftDialog.open(p, this)));
        if (page > 0) buttons.add(new DialogButton(lang().component(player, "common.previous"), (p, r) -> page(p, scope, page - 1)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang().component(player, "common.next"), (p, r) -> page(p, scope, page + 1)));
        if (scope == SettingScope.SERVER || !visible(player, SettingScope.SERVER).isEmpty())
            buttons.add(new DialogButton(lang().component(player, "settings.up"), (p, r) -> openOverview(p)));
        if (webAvailable()) buttons.add(new DialogButton(lang().component(player, "settings.web"), lang().component(player, "settings.web-tooltip"),
                (p, r) -> Vystorm.vystorm_core.web.WebServices.openOrLink(p, WEB_MODULE, "", null)));
        buttons.add(homeButton(player));
        Component title = lang().component(player, scope == SettingScope.PLAYER ? "settings.personal" : "settings.server-title");
        Component text = lang().component(player, all.isEmpty() ? "settings.empty"
                : scope == SettingScope.PLAYER ? "settings.personal-text" : "settings.server-text");
        dialogs.get().open(core, player, new DialogRequest(pages > 1 ? title.append(Component.text(" " + (page + 1) + "/" + pages)) : title,
                List.of(DialogBody.plainMessage(text)), List.of(), buttons, Player::isOnline, () -> {}, "Schließen", 2));
    }

    @Override public boolean openSection(Player player, String key) {
        thread();
        Entry e = find(key);
        if (e == null || !allowed(e, player)) return false;
        // config-editor sections can hold thousands of values: they keep their dialog (ConfigEditor opens them)
        if (!groups.containsKey(e.key) && screen(player, e.key)) return true;
        section(player, e, List.of());
        return true;
    }

    Entry find(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        Entry e = entries.get(k);
        if (e == null && !k.contains(":")) {
            List<Entry> matches = entries.values().stream().filter(v -> v.section.id().equals(k)).toList();
            if (matches.size() == 1) e = matches.getFirst();
        }
        return e;
    }

    private void section(Player player, Entry e, List<Component> status) {
        List<Setting> settings = e.section.settings().stream().filter(s -> s.allowed(player)).toList();
        List<DialogInput> inputs = new ArrayList<>();
        List<Setting> shown = new ArrayList<>();
        List<Component> body = new ArrayList<>(status);
        if (!e.section.description().isBlank()) body.add(text(player, e.section.description()).colorIfAbsent(NamedTextColor.GRAY));
        for (Setting s : settings) {
            Object value;
            try { value = s.getter().apply(player); }
            catch (RuntimeException ex) { failure(e, "read " + s.id(), ex); body.add(lang().component(player, "settings.read-failed", "label", plain(player, s.label()))); continue; }
            inputs.add(input("v" + shown.size(), s, value, player));
            shown.add(s);
            if (!s.description().isBlank()) body.add(Component.text("• ", NamedTextColor.WHITE).append(text(player, s.label())).append(Component.text(": "))
                    .append(text(player, s.description()).colorIfAbsent(NamedTextColor.GRAY)));
        }
        List<DialogButton> buttons = new ArrayList<>();
        if (!shown.isEmpty()) buttons.add(new DialogButton(lang().component(player, "settings.save"),
                (p, r) -> apply(p, e, shown, r)));
        var back = groups.containsKey(e.key) ? groupOpener : null;
        buttons.add(new DialogButton(lang().component(player, "settings.overview"),
                (p, r) -> { if (back != null) back.accept(p, e.key); else openScope(p, e.section.scope()); }));
        buttons.add(homeButton(player));
        Component scopeName = lang().component(player, e.section.scope() == SettingScope.PLAYER ? "settings.title" : "settings.server-title");
        dialogs.get().open(core, player, new DialogRequest(scopeName.append(Component.text(" · ")).append(text(player, e.section.title())),
                body.stream().map(c -> (DialogBody) DialogBody.plainMessage(c, 300)).toList(), inputs, buttons,
                p -> allowed(e, p), () -> {}, "Schließen", 3));
    }

    static DialogInput input(String key, Setting s, Object value) { return input(key, s, value, null); }

    static DialogInput input(String key, Setting s, Object value, Player viewer) {
        Component label = text(viewer, s.label());
        return switch (s.kind()) {
            case TOGGLE -> DialogInput.bool(key, label).initial(Boolean.TRUE.equals(value)).build();
            case NUMBER -> {
                double v = value instanceof Number n ? n.doubleValue() : s.min();
                v = Math.max(s.min(), Math.min(s.max(), v));
                yield DialogInput.numberRange(key, label, (float) s.min(), (float) s.max())
                        .step((float) s.step()).initial((float) v).width(300).build();
            }
            case CHOICE -> {
                String current = value == null ? null : value.toString();
                boolean known = s.options().stream().anyMatch(o -> o.id().equals(current));
                List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
                for (int i = 0; i < s.options().size(); i++) {
                    Setting.Option o = s.options().get(i);
                    options.add(SingleOptionDialogInput.OptionEntry.create(o.id(), text(viewer, o.label()),
                            known ? o.id().equals(current) : i == 0));
                }
                yield DialogInput.singleOption(key, label, options).width(300).build();
            }
            case TEXT -> {
                String current = value == null ? "" : value.toString();
                if (current.length() > s.maxLength()) current = current.substring(0, s.maxLength());
                yield DialogInput.text(key, label).initial(current).maxLength(s.maxLength()).width(300).build();
            }
        };
    }

    /** Converts a dialog answer to the setting's value type; null when the payload does not fit. */
    static Object read(String key, Setting s, DialogResponseView response) {
        return switch (s.kind()) {
            case TOGGLE -> response.getBoolean(key);
            case NUMBER -> {
                Float f = response.getFloat(key);
                if (f == null || !Float.isFinite(f)) yield null;
                yield snap(s, f);
            }
            case CHOICE -> {
                String v = response.getText(key);
                yield v != null && s.options().stream().anyMatch(o -> o.id().equals(v)) ? v : null;
            }
            case TEXT -> {
                String v = response.getText(key);
                yield v == null || v.length() > s.maxLength() ? null : v;
            }
        };
    }

    static double snap(Setting s, double value) {
        double v = Math.max(s.min(), Math.min(s.max(), s.min() + Math.rint((value - s.min()) / s.step()) * s.step()));
        return Math.abs(v - Math.rint(v)) < 1e-6 ? Math.rint(v) : v;
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Math.abs(x.doubleValue() - y.doubleValue()) < 1e-9;
        return Objects.equals(a, b);
    }

    void apply(Player player, Entry e, List<Setting> shown, DialogResponseView response) {
        if (!allowed(e, player)) { lang().send(player, "common.settings-gone"); return; }
        Map<Setting, String> keys = new IdentityHashMap<>();
        for (int i = 0; i < shown.size(); i++) keys.put(shown.get(i), "v" + i);
        SaveResult result = commit(player, e, shown, s -> response == null ? null : read(keys.get(s), s, response), "dialog");
        List<Component> status = new ArrayList<>();
        for (Problem problem : result.problems()) {
            status.add(switch (problem.kind()) {
                case INVALID -> lang().component(player, "settings.invalid", "label", plain(player, problem.label()));
                case REJECTED -> lang().component(player, "settings.error", "label", plain(player, problem.label()), "error", plain(player, problem.error()));
                case FAILED -> lang().component(player, "settings.save-failed", "label", plain(player, problem.label()));
            });
        }
        if (result.applyFailed()) status.add(lang().component(player, "settings.apply-failed"));
        int changed = result.changed();
        status.addFirst(changed == 0 ? lang().component(player, "settings.no-changes")
                : changed == 1 ? lang().component(player, "settings.saved-one") : lang().component(player, "settings.saved-many", "count", changed));
        if (e.owner.isEnabled() && e.section.allowed(player)) section(player, e, status);
    }

    /**
     * The one save path of the in-game dialog and the web page: for every candidate the player may change, {@code next}
     * delivers the already validated new value (null = invalid); unchanged values are skipped, the owner's setter decides,
     * {@code afterSave} runs once after at least one change, and change listeners are told.
     */
    SaveResult commit(Player player, Entry e, List<Setting> candidates, java.util.function.Function<Setting, Object> next, String source) {
        List<Problem> problems = new ArrayList<>();
        int changed = 0;
        for (Setting s : candidates) {
            if (!s.allowed(player)) continue;
            Object value = next.apply(s);
            if (value == null) { problems.add(new Problem(s.id(), s.label(), Problem.Kind.INVALID, null)); continue; }
            try {
                Object current = s.getter().apply(player);
                // An untouched slider snaps an off-grid value to the step grid; that is not a change.
                if (same(current, value) || (s.kind() == Setting.Kind.NUMBER && current instanceof Number n && same(snap(s, n.doubleValue()), value))) continue;
                String error = s.setter().apply(player, value);
                if (error == null) changed++;
                else problems.add(new Problem(s.id(), s.label(), Problem.Kind.REJECTED, error));
            } catch (RuntimeException ex) {
                failure(e, "write " + s.id(), ex);
                problems.add(new Problem(s.id(), s.label(), Problem.Kind.FAILED, null));
            }
        }
        boolean applyFailed = false;
        if (changed > 0) {
            try { e.section.afterSave().accept(player); }
            catch (RuntimeException ex) { failure(e, "afterSave", ex); applyFailed = true; }
            if (e.section.scope() == SettingScope.SERVER)
                DebugMessages.mid("[Settings] " + player.getName() + " changed " + changed + " value(s) in " + e.key + " (" + source + ")");
            fire(e.section.scope() == SettingScope.SERVER ? null : player, e.key);
        }
        return new SaveResult(true, changed, List.copyOf(problems), applyFailed);
    }

    // ------------------------------------------------------------------ web view and save (0.24.0)

    /**
     * Outcome of a save through {@link #save}. {@code found} is false when the section does not exist or the player may
     * not open it (then nothing was written).
     *
     * @since 0.24.0
     */
    public record SaveResult(boolean found, int changed, List<Problem> problems, boolean applyFailed) {}

    /**
     * A value that was not saved: {@code INVALID} (wrong type, unknown option, out of range text), {@code REJECTED} (the
     * owner's setter returned {@code error}), {@code FAILED} (the setter threw; logged). {@code label} may be a
     * {@code Lang.ref}.
     *
     * @since 0.24.0
     */
    public record Problem(String settingId, String label, Kind kind, String error) {
        public enum Kind { INVALID, REJECTED, FAILED }
    }

    /** One setting with its current value for a viewer; {@code readable} is false when the getter failed. @since 0.24.0 */
    public record SettingView(String sectionKey, Setting setting, Object value, boolean readable) {}

    /** A section the viewer may open, with the settings the viewer may change. @since 0.24.0 */
    public record SectionView(String key, SettingsSection section, List<SettingView> settings) {}

    /** Listener for changed values: {@code player} null = server-wide values; {@code sectionKey} null = unknown. @since 0.24.0 */
    @FunctionalInterface
    public interface ChangeListener { void changed(Player player, String sectionKey); }

    private final List<ChangeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Adds a change listener (e.g. Core's web settings page); returns a handle that removes it again. @since 0.24.0 */
    public Runnable onChange(ChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override public void changed(Player player) { fire(player, null); }

    private void fire(Player player, String key) {
        for (ChangeListener listener : listeners) {
            try { listener.changed(player, key); }
            catch (RuntimeException ex) { DebugMessages.high("[Settings] change listener failed: " + ex); }
        }
    }

    /**
     * All sections the player may open (personal first, then server sections for admins), with current values; the same
     * permission checks as the dialogs. Server thread only.
     *
     * @since 0.24.0
     */
    public List<SectionView> view(Player player) {
        thread();
        List<SectionView> out = new ArrayList<>();
        for (SettingScope scope : SettingScope.values())
            for (Entry e : visible(player, scope)) out.add(view(player, e));
        return out;
    }

    /** One section for the player, empty if missing or not allowed. Server thread only. @since 0.24.0 */
    public Optional<SectionView> view(Player player, String key) {
        thread();
        Entry e = key == null ? null : find(key);
        return e == null || !allowed(e, player) ? Optional.empty() : Optional.of(view(player, e));
    }

    private SectionView view(Player player, Entry e) {
        List<SettingView> settings = new ArrayList<>();
        for (Setting s : e.section.settings()) {
            if (!s.allowed(player)) continue;
            try { settings.add(new SettingView(e.key, s, s.getter().apply(player), true)); }
            catch (RuntimeException ex) { failure(e, "read " + s.id(), ex); settings.add(new SettingView(e.key, s, null, false)); }
        }
        return new SectionView(e.key, e.section, List.copyOf(settings));
    }

    /**
     * Saves values from another front end (Core's web page) through the same path as the in-game dialog: section and
     * setting permissions, type/range/option validation ({@link #coerce}), unchanged values skipped, the owner's setter,
     * {@code afterSave} once, change listeners. Unknown or hidden setting ids count as {@code INVALID}. Server thread only.
     *
     * @param values setting id → {@link Boolean}, {@link Number} or {@link String}
     * @since 0.24.0
     */
    public SaveResult save(Player player, String key, Map<String, ?> values, String source) {
        thread();
        Objects.requireNonNull(player, "player");
        Entry e = key == null ? null : find(key);
        if (e == null || !allowed(e, player)) return new SaveResult(false, 0, List.of(), false);
        Map<String, ?> input = values == null ? Map.of() : values;
        List<Setting> candidates = new ArrayList<>();
        List<Problem> unknown = new ArrayList<>();
        for (String id : input.keySet()) {
            Setting s = e.section.settings().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (s == null || !s.allowed(player)) unknown.add(new Problem(id, id, Problem.Kind.INVALID, null));
            else candidates.add(s);
        }
        SaveResult result = commit(player, e, candidates, s -> coerce(s, input.get(s.id())), source == null ? "api" : source);
        if (unknown.isEmpty()) return result;
        List<Problem> problems = new ArrayList<>(unknown);
        problems.addAll(result.problems());
        return new SaveResult(true, result.changed(), List.copyOf(problems), result.applyFailed());
    }

    /**
     * Checks a raw value from outside (web JSON) exactly like a dialog answer ({@link #read}): toggle = Boolean, number =
     * finite Number snapped to the step grid and clamped, choice = one of the option ids, text = String up to
     * {@code maxLength}. Null when it does not fit.
     *
     * @since 0.24.0
     */
    public static Object coerce(Setting s, Object raw) {
        if (s == null || raw == null) return null;
        return switch (s.kind()) {
            case TOGGLE -> raw instanceof Boolean b ? b : null;
            case NUMBER -> raw instanceof Number n && Double.isFinite(n.doubleValue()) ? snap(s, n.doubleValue()) : null;
            case CHOICE -> raw instanceof String v && s.options().stream().anyMatch(o -> o.id().equals(v)) ? v : null;
            case TEXT -> raw instanceof String v && v.length() <= s.maxLength() ? v : null;
        };
    }

    /** Module id of Core's web settings page. */
    private static final String WEB_MODULE = "settings";

    private static boolean webAvailable() {
        try { return Vystorm.vystorm_core.web.WebServices.available() && Vystorm.vystorm_core.web.WebServices.registered(WEB_MODULE); }
        catch (RuntimeException | LinkageError failure) { return false; }
    }

    private DialogButton homeButton(Player viewer) { return new DialogButton(lang().component(viewer, "menu.home"), (p, r) -> home.accept(p)); }

    private static void failure(Entry e, String what, RuntimeException ex) {
        DebugMessages.high("[Settings] " + e.key + " " + what + " failed: " + ex);
    }
}
