package Vystorm.vystorm_core.network.core;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

/**
 * Participant-side pairing rules: take an offer only while no key exists and nothing is pending (rule 2), keep the
 * derived key only after the admin confirmed the matching code (rule 3). Thread-safe.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PairingGate {
    private final Clock clock;
    private Pairing.ClientSide pending;

    public PairingGate(Clock clock) { this.clock = clock; }

    /** @return the pending pairing to answer, or empty if the offer is ignored */
    public synchronized Optional<Pairing.ClientSide> acceptOffer(PairingOffer offer, boolean hasKey) {
        if (hasKey) return Optional.empty();
        if (pending != null && !pending.expired()) return Optional.empty();
        try {
            pending = Pairing.clientAccept(offer, clock);
            return Optional.of(pending);
        } catch (GeneralSecurityException | RuntimeException badOffer) {
            pending = null;
            return Optional.empty();
        }
    }

    public synchronized Optional<Pairing.ClientSide> pending() {
        if (pending != null && pending.expired()) pending = null;
        return Optional.ofNullable(pending);
    }

    /** @return the confirmed pairing (key + hub address) when the code matches, else empty; a match clears it */
    public synchronized Optional<Pairing.ClientSide> confirm(String code) {
        Optional<Pairing.ClientSide> current = pending();
        if (current.isEmpty() || code == null) return Optional.empty();
        if (!current.get().code().equals(code.trim().toUpperCase(Locale.ROOT))) return Optional.empty();
        pending = null;
        return current;
    }

    public synchronized void unpair() { pending = null; }
}
