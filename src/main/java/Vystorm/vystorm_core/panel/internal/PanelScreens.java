package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_CHANGE;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_SEARCH;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_SUBMIT;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_TAB;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.Screens;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Panels as Vystorm Screens (since 0.57.0, own layout since 0.58.0): the panel document is drawn by
 * {@link PanelScreenLayout} with real sections, tables, lists, tabs, bars and inputs. Every action goes through
 * {@link PanelDialogs#action}, so rate limits, permissions, checks and handlers are exactly those of the dialog and the
 * client mod. Text inputs open a short typing dialog and come back to the screen. Server thread only. Core-internal.
 */
final class PanelScreens {
    private final PanelHub hub;
    private Plugin core;

    PanelScreens(PanelHub hub) { this.hub = hub; }

    void configure(Plugin core) { this.core = core; }

    /** Mutable state of one open panel screen. */
    private static final class State {
        final PanelHub.Session session;
        final PanelScreenLayout.View view = new PanelScreenLayout.View();
        PanelScreenLayout.Result result;
        List<String> status = List.of();
        ScreenSession screen;
        State(PanelHub.Session session) { this.session = session; }
    }

    /** false = no screen (render failure, refused); the caller takes the next route. */
    boolean open(Player player, PanelRegistry.Registered reg, String arg) {
        if (core == null) return false;
        State state = new State(new PanelHub.Session(0, player, reg, arg));
        PanelOpener.Seed seed = PanelOpener.seed(state.session);   // 0.71.0: values and status carried back by PickReturn
        if (seed != null) state.status = seed.status;
        if (!render(state)) return false;
        state.result = layout(state);
        ScreenSpec spec = ScreenSpec.builder("panel:" + reg.id(), state.result.layout())
                .backdrop(ScreenSpec.Backdrop.BLACK)
                .onInput((screen, input) -> { state.screen = screen; handle(state, input); })
                .build();
        String key = "panel:" + reg.id() + ":" + (arg == null ? "" : arg);
        boolean opened = Vystorm.vystorm_core.screen.internal.ScreenRuntime.get().open(core, player, spec,
                new Vystorm.vystorm_core.screen.internal.ScreenRuntime.Origin(key, p -> PanelOpener.open(p, reg, arg), true)).opened();
        if (opened && seed != null) seed.statusShown = true;
        return opened;
    }

    /** After a confirmation (its screen replaced the panel): show the panel again. */
    private void reopen(State state) {
        PanelHub.Session s = state.session;
        if (state.screen != null && state.screen.isOpen()) return;
        if (hub.dialogs().takeHandoff(s.player.getUniqueId()) || !s.player.isOnline()) return;
        PanelOpener.open(s.player, s.reg, s.arg);
    }

    private boolean render(State state) {
        try {
            hub.render(state.session);
            return true;
        } catch (RuntimeException failure) {
            hub.renderFailed(state.session.reg, failure);
            return false;
        }
    }

    private PanelScreenLayout.Result layout(State state) {
        Player player = state.session.player;
        ScreenProfile profile = Screens.of(core).profile(player);
        var lang = Localization.get().core();
        PanelScreenLayout.Metrics metrics = PanelScreenLayout.metrics(state.session.model.root(), profile.viewWidth(), profile.viewHeight(), profile.scale());
        PanelScreenLayout.Texts texts = new PanelScreenLayout.Texts() {
            @Override public Component close() { return lang.component(player, "menu.screen.close"); }
            @Override public Component page(int page, int pages) { return lang.component(player, "panel.screen.page", "page", page, "pages", pages); }
            @Override public Component scrollHint() { return lang.component(player, "panel.screen.scroll"); }
            @Override public Component on() { return lang.component(player, "features.state-on"); }
            @Override public Component off() { return lang.component(player, "features.state-off"); }
            @Override public Component rows(int from, int to, int total) { return lang.component(player, "screen.list.position", "from", from, "to", to, "total", total); }
            @Override public Component listHelp(String kind) { return lang.component(player, "screen.list.help-" + kind); }
            @Override public Object listIcon(String entryKey, org.bukkit.Material material) {
                PanelOpener.ListIcons icons = PanelOpener.listIcons;
                if (icons == null || entryKey == null) return material;
                try {
                    Object icon = icons.icon(player, state.session.reg.id(), entryKey);
                    return icon == null ? material : icon;
                } catch (RuntimeException | LinkageError failed) {
                    return material;
                }
            }
        };
        return PanelScreenLayout.layout(state.session.model, state.view, metrics, texts, state.status, PanelScreenLayout.listIds(state.session.doc));
    }

    private void handle(State state, ScreenInput input) {
        switch (input) {
            case ScreenInput.Click click -> {
                if (click.widgetId() == null || state.result == null) return;
                PanelScreenLayout.Act act = state.result.acts().get(click.widgetId());
                if (act != null) run(state, act, click.right());
            }
            case ScreenInput.Hover hover -> {
                if (state.result == null || state.screen == null) return;
                Component hint = hover.widgetId() == null ? null : state.result.hints().get(hover.widgetId());
                state.screen.setText(PanelScreenLayout.HINT, hint != null ? hint : state.result.hint());
            }
            case ScreenInput.Scroll scroll -> {
                if (state.result == null || state.result.maxScroll() <= 0) return;
                double before = state.view.scroll;
                state.view.scroll = Math.max(0, Math.min(state.result.maxScroll(), before + scroll.delta() * 0.12));
                if (state.view.scroll != before) relayout(state);
            }
            case ScreenInput.Closed closed -> {
                PanelOpener.ClosedListener listener = PanelOpener.closedListener;
                if (listener != null) {
                    try { listener.closed(state.session.player, state.session.reg.id(), state.session.arg, closed.reason()); }
                    catch (RuntimeException failure) { hub.logger().log(java.util.logging.Level.WARNING, "[Panels] Close listener failed", failure); }
                }
            }
            default -> { }
        }
    }

    private void run(State state, PanelScreenLayout.Act act, boolean right) {
        PanelHub.Session s = state.session;
        switch (act) {
            case PanelScreenLayout.Act.Close c -> state.screen.close();
            case PanelScreenLayout.Act.Action a -> {
                String value = right && a.alt() != null ? a.alt() : a.value();
                if (a.confirm() != null) {
                    Vystorm.vystorm_core.main.getApi().dialogs().open(core, s.player, DialogDowngrade.confirmation(a.confirm(), s.player,
                            () -> { send(state, a.elementId(), a.kind(), value); reopen(state); }, () -> reopen(state)));
                    return;
                }
                send(state, a.elementId(), a.kind(), value);
            }
            case PanelScreenLayout.Act.Navigate n -> {
                state.screen.close();
                PanelRegistry.find(n.panel()).ifPresent(reg -> PanelOpener.open(s.player, reg, n.arg()));
            }
            case PanelScreenLayout.Act.Web w -> {
                state.screen.close();
                String module = w.module() == null || w.module().isEmpty() ? s.reg.spec().webModule() : w.module();
                if (module != null) Vystorm.vystorm_core.web.WebServices.openOrLink(s.player, module, w.page() == null ? "" : w.page(), null);
            }
            case PanelScreenLayout.Act.Tab t -> {
                state.view.tabs.put(t.tabsId(), t.tabId());
                state.view.scroll = 0;
                if (t.lazy()) send(state, t.tabsId(), KIND_TAB, new JsonPrimitive(t.tabId()).toString());
                else refresh(state, List.of());
            }
            case PanelScreenLayout.Act.FormSet f -> {
                state.view.form.put(f.elementId(), right && f.alt() != null ? f.alt() : f.value());
                relayout(state);
            }
            case PanelScreenLayout.Act.Submit f -> {
                Runnable submit = () -> {
                    JsonObject values = f.values();
                    send(state, f.formId(), KIND_SUBMIT, values.toString());
                    // sent: forget the edits so the server's new values show
                    values.keySet().forEach(state.view.form::remove);
                };
                if (f.confirm() != null) {
                    Vystorm.vystorm_core.main.getApi().dialogs().open(core, s.player, DialogDowngrade.confirmation(f.confirm(), s.player, () -> { submit.run(); reopen(state); }, () -> reopen(state)));
                } else {
                    submit.run();
                }
            }
            case PanelScreenLayout.Act.Edit e -> edit(state, e);
            case PanelScreenLayout.Act.Toggle t -> {
                if (!state.view.collapsed.remove(t.sectionKey())) state.view.collapsed.add(t.sectionKey());
                if (!state.view.expanded.remove(t.sectionKey())) state.view.expanded.add(t.sectionKey());
                relayout(state);
            }
        }
    }

    /** Typing happens in a Paper dialog; the screen stays open behind it and shows the new value afterwards. */
    private void edit(State state, PanelScreenLayout.Act.Edit e) {
        Player player = state.session.player;
        var lang = Localization.get().core();
        Component label = e.label() == null || e.label().isEmpty() ? Component.empty() : DialogDowngrade.component(e.label());
        DialogInput field = DialogInput.text("value", label).initial(e.value()).maxLength(Math.max(1, Math.min(256, e.maxLength()))).width(300).build();
        DialogButton apply = new DialogButton(lang.component(player, "panel.dialog.apply"), (p, response) -> {
            String text = response.getText("value");
            if (text == null) return;
            if (e.formId() != null) {
                state.view.form.put(e.elementId(), new JsonPrimitive(text));
                relayout(state);
            } else if (e.number()) {
                // number field shown as a list row: Core's check still tests range and step against what it sent
                send(state, e.elementId(), KIND_CHANGE, numberJson(text));
            } else {
                send(state, e.elementId(), e.search() ? KIND_SEARCH : KIND_CHANGE, new JsonPrimitive(text).toString());
            }
        });
        Component title = DialogDowngrade.component(state.session.model.root().title());
        typingDialogs(Vystorm.vystorm_core.main.getApi().dialogs()).open(core, player, new DialogRequest(title, List.of(), List.of(field), List.of(apply),
                Player::isOnline, () -> { }, lang.plain(player, "panel.dialog.close"), 2));
    }

    /** Typed text as a JSON number ({@code 2,5} counts as {@code 2.5}); anything else stays a string and is refused by the check. */
    static String numberJson(String text) {
        String t = text == null ? "" : text.strip().replace(',', '.');
        try {
            double v = Double.parseDouble(t);
            if (Double.isFinite(v)) return new JsonPrimitive(v == Math.rint(v) && Math.abs(v) < 1e15 ? (Number) (long) v : (Number) v).toString();
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        return new JsonPrimitive(t).toString();
    }

    private void send(State state, String elementId, int kind, String value) {
        List<String> status = new ArrayList<>();
        PanelDialogs.addStatus(state.session, status, hub.dialogs().action(state.session, elementId, kind, value));
        refresh(state, status);
        // 0.71.0: the handler asked for typing into a field of the redrawn screen (Core's picker: Search tab = one prompt)
        String typeNext = PanelOpener.takeTypeNext(state.session.player);
        if (typeNext != null && state.screen != null && state.screen.isOpen() && state.result != null) {
            PanelScreenLayout.Act.Edit e = editFor(state.result.acts(), typeNext);
            if (e != null) edit(state, e);
        }
    }

    /** The typing act of the input {@code elementId} on the drawn screen, or null. */
    static PanelScreenLayout.Act.Edit editFor(Map<String, PanelScreenLayout.Act> acts, String elementId) {
        if (acts == null || elementId == null) return null;
        for (PanelScreenLayout.Act act : acts.values())
            if (act instanceof PanelScreenLayout.Act.Edit e && elementId.equals(e.elementId())) return e;
        return null;
    }

    /**
     * The handler that shows the typing box: never the screen wrapper. Since 0.61.0 dialogs with inputs open as a
     * screen; a screen replaces the panel's screen, so the panel closed under the player (Core's player picker took
     * that close for "another menu" and ended) and typing took two forms. The native dialog (Paper) or chat form opens
     * over the panel's screen, which stays open and shows the result.
     */
    static Vystorm.vystorm_core.compat.dialog.DialogInterface typingDialogs(Vystorm.vystorm_core.compat.dialog.DialogInterface api) {
        return api instanceof Vystorm.vystorm_core.compat.internal.ScreenDialogs screens ? screens.delegate() : api;
    }

    /** Shows the panel again after an action; when the action handed over (closeAndThen), the screen closes. */
    private void refresh(State state, List<String> status) {
        if (hub.dialogs().takeHandoff(state.session.player.getUniqueId())) {
            if (state.screen != null && state.screen.isOpen()) state.screen.close();
            return;
        }
        if (state.screen == null || !state.screen.isOpen()) return;
        if (!render(state)) { state.screen.close(); return; }
        // selections the server now sends itself replace the player's local choice
        state.view.chosen.clear();
        state.status = status;
        relayout(state);
    }

    private void relayout(State state) {
        if (state.screen == null || !state.screen.isOpen()) return;
        state.result = layout(state);
        state.screen.setLayout(state.result.layout());
    }

}
