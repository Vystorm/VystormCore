package Vystorm.vystorm_core.compat.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface DialogInterface {
    void open(Plugin owner, Player player, DialogRequest request);
    void close(Player player);
    void removeAll(Plugin owner);
    void clear();


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

    /** Shared navigation buttons: home, back, category, settings ({@code _uiMethods.menu.CompatMenuButtons}). @since 0.64.0 */
    default Vystorm.vystorm_core._uiMethods.menu.CompatMenuButtonsInterface buttons() { return Vystorm.vystorm_core._uiMethods.menu.CompatMenuButtonsInterface.DEFAULT; }
}
