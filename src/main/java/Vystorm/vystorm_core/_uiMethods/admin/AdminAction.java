package Vystorm.vystorm_core._uiMethods.admin;

import java.util.Objects;

/** A fixed command prefix; user text only supplies documented trailing arguments. */
public record AdminAction(String id, String label, String permission, String fixedArguments,
                          String argumentHint, String example, boolean mutating) {
    public AdminAction {
        if (!id.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("invalid action id");
        label = Objects.requireNonNull(label); permission = Objects.requireNonNull(permission);
        fixedArguments = fixedArguments == null ? "" : fixedArguments.trim();
        argumentHint = argumentHint == null ? "" : argumentHint; example = example == null ? "" : example;
    }
}
