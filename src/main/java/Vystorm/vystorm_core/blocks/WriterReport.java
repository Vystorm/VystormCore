package Vystorm.vystorm_core.blocks;

import java.util.List;
import java.util.Objects;

/**
 * Which block writers this Core has and which one {@code api.blocks().writer(world)} hands out, for {@code /vadmin}
 * and plugin diagnose commands.
 *
 * @param selected id of the writer {@code writer(world)} returns ({@code "bukkit"} in 0.69.0)
 * @param available ids of all writers this Core has
 * @param reason plain English sentence why {@code selected} is used
 * @since 0.69.0
 */
public record WriterReport(String selected, List<String> available, String reason) {
    public WriterReport {
        Objects.requireNonNull(selected, "selected");
        available = List.copyOf(available);
        reason = reason == null ? "" : reason;
    }
}
