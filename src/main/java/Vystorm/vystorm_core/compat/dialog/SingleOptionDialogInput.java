package Vystorm.vystorm_core.compat.dialog;

import net.kyori.adventure.text.Component;

public final class SingleOptionDialogInput {
    private SingleOptionDialogInput() {}
    public record OptionEntry(String id, Component display, boolean initial) {
        public static OptionEntry create(String id, Component display, boolean initial) {
            return new OptionEntry(id, display, initial);
        }
    }
}
