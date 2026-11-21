package Vystorm.vystorm_core.compat.dialog;

import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Server-thread chat forms for servers without the native dialog protocol. */
public final class ChatDialogHandler implements DialogInterface, Listener {
    private final Map<UUID, Session> sessions = new HashMap<>();
    private static final String COMMAND = "/vcoreform ";
    private static final class Session implements DialogResponseView {
        final Plugin owner; final Player player; final DialogRequest request;
        final String token = UUID.randomUUID().toString();
        final Map<String, Object> values = new HashMap<>();
        int input; BukkitTask expiry;
        Session(Plugin owner, Player player, DialogRequest request) { this.owner = owner; this.player = player; this.request = request; }
        public String getText(String key) { return (String) values.get(key); }
        public Boolean getBoolean(String key) { return (Boolean) values.get(key); }
        public Float getFloat(String key) { return (Float) values.get(key); }
    }

    @Override public void open(Plugin owner, Player player, DialogRequest request) {
        requireThread(); close(player);
        Session s = new Session(owner, player, request);
        if (!owner.isEnabled() || !player.isOnline() || !valid(s)) return;
        sessions.put(player.getUniqueId(), s);
        s.expiry = Bukkit.getScheduler().runTaskLater(Vystorm.vystorm_core.main.getPlugin(), () -> {
            if (sessions.remove(player.getUniqueId(), s) && owner.isEnabled()) callback(s, request.cancel());
        }, 1200);
        player.closeInventory();
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, Localization.get().render(player, request.title()));
        for (DialogBody body : request.body()) Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, body.item() == null
                ? Localization.get().render(player, body.contents())
                : Component.translatable(Vystorm.vystorm_core.compat.PlatformCompat.translationKey(body.item().getType())));
        show(s);
    }

    private void show(Session s) {
        var lang = Localization.get();
        if (s.input < s.request.inputs().size()) {
            DialogInput in = s.request.inputs().get(s.input);
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(s.player, lang.render(s.player, in.label()));
            if (in.kind() == DialogInput.Kind.OPTIONS) for (var option : in.entries()) {
                Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(s.player, lang.render(s.player, option.display()).clickEvent(ClickEvent.runCommand(COMMAND + s.token + " " + option.id())));
            }
            String initial = in.initial() == null ? "" : in.initial().toString();
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(s.player, lang.core().component(s.player, "dialog.legacy.input")
                    .clickEvent(ClickEvent.suggestCommand(COMMAND + s.token + " " + initial)));
            if (in.kind() == DialogInput.Kind.BOOL) {
                Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(s.player, lang.core().component(s.player, "dialog.legacy.yes")
                        .clickEvent(ClickEvent.runCommand(COMMAND + s.token + " true")));
                Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(s.player, lang.core().component(s.player, "dialog.legacy.no")
                        .clickEvent(ClickEvent.runCommand(COMMAND + s.token + " false")));
            }
        } else {
            for (int i = 0; i < s.request.buttons().size(); i++) {
                DialogButton button = s.request.buttons().get(i);
                Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(s.player, lang.render(s.player, button.label())
                        .clickEvent(ClickEvent.runCommand(COMMAND + s.token + " " + i)));
            }
        }
        Component cancel = DialogRequest.DEFAULT_CANCEL.equals(s.request.cancelLabel())
                ? lang.core().component(s.player, "dialog.cancel")
                : "Schließen".equals(s.request.cancelLabel()) ? lang.core().component(s.player, "dialog.close")
                : lang.component(s.player, s.request.cancelLabel());
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(s.player, cancel
                .clickEvent(ClickEvent.runCommand(COMMAND + s.token + " /cancel")));
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.HIGHEST)
    public void command(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().startsWith(COMMAND)) return;
        event.setCancelled(true);
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        String[] parts = event.getMessage().substring(COMMAND.length()).split(" ", 2);
        if (parts.length != 2 || !parts[0].equals(s.token)) return;
        if (!s.owner.isEnabled() || !s.player.isOnline() || !valid(s)) { close(s.player); return; }
        String value = parts[1];
        if (value.equals("/cancel")) { close(s.player); callback(s, s.request.cancel()); return; }
        if (s.input < s.request.inputs().size()) {
            DialogInput in = s.request.inputs().get(s.input);
            try { s.values.put(in.key(), parse(in, value)); }
            catch (IllegalArgumentException invalid) {
                Localization.get().core().send(s.player, "dialog.legacy.invalid"); show(s); return;
            }
            s.input++; show(s); return;
        }
        try {
            int index = Integer.parseInt(value);
            if (index < 0 || index >= s.request.buttons().size()) return;
            close(s.player);
            callback(s, () -> s.request.buttons().get(index).action().accept(s.player, s));
        } catch (NumberFormatException ignored) { }
    }

    static Object parse(DialogInput in, String value) {
        return switch (in.kind()) {
            case TEXT -> { if (value.length() > in.maxLength()) throw new IllegalArgumentException(); yield value; }
            case BOOL -> { if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException(); yield Boolean.valueOf(value); }
            case OPTIONS -> { if (in.entries().stream().noneMatch(e -> e.id().equals(value))) throw new IllegalArgumentException(); yield value; }
            case RANGE -> {
                float number = Float.parseFloat(value);
                if (!Float.isFinite(number) || number < Math.min(in.start(), in.end()) || number > Math.max(in.start(), in.end())) throw new IllegalArgumentException();
                yield number;
            }
        };
    }

    @EventHandler public void quit(PlayerQuitEvent event) { close(event.getPlayer()); }
    @Override public void close(Player player) {
        requireThread(); Session old = sessions.remove(player.getUniqueId());
        if (old != null && old.expiry != null) old.expiry.cancel();
    }
    @Override public void removeAll(Plugin owner) {
        requireThread();
        for (Session s : List.copyOf(sessions.values())) if (s.owner == owner) close(s.player);
    }
    @Override public void clear() { requireThread(); for (Session s : List.copyOf(sessions.values())) close(s.player); }
    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Dialogs require the server thread"); }
    private static void callback(Session s, Runnable action) {
        try { Vystorm.vystorm_core.screen.internal.ScreenDispatch.run(action); }   // 0.66.1: plugin code, not player input
        catch (RuntimeException failure) { ProviderFailures.report("Dialog", s.owner.getName(), "callback", failure); }
    }
    private static boolean valid(Session s) {
        try { return s.request.valid().test(s.player); }
        catch (RuntimeException failure) {
            ProviderFailures.report("Dialog", s.owner.getName(), "validity", failure);
            return false;
        }
    }
}
