package Vystorm.vystorm_core._uiMethods.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.*;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import Vystorm.vystorm_core.lang.Localization;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import java.security.SecureRandom;
import java.util.*;

/** Server-thread session registry for Paper's native dialog protocol. */
public final class DefaultDialogHandler implements DialogInterface {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long EXPIRY_TICKS = 20L * 60L;
    private static final long EXPIRY_NANOS = 60_000_000_000L;
    /**
     * Nach einem Klick schließt der Client den Dialog selbst. {@code WAIT_FOR_RESPONSE} ließ Vanilla 26.2 auf
     * „Warte auf Server“ stehen: {@code closeDialog()} schließt diesen Bildschirm nicht (Review I5). Folgedialoge und
     * Menüs öffnet der Server danach wie gewohnt.
     */
    static final DialogBase.DialogAfterAction AFTER_ACTION = DialogBase.DialogAfterAction.CLOSE;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Object> closing = new HashMap<>();

    private static final class Session {
        final Plugin owner; final UUID player; final String token; final DialogRequest request;
        final long openedAt = System.nanoTime();
        BukkitTask showTask, expiry, dispatchTask;
        boolean consumed;
        Session(Plugin owner, UUID player, String token, DialogRequest request) {
            this.owner = owner; this.player = player; this.token = token; this.request = request;
        }
    }

    @Override public void open(Plugin owner, Player player, DialogRequest request) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(player, "player"); Objects.requireNonNull(request, "request");
        requireThread();
        reserve(owner, player, request);
    }

    private void reserve(Plugin owner, Player player, DialogRequest request) {
        requireThread();
        if (!owner.isEnabled() || !player.isOnline()) return;
        try { validateRequest(request); } catch (RuntimeException ex) { failure("Invalid dialog request", ex); throw ex; }
        if (!safeValid(request, player)) return;
        Session previous = sessions.remove(player.getUniqueId());
        cancelTasks(previous);
        Session session = new Session(owner, player.getUniqueId(), token(), request);
        closing.remove(session.player);
        sessions.put(session.player, session);
        session.expiry = Bukkit.getScheduler().runTaskLater(Vystorm.vystorm_core.main.getPlugin(), () -> timeout(session), EXPIRY_TICKS);
        session.showTask = Bukkit.getScheduler().runTask(Vystorm.vystorm_core.main.getPlugin(), () -> display(session, player));
    }

    private void display(Session session, Player player) {
        if (sessions.get(session.player) != session) return;
        if (!session.owner.isEnabled() || !player.isOnline() || !safeValid(session.request, player)) { expire(session); return; }
        try {
            player.closeInventory();
            if (sessions.get(session.player) != session) return;
            if (!safeValid(session.request, player)) { expire(session); return; }
            DialogRequest request = session.request;
            List<ActionButton> actions = new ArrayList<>();
            Localization lang = Localization.get();
            for (int i = 0; i < request.buttons().size(); i++) {
                DialogButton b = request.buttons().get(i);
                actions.add(button(lang.render(player, b.label()), lang.render(player, b.tooltip()), key(session, i)));
            }
            ActionButton exit = button(cancelLabel(player, request.cancelLabel()), null, key(session, -1));
            Component title = lang.render(player, request.title());
            List<DialogBody> body = request.body().stream().map(b -> localize(player, b)).toList();
            Dialog dialog = Dialog.create(factory -> factory.empty().base(DialogBase.create(title, null, true, false,
                    AFTER_ACTION, body, request.inputs()))
                    // Paper rejects a multi-action dialog without actions: a request with only the exit button becomes a notice (0.57.0).
                    .type(actions.isEmpty() ? DialogType.notice(exit) : DialogType.multiAction(actions, exit, Math.min(request.columns(), Math.max(1, actions.size())))));
            if (sessions.get(session.player) == session && session.owner.isEnabled() && player.isOnline()) player.showDialog(dialog);
        } catch (RuntimeException ex) { expire(session); failure("Dialog display failed", ex); }
    }

    /** Core's standard exit labels follow the viewer's language; {@code Lang.ref} labels are resolved. */
    static Component cancelLabel(Player player, String label) {
        Localization lang = Localization.get();
        if (DialogRequest.DEFAULT_CANCEL.equals(label)) return lang.core().component(player, "dialog.cancel");
        if ("Schließen".equals(label)) return lang.core().component(player, "dialog.close");
        return lang.component(player, label);
    }

    private static DialogBody localize(Player player, DialogBody body) {
        if (body instanceof PlainMessageDialogBody plain) {
            Component rendered = Localization.get().render(player, plain.contents());
            return rendered == plain.contents() ? body : DialogBody.plainMessage(rendered, plain.width());
        }
        return body;
    }

    private ActionButton button(Component label, Component tooltip, Key key) {
        return ActionButton.create(label, tooltip, 150, DialogAction.customClick(key, null));
    }

    @Override public void close(Player player) {
        requireThread(); Objects.requireNonNull(player, "player");
        Session old = sessions.remove(player.getUniqueId());
        cancelTasks(old);
        deferClose(player, old);
    }

    @Override public void removeAll(Plugin owner) { requireThread(); remove(Objects.requireNonNull(owner)); }
    @Override public void clear() { requireThread(); remove(null); }

    private void remove(Plugin owner) {
        for (Session session : List.copyOf(sessions.values())) {
            if (owner == null || session.owner == owner) {
                sessions.remove(session.player, session);
                cancelTasks(session);
                Player player = Bukkit.getPlayer(session.player);
                if (player != null) deferClose(player, session);
            }
        }
    }

    @Override public void handle(Player player, Key key, DialogResponseView response) {
        requireThread(); Objects.requireNonNull(player, "player"); Objects.requireNonNull(key, "key");
        String[] path = key.value().split("/");
        if (!key.namespace().equals("vystorm_core") || path.length != 3 || !path[0].equals("dialog")) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.consumed || !session.token.equals(path[1])) return;
        if (!session.owner.isEnabled() || !player.isOnline() || System.nanoTime() - session.openedAt >= EXPIRY_NANOS) { expire(session); return; }
        final int index;
        try { index = Integer.parseInt(path[2]); } catch (NumberFormatException ignored) { return; }
        if (index < -1 || index >= session.request.buttons().size()) return;
        if (index >= 0) {
            try {
                if (!safeValid(session.request, player) || (response == null && !session.request.inputs().isEmpty())
                        || !validResponse(session.request.inputs(), response)) { expire(session); return; }
            } catch (RuntimeException ex) { expire(session); failure("Invalid dialog response", ex); return; }
        }
        session.consumed = true;
        cancelTasks(session);
        // Keep the consumed session registered until dispatch so close/disable/override can revoke it.
        session.dispatchTask = Bukkit.getScheduler().runTask(Vystorm.vystorm_core.main.getPlugin(), () -> {
            if (sessions.get(session.player) != session) return;
            if (!session.owner.isEnabled() || !player.isOnline() || !safeValid(session.request, player)
                    || System.nanoTime() - session.openedAt >= EXPIRY_NANOS) { expire(session); return; }
            sessions.remove(session.player, session);
            player.closeDialog();
            try {
                // 0.66.1: plugin code, not player input: world events it fires pass the screen guard (ScreenDispatch)
                if (index < 0) Vystorm.vystorm_core.screen.internal.ScreenDispatch.run(session.request.cancel());
                else Vystorm.vystorm_core.screen.internal.ScreenDispatch.run(() -> session.request.buttons().get(index).action().accept(player, response));
            } catch (RuntimeException ex) { failure("Dialog callback failed", ex); }
        });
    }

    /**
     * Ablauf ohne Auswahl: Dialog schließen und {@code cancel} des Anfragenden ausführen (wie beim Exit-Button bzw. ESC),
     * damit Konsumenten reservierte Ressourcen freigeben können. Nicht bei deaktiviertem Owner.
     */
    private void timeout(Session session) {
        if (sessions.get(session.player) != session || session.consumed) return;
        session.consumed = true;
        expire(session);
        if (!session.owner.isEnabled()) return;
        try { session.request.cancel().run(); } catch (RuntimeException ex) { failure("Dialog cancel callback failed", ex); }
    }

    private void expire(Session session) {
        requireThread();
        if (!sessions.remove(session.player, session)) return;
        cancelTasks(session);
        Player player = Bukkit.getPlayer(session.player);
        if (player != null) deferClose(player, session);
    }

    static boolean validResponse(List<DialogInput> inputs, DialogResponseView response) {
        for (DialogInput input : inputs) {
            if (input instanceof TextDialogInput text) {
                String value = response.getText(text.key());
                if (value == null || value.length() > text.maxLength()) return false;
            } else if (input instanceof SingleOptionDialogInput option) {
                String value = response.getText(option.key());
                if (value == null || option.entries().stream().noneMatch(entry -> entry.id().equals(value))) return false;
            } else if (input instanceof BooleanDialogInput bool && response.getBoolean(bool.key()) == null) return false;
            else if (input instanceof NumberRangeDialogInput number) {
                Float value = response.getFloat(number.key());
                if (value == null || !Float.isFinite(value) || value < Math.min(number.start(), number.end()) || value > Math.max(number.start(), number.end())) return false;
            }
        }
        return true;
    }

    private static void validateRequest(DialogRequest request) {
        Set<String> keys = new HashSet<>();
        for (DialogInput input : request.inputs()) if (!keys.add(input.key())) throw new IllegalArgumentException("Dialog input keys must be unique");
    }
    private static Key key(Session session, int index) { return Key.key("vystorm_core", "dialog/" + session.token + "/" + index); }
    private static String token() { byte[] bytes = new byte[18]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase(Locale.ROOT); }
    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Dialog operations require the server thread"); }
    private void deferClose(Player player, Session old) {
        if (old == null) return;
        Object token = new Object();
        closing.put(old.player, token);
        Runnable action = () -> {
            if (!closing.remove(old.player, token) || sessions.containsKey(old.player) || !player.isOnline()) return;
            player.closeDialog();
        };
        if (!Vystorm.vystorm_core.main.getPlugin().isEnabled()) action.run();
        else Bukkit.getScheduler().runTask(Vystorm.vystorm_core.main.getPlugin(), action);
    }
    private static void cancelTasks(Session session) {
        if (session == null) return;
        if (session.showTask != null) session.showTask.cancel();
        if (session.expiry != null) session.expiry.cancel();
        if (session.dispatchTask != null) session.dispatchTask.cancel();
    }
    private static boolean safeValid(DialogRequest request, Player player) {
        try { return request.valid().test(player); } catch (RuntimeException ex) { failure("Dialog validity predicate failed", ex); return false; }
    }
    private static void failure(String message, RuntimeException ex) { DebugMessages.high("[Dialog] " + message + ": " + ex); }
}
