package Vystorm.vystorm_core.actions;

/**
 * How an action ended.
 *
 * @param code    {@link #OK}, {@link #FAILED} (the action itself said no), {@link #NO_PERMISSION}, {@link #NEEDS_CONFIRM},
 *                {@link #ERROR} (an exception, logged) or {@link #DISABLED} (the owning plugin is not enabled)
 * @param message plain text for the status line, may be empty
 * @param before  value before as text (overrides the preview's when not empty)
 * @param after   value after as text (overrides the preview's when not empty)
 * @since 0.73.0
 */
public record ActionResult(String code, String message, String before, String after) {
    public static final String OK = "ok", FAILED = "failed", NO_PERMISSION = "no-permission", NEEDS_CONFIRM = "needs-confirm",
            ERROR = "error", DISABLED = "disabled";

    public ActionResult {
        code = code == null ? ERROR : code;
        message = message == null ? "" : message;
        before = before == null ? "" : before;
        after = after == null ? "" : after;
    }

    public static ActionResult ok(String message) { return new ActionResult(OK, message, "", ""); }

    public static ActionResult ok(String message, Object before, Object after) {
        return new ActionResult(OK, message, before == null ? "" : before.toString(), after == null ? "" : after.toString());
    }

    public static ActionResult failed(String message) { return new ActionResult(FAILED, message, "", ""); }

    public boolean isOk() { return OK.equals(code); }
}
