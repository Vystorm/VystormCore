package Vystorm.vystorm_core.worlds;

/** Result of an administrator recovery request. Additional outcomes may be added; handle a default case. @since 0.26.1 */
public enum RestoreOutcome {
    VISIT_ENDED("visit-ended"), RESTORED("restored"), DISCARDED("discarded"), EVICTED("evicted"),
    NOTHING("nothing"), PENDING("pending"), IN_PROGRESS("in-progress");

    private final String code;
    RestoreOutcome(String code) { this.code = code; }
    public String code() { return code; }
    /** Unknown legacy codes fail closed as pending. */
    public static RestoreOutcome fromCode(String code) {
        for (RestoreOutcome outcome : values()) if (outcome.code.equals(code)) return outcome;
        return PENDING;
    }
}
