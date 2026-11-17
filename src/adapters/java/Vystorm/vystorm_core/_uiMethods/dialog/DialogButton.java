package Vystorm.vystorm_core._uiMethods.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;

/** A dialog action button; the optional tooltip is shown when hovering it. */
public record DialogButton(Component label, @Nullable Component tooltip, BiConsumer<Player, DialogResponseView> action) {
    public DialogButton {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(action, "action");
    }

    public DialogButton(Component label, BiConsumer<Player, DialogResponseView> action) {
        this(label, null, action);
    }
}
