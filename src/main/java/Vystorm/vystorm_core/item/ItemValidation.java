package Vystorm.vystorm_core.item;

import java.util.List;

/** Ergebnis einer serverseitigen Item-Prüfung. */
public record ItemValidation(Status status, List<String> messages, boolean restricted) {
    public enum Status { VALID, NOT_MANAGED, UNKNOWN_TEMPLATE, OUTDATED, TAMPERED, DUPLICATE, QUARANTINED }

    /** Original constructor retained for consumers compiled against Core 0.26.0. */
    public ItemValidation(Status status, List<String> messages) { this(status, messages, false); }

    public ItemValidation {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (restricted && status != Status.QUARANTINED) throw new IllegalArgumentException("restricted items must be quarantined");
    }

    public boolean ok() { return status == Status.VALID || status == Status.NOT_MANAGED || status == Status.OUTDATED; }

    public static ItemValidation valid() { return new ItemValidation(Status.VALID, List.of()); }

    public static ItemValidation notManaged() { return new ItemValidation(Status.NOT_MANAGED, List.of()); }

    /**
     * Von Core gesperrtes Item ({@link ItemRestrictions}): Status {@link Status#QUARANTINED}, Meldung
     * {@code restricted:<grund>}. Bestehende Prüfungen auf {@code ok()}/{@code QUARANTINED} lehnen es damit ab.
     *
     * @since 0.26.0
     */
    public static ItemValidation restricted(String reason) { return new ItemValidation(Status.QUARANTINED, List.of("restricted:" + reason), true); }

}
