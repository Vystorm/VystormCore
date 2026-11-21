package Vystorm.vystorm_core.compat.internal;

import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogResponseView;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Dialogs as Vystorm Screens (since 0.59.0): plugin dialogs open as a screen in the look of the other menus: title,
 * the body text, one button per choice and Close. Since 0.61.0 dialogs with input fields are screens too
 * ({@code screens.dialog-inputs}): one row per field; switches and choices change with a click, numbers with − and +,
 * and a click on a text or number box opens a one-field typing dialog and returns to the screen. Dialogs on servers
 * without screens and {@code screens.dialogs: false} keep the native dialog of {@code delegate}. Server thread.
 */
public final class ScreenDialogs implements Vystorm.vystorm_core._uiMethods.dialog.DialogInterface {
    private static final DialogResponseView EMPTY = new DialogResponseView() {
        @Override public String getText(String key) { return null; }
        @Override public Boolean getBoolean(String key) { return null; }
        @Override public Float getFloat(String key) { return null; }
    };
    private final Vystorm.vystorm_core._uiMethods.dialog.DialogInterface delegate;
    private final Map<UUID, ScreenSession> open = new HashMap<>();

    public ScreenDialogs(Vystorm.vystorm_core._uiMethods.dialog.DialogInterface delegate) { this.delegate = delegate; }

    public Vystorm.vystorm_core._uiMethods.dialog.DialogInterface delegate() { return delegate; }

    @Override
    public void open(Plugin owner, Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request) {
        if ((request.inputs().isEmpty() || inputsEnabled()) && enabled() && Bukkit.isPrimaryThread()) {
            try { if (openScreen(player, request, initialValues(request))) return; }
            catch (RuntimeException | LinkageError unavailable) { /* fall back to the native dialog */ }
        }
        delegate.open(owner, player, request);
    }

    @Override public void open(Plugin owner, Player player, Vystorm.vystorm_core._uiMethods.dialog.DialogRequest request) { delegate.open(owner, player, request); }
    @Override public void close(Player player) { closeScreen(player.getUniqueId()); delegate.close(player); }
    @Override public void removeAll(Plugin owner) { delegate.removeAll(owner); }
    // 0.66.2: the inherited defaults build Paper dialog types (NoClassDefFoundError on Spigot and Paper 1.20.6); the
    // wrapped handler knows the platform (native dialog or chat) and builds the same request as before on Paper.
    @Override public void prompt(Plugin owner, Player player, String title, String question, String initial,
                                 java.util.function.Consumer<String> submit, Runnable cancel, java.util.function.Predicate<Player> valid) {
        delegate.prompt(owner, player, title, question, initial, submit, cancel, valid);
    }
    @Override public void confirm(Plugin owner, Player player, String title, String message,
                                  Runnable submit, Runnable cancel, java.util.function.Predicate<Player> valid) {
        delegate.confirm(owner, player, title, message, submit, cancel, valid);
    }
    @Override public void clear() { for (UUID id : List.copyOf(open.keySet())) closeScreen(id); delegate.clear(); }
    @Override public void handle(Player player, net.kyori.adventure.key.Key key, io.papermc.paper.dialog.DialogResponseView response) { delegate.handle(player, key, response); }

    private static boolean enabled() {
        Plugin core = Vystorm.vystorm_core.main.getPlugin();
        return core != null && core.isEnabled() && core.getConfig().getBoolean("screens.dialogs", true);
    }

    private static boolean inputsEnabled() {
        Plugin core = Vystorm.vystorm_core.main.getPlugin();
        return core != null && core.getConfig().getBoolean("screens.dialog-inputs", true);
    }

    /** Start values of the input fields by key: String (text, option id), Boolean or Float. */
    static Map<String, Object> initialValues(Vystorm.vystorm_core.compat.dialog.DialogRequest request) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (DialogInput input : request.inputs()) {
            switch (input.kind()) {
                case TEXT -> values.put(input.key(), input.initial() == null ? "" : String.valueOf(input.initial()));
                case BOOL -> values.put(input.key(), Boolean.TRUE.equals(input.initial()));
                case RANGE -> values.put(input.key(), clamp(input, input.initial() instanceof Number n ? n.floatValue() : input.start()));
                case OPTIONS -> {
                    String chosen = input.entries().isEmpty() ? "" : input.entries().get(0).id();
                    for (var entry : input.entries()) if (entry.initial()) { chosen = entry.id(); break; }
                    values.put(input.key(), chosen);
                }
            }
        }
        return values;
    }

    static float clamp(DialogInput input, float value) {
        float low = Math.min(input.start(), input.end()), high = Math.max(input.start(), input.end());
        return Math.max(low, Math.min(high, value));
    }

    static float step(DialogInput input) {
        if (input.step() != null && input.step() > 0) return input.step();
        float span = Math.abs(input.end() - input.start());
        return span <= 0 ? 1 : span >= 20 ? Math.max(1, Math.round(span / 20)) : span / 20;
    }

    static String number(float value) {
        return Math.abs(value - Math.rint(value)) < 1e-4 ? Long.toString(Math.round(value)) : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** What the buttons get: the values on the screen, read like the answers of a native dialog. */
    private static DialogResponseView response(Map<String, Object> values) {
        return new DialogResponseView() {
            @Override public String getText(String key) { Object v = values.get(key); return v instanceof String s ? s : null; }
            @Override public Boolean getBoolean(String key) { Object v = values.get(key); return v instanceof Boolean b ? b : null; }
            @Override public Float getFloat(String key) { Object v = values.get(key); return v instanceof Float f ? f : null; }
        };
    }

    /** A click on an input row: switch, next choice, − / +, or the typing dialog for text and numbers. */
    private void input(Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request, Map<String, Object> values,
                       ScreenSession session, String id, boolean right) {
        boolean dec = id.startsWith("dec"), inc = id.startsWith("inc");
        int index;
        try { index = Integer.parseInt(id.substring(dec || inc ? 3 : 2)); } catch (NumberFormatException other) { return; }
        if (index < 0 || index >= request.inputs().size()) return;
        DialogInput input = request.inputs().get(index);
        switch (input.kind()) {
            case BOOL -> values.put(input.key(), !Boolean.TRUE.equals(values.get(input.key())));
            case OPTIONS -> {
                if (input.entries().isEmpty()) return;
                int at = 0;
                for (int i = 0; i < input.entries().size(); i++) if (input.entries().get(i).id().equals(values.get(input.key()))) at = i;
                int next = Math.floorMod(at + (right ? -1 : 1), input.entries().size());
                values.put(input.key(), input.entries().get(next).id());
            }
            case RANGE -> {
                if (!dec && !inc) { type(player, request, values, input); return; }
                float now = values.get(input.key()) instanceof Float f ? f : input.start();
                values.put(input.key(), clamp(input, now + (dec ? -step(input) : step(input))));
            }
            case TEXT -> { type(player, request, values, input); return; }
        }
        if (session.isOpen()) session.setLayout(layout(Screens.of(Vystorm.vystorm_core.main.getPlugin()).profile(player), player, request, values));
    }

    /** Typing happens in a one-field Paper dialog (a screen cannot read keys); afterwards the screen shows the value. */
    private void type(Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request, Map<String, Object> values, DialogInput input) {
        Plugin core = Vystorm.vystorm_core.main.getPlugin();
        var lang = Localization.get().core();
        boolean numeric = input.kind() == DialogInput.Kind.RANGE;
        Object current = values.get(input.key());
        DialogInput.Builder field = DialogInput.text("value", input.label())
                .initial(numeric ? number(current instanceof Float f ? f : input.start()) : String.valueOf(current == null ? "" : current))
                .maxLength(numeric ? 16 : Math.max(1, input.maxLength())).width(Math.max(200, input.width()));
        if (!numeric && input.multiline() != null) field.multiline(input.multiline());
        Runnable back = () -> { if (player.isOnline() && request.valid().test(player)) { try { if (!openScreen(player, request, values)) delegate.open(core, player, request); } catch (RuntimeException | LinkageError failure) { delegate.open(core, player, request); } } };
        DialogButton apply = new DialogButton(lang.component(player, "panel.dialog.apply"), (p, answer) -> {
            String text = answer.getText("value");
            if (text != null) {
                if (!numeric) values.put(input.key(), text);
                else try { values.put(input.key(), clamp(input, Float.parseFloat(text.trim().replace(',', '.')))); } catch (NumberFormatException notANumber) { /* keep the old value */ }
            }
            back.run();
        });
        delegate.open(core, player, new Vystorm.vystorm_core.compat.dialog.DialogRequest(request.title(), List.of(), List.of(field.build()), List.of(apply),
                Player::isOnline, back, lang.plain(player, "menu.back"), 2));
    }

    private void closeScreen(UUID id) {
        ScreenSession session = open.remove(id);
        if (session != null && session.isOpen()) session.close();
    }

    private boolean openScreen(Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request, Map<String, Object> values) {
        Plugin core = Vystorm.vystorm_core.main.getPlugin();
        Screens screens = Screens.of(core);
        boolean[] handled = { false };
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:dialog", layout(screens.profile(player), player, request, values))
                .onInput((session, input) -> {
                    if (input instanceof ScreenInput.Closed) {
                        open.remove(player.getUniqueId(), session);
                        if (!handled[0]) { handled[0] = true; run(request.cancel()); }
                        return;
                    }
                    if (!(input instanceof ScreenInput.Click click) || click.widgetId() == null) return;
                    String id = click.widgetId();
                    if (id.equals("close")) { session.close(); return; }
                    if (id.startsWith("in") || id.startsWith("dec") || id.startsWith("inc")) {
                        // a text or number box hands over to the typing dialog: that is not a cancel
                        boolean typing = id.startsWith("in") && typed(request, id);
                        if (typing) { handled[0] = true; open.remove(player.getUniqueId(), session); session.close(); }
                        input(player, request, values, session, id, click.right());
                        return;
                    }
                    if (!id.startsWith("b")) return;
                    int index;
                    try { index = Integer.parseInt(id.substring(1)); } catch (NumberFormatException ignored) { return; }
                    if (index < 0 || index >= request.buttons().size()) return;
                    handled[0] = true;
                    open.remove(player.getUniqueId(), session);
                    session.close();
                    if (!request.valid().test(player)) return;
                    DialogButton button = request.buttons().get(index);
                    button.action().accept(player, values.isEmpty() ? EMPTY : response(values));
                })
                .build();
        // 0.61.0: a dialog screen is a place in the screen history (Back from the next screen shows it again); it keeps
        // its own buttons, so Core adds no Back button to it
        String key = "dialog:" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(request.title());
        if (!Vystorm.vystorm_core.screen.internal.ScreenRuntime.get().open(core, player, spec,
                new Vystorm.vystorm_core.screen.internal.ScreenRuntime.Origin(key, p -> { if (request.valid().test(p)) open(core, p, request); }, false)).opened()) return false;
        screens.session(player).ifPresent(session -> open.put(player.getUniqueId(), session));
        return true;
    }

    private static void run(Runnable action) { if (action != null) action.run(); }

    /** True when a click on this input row opens the typing dialog (text fields and number boxes). */
    private static boolean typed(Vystorm.vystorm_core.compat.dialog.DialogRequest request, String id) {
        try {
            DialogInput.Kind kind = request.inputs().get(Integer.parseInt(id.substring(2))).kind();
            return kind == DialogInput.Kind.TEXT || kind == DialogInput.Kind.RANGE;
        } catch (RuntimeException other) { return false; }
    }

    static String plain(Component component, Player player) {
        Component rendered = Localization.get().render(player, component);
        return PlainTextComponentSerializer.plainText().serialize(rendered);
    }

    static ScreenLayout layout(ScreenProfile profile, Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request) {
        return layout(profile, player, request, Map.of());
    }

    static ScreenLayout layout(ScreenProfile profile, Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request, Map<String, Object> values) {
        double s = profile.scale(), margin = 0.04;
        double w = Math.max(1.2, Math.min(profile.viewWidth() * 0.85, 2.0 * s));
        double maxH = Math.max(0.8, profile.viewHeight() * 0.85);
        double line = 0.04 * s, head = 0.1 * s, closeW = 0.2 * s;
        int columns = Math.max(1, Math.min(4, request.columns()));
        double bh = 0.062 * s, gap = 0.015 * s;   // like panel buttons: low enough for centred text
        int rows = (request.buttons().size() + columns - 1) / columns;
        double buttonsH = rows * (bh + gap);
        int fields = values.isEmpty() ? 0 : request.inputs().size();
        double fieldH = 0.066 * s, fieldsH = fields == 0 ? 0 : fields * (fieldH + gap) + 0.01 * s;
        List<ScreenWidget> widgets = new ArrayList<>();
        widgets.add(ScreenWidget.label("title", margin, 0.02 * s, w - 2 * margin - closeW, 0.06 * s,
                Component.text(plain(request.title(), player)).color(TextColor.color(0xF2F2F2)).decorate(TextDecoration.BOLD))
                .withAlign(ScreenWidget.Align.START));
        widgets.add(ScreenWidget.button("close", w - margin - closeW, 0.02 * s, closeW, 0.06 * s,
                Localization.get().core().component(player, "menu.screen.close")).withTextHeight(0.034 * s));
        widgets.add(ScreenWidget.fill("line", 0, head, w, 0.004 * s, 0xFF343B47));

        List<String> lines = new ArrayList<>();
        double textW = w - 2 * margin;
        for (DialogBody body : request.body()) {
            if (body.item() != null) {
                var meta = body.item().getItemMeta();
                @SuppressWarnings("deprecation") String name = meta != null && meta.hasDisplayName() ? org.bukkit.ChatColor.stripColor(meta.getDisplayName()) : body.item().getType().name().toLowerCase().replace('_', ' ');
                lines.add("• " + name);
            } else {
                lines.addAll(wrap(plain(body.contents(), player), textW, line));
            }
        }
        double available = maxH - head - 0.06 * s - buttonsH - fieldsH - 0.04 * s;
        int fit = (int) Math.max(0, Math.floor(available / (line * 1.15)));
        if (lines.size() > fit) { lines = new ArrayList<>(lines.subList(0, Math.max(0, fit - 1))); lines.add("…"); }
        double y = head + 0.03 * s;
        for (int k = 0; k < lines.size(); k++) {
            widgets.add(ScreenWidget.label("t" + k, margin, y, textW, line, Component.text(lines.get(k)).color(TextColor.color(0xD6DAE0)))
                    .withAlign(ScreenWidget.Align.START));
            y += line * 1.15;
        }
        y += lines.isEmpty() ? 0 : 0.02 * s;
        // input fields (0.61.0): label on the left, the value as a box you click on the right
        for (int i = 0; i < fields; i++) {
            DialogInput input = request.inputs().get(i);
            Object value = values.get(input.key());
            double labelW = (w - 2 * margin) * 0.42, boxX = margin + labelW + gap, boxW = w - margin - boxX;
            widgets.add(ScreenWidget.label("il" + i, margin, y + 0.014 * s, labelW, 0.036 * s,
                    Component.text(plain(input.label(), player)).color(TextColor.color(0xD6DAE0))).withAlign(ScreenWidget.Align.START));
            switch (input.kind()) {
                case BOOL -> {
                    boolean on = Boolean.TRUE.equals(value);
                    widgets.add(ScreenWidget.button("in" + i, boxX, y, boxW, fieldH,
                            Localization.get().core().component(player, on ? "features.state-on" : "features.state-off"))
                            .withTextHeight(0.034 * s).withFill(on ? 0xC02E7D46 : 0xC08A3A3A));
                }
                case OPTIONS -> {
                    String shown = String.valueOf(value);
                    for (var entry : input.entries()) if (entry.id().equals(value)) shown = plain(entry.display(), player);
                    widgets.add(ScreenWidget.button("in" + i, boxX, y, boxW, fieldH, Component.text("< " + shown + " >")).withTextHeight(0.034 * s));
                }
                case RANGE -> {
                    double small = fieldH;
                    widgets.add(ScreenWidget.button("dec" + i, boxX, y, small, fieldH, Component.text("−")).withTextHeight(0.04 * s));
                    widgets.add(ScreenWidget.input("in" + i, boxX + small + gap, y, boxW - 2 * (small + gap), fieldH,
                            Component.text(number(value instanceof Float f ? f : input.start()))).withTextHeight(0.034 * s));
                    widgets.add(ScreenWidget.button("inc" + i, boxX + boxW - small, y, small, fieldH, Component.text("+")).withTextHeight(0.04 * s));
                }
                case TEXT -> {
                    String text = value == null ? "" : String.valueOf(value).replace('\n', ' ');
                    if (text.length() > 48) text = text.substring(0, 47) + "…";
                    widgets.add(ScreenWidget.input("in" + i, boxX, y, boxW, fieldH,
                            text.isEmpty() ? Component.text("…").color(TextColor.color(0x9AA3B2)) : Component.text(text)).withTextHeight(0.034 * s));
                }
            }
            y += fieldH + gap;
        }
        if (fields > 0) y += 0.01 * s;
        widgets.add(ScreenWidget.fill("line2", 0, y, w, 0.004 * s, 0xFF343B47));
        y += 0.02 * s;
        double bw = (w - 2 * margin - (columns - 1) * gap) / columns;
        for (int i = 0; i < request.buttons().size(); i++) {
            int r = i / columns, c = i % columns;
            DialogButton button = request.buttons().get(i);
            widgets.add(ScreenWidget.button("b" + i, margin + c * (bw + gap), y + r * (bh + gap), bw, bh,
                    Component.text(plain(button.label(), player))).withTextHeight(0.034 * s));
        }
        double h = Math.min(maxH, y + buttonsH + 0.03 * s);
        return new ScreenLayout(w, h, widgets);
    }

    /** Word wrap by the default font's widths; keeps the text's own line breaks. */
    static List<String> wrap(String text, double width, double lineHeight) {
        double perPx = (1.0 / 40) * (lineHeight / 0.25 * 0.8);
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isBlank()) { out.add(""); continue; }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.strip().split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (ScreenRuntime.textWidth(candidate) * perPx > width && !current.isEmpty()) {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                } else current = new StringBuilder(candidate);
            }
            if (!current.isEmpty()) out.add(current.toString());
        }
        return out;
    }
}
