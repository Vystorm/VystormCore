package Vystorm.vystorm_core.actions;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The audit log of one plugin: one line per action in {@code plugins/<Plugin>/log/<yyyy-MM>.log} (tab-separated,
 * control characters escaped, so a reason or name can never start a new line), written in batches off the server
 * thread; months older than {@link #keepMonths} are moved to {@code log/old/} at start. @since 0.73.0
 */
public interface ActionLogInterface {
    /** The newest entries (up to 500 kept in memory, newest first). Any thread. */
    List<ActionLogEntry> recent(int limit);

    /**
     * A page of all months, newest first. Answered at once from memory when the newest entries cover the page (so a
     * console or RCON sender gets it in the same call); otherwise read off the server thread and {@code done} runs on
     * the server thread later.
     */
    void query(LogQuery query, Consumer<List<ActionLogEntry>> done);

    /** The folder with the monthly files. */
    Path folder();

    /** Months kept (default 12). */
    int keepMonths();

    /** Sets the months kept (1 to 120); applied at the next start. */
    void keepMonths(int months);

    /**
     * The permission that reading this log needs besides Core's admin right ({@code /vadmin actions}), checked with the
     * family rule; empty = none. The owning plugin sets its own log node here. @since 0.73.0
     */
    default String readNode() { return ""; }

    /** Sets {@link #readNode()}. @since 0.73.0 */
    default void readNode(String node) { }

    /** Writes the waiting lines now (blocking). */
    void flush();
}
