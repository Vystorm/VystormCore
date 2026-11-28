package Vystorm.vystorm_core.actions;

/**
 * A page of an audit log, newest first. Filters match case-insensitively as a part of the field; {@code null} or empty
 * matches everything. {@code text} matches actor, action, world, target and command.
 *
 * @param text     free filter, or null
 * @param page     1-based page
 * @param pageSize lines per page (1 to 100)
 * @since 0.73.0
 */
public record LogQuery(String text, int page, int pageSize) {
    public LogQuery {
        page = Math.max(1, page);
        pageSize = Math.clamp(pageSize, 1, 100);
    }

    /** Matches the entry. */
    public boolean matches(ActionLogEntry e) {
        if (text == null || text.isBlank()) return true;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        for (String field : new String[]{e.actorName(), e.action(), e.world(), e.target(), e.command()})
            if (field.toLowerCase(java.util.Locale.ROOT).contains(t)) return true;
        return false;
    }
}
