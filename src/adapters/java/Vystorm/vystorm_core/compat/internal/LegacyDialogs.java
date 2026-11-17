package Vystorm.vystorm_core.compat.internal;

import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;
import Vystorm.vystorm_core._uiMethods.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.ChatDialogHandler;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Retains the old public interface while delegating Core forms to the baseline implementation. */
public final class LegacyDialogs implements DialogInterface {
    public final ChatDialogHandler chat = new ChatDialogHandler();
    public void open(Plugin owner, Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request) { chat.open(owner, player, request); }
    public void open(Plugin owner, Player player, DialogRequest request) { throw new UnsupportedOperationException("Native Paper dialog requests require a dialog-capable server; use compat.dialog"); }
    public void close(Player player) { chat.close(player); }
    public void removeAll(Plugin owner) { chat.removeAll(owner); }
    public void clear() { chat.clear(); }
    public void handle(Player player, net.kyori.adventure.key.Key key, io.papermc.paper.dialog.DialogResponseView response) { }
    @Override public void prompt(Plugin owner, Player player, String title, String question, String initial,
                                 Consumer<String> submit, Runnable cancel, Predicate<Player> valid) {
        chat.prompt(owner, player, title, question, initial, submit, cancel, valid);
    }
    @Override public void confirm(Plugin owner, Player player, String title, String message,
                                  Runnable submit, Runnable cancel, Predicate<Player> valid) {
        chat.confirm(owner, player, title, message, submit, cancel, valid);
    }
}
