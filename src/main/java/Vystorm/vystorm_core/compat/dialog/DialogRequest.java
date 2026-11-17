package Vystorm.vystorm_core.compat.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A portable dialog, rendered natively or as a chat form. {@code cancelLabel} names the always-present exit button (which runs {@code cancel});
 * {@code cancel} also runs when the player closes a native dialog with ESC (the client sends the exit action) and when
 * the dialog times out after 60 seconds without a choice. It does not run when the dialog is replaced by another
 * dialog, closed via {@code DialogInterface.close}, or when the owning plugin is disabled;
 * {@code columns} is the maximum number of action buttons per row.
 */
public record DialogRequest(Component title, List<DialogBody> body, List<DialogInput> inputs,
                            List<DialogButton> buttons, Predicate<Player> valid, Runnable cancel,
                            String cancelLabel, int columns) {
    public static final String DEFAULT_CANCEL = "Abbrechen";

    public DialogRequest {
        Objects.requireNonNull(title, "title");
        body = List.copyOf(Objects.requireNonNull(body, "body"));
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        Objects.requireNonNull(valid, "valid");
        Objects.requireNonNull(cancel, "cancel");
        cancelLabel = cancelLabel == null || cancelLabel.isBlank() ? DEFAULT_CANCEL : cancelLabel;
        if (columns < 1 || columns > 4) throw new IllegalArgumentException("columns must be 1..4");
    }

    public DialogRequest(Component title, List<DialogBody> body, List<DialogInput> inputs,
                         List<DialogButton> buttons, Predicate<Player> valid, Runnable cancel) {
        this(title, body, inputs, buttons, valid, cancel, DEFAULT_CANCEL, 3);
    }
}
