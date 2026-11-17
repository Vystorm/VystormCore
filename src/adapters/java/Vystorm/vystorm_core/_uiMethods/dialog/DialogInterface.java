package Vystorm.vystorm_core._uiMethods.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface DialogInterface extends Vystorm.vystorm_core.compat.dialog.DialogInterface {
    @Override default void open(Plugin owner, Player player, Vystorm.vystorm_core.compat.dialog.DialogRequest request) {
        open(owner, player, Vystorm.vystorm_core.compat.internal.NativeDialogs.convert(request));
    }
    void open(Plugin owner, Player player, DialogRequest request);
    void close(Player player);
    void removeAll(Plugin owner);
    void clear();
    void handle(Player player, net.kyori.adventure.key.Key key, DialogResponseView response);

    default void prompt(Plugin owner, Player player, String title, String question, String initial,
                        Consumer<String> submit, Runnable cancel, Predicate<Player> valid) {
        Objects.requireNonNull(submit, "submit");
        var lang = Vystorm.vystorm_core.lang.Localization.get();
        DialogInput input = DialogInput.text("value", lang.component(player, question))
                .initial(initial == null ? "" : initial).maxLength(256).build();
        open(owner, player, new DialogRequest(lang.component(player, title), List.of(), List.of(input),
                List.of(new DialogButton(lang.core().component(player, "dialog.apply"), (p, response) -> submit.accept(response.getText("value")))),
                valid, cancel));
    }

    default void confirm(Plugin owner, Player player, String title, String message,
                         Runnable submit, Runnable cancel, Predicate<Player> valid) {
        Objects.requireNonNull(submit, "submit");
        var lang = Vystorm.vystorm_core.lang.Localization.get();
        open(owner, player, new DialogRequest(lang.component(player, title),
                List.of(DialogBody.plainMessage(lang.component(player, message))), List.of(),
                List.of(new DialogButton(lang.core().component(player, "dialog.confirm"), (p, response) -> submit.run())), valid, cancel));
    }
}
