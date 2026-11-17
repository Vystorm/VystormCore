package Vystorm.vystorm_core._uiMethods.admin;

import java.util.List;
import java.util.Objects;

public record AdminModule(String id, String title, String plugin, String command, String permission,
                          String nativeEntry, List<AdminAction> actions) {
    public AdminModule {
        if (!id.matches("[a-z0-9-]{1,40}") || !command.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("invalid module id or command");
        title = Objects.requireNonNull(title); plugin = Objects.requireNonNull(plugin); permission = Objects.requireNonNull(permission);
        nativeEntry = nativeEntry == null ? "" : nativeEntry;
        actions = List.copyOf(actions == null ? List.of() : actions);
        if (actions.size() > 64) throw new IllegalArgumentException("too many actions");
    }
}
