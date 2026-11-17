package Vystorm.vystorm_core.party;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Anbieter der Parties (VystormParties). Alle Methoden müssen schnell und threadsicher lesbar sein. */
public interface PartySource {
    Optional<PartyView> partyOf(UUID player);

    Optional<PartyView> party(UUID partyId);

    Collection<PartyView> all();

    /** Setzt eine Party-Einstellung (Plugin-Erweiterungen); {@code null} entfernt sie. Liefert, ob die Party existiert. */
    boolean setting(UUID partyId, String key, String value);
    /** Main thread only. Advisory, time-limited readiness; empty without support or after roster changes. */
    default Optional<PartyReadyCheck> readyCheck(UUID partyId) { return Optional.empty(); }

    /** Main thread only. Records already granted group XP or a completed provider goal, never grants rewards.
     * eventId must be stable for retries; the provider may reject duplicates, absent opt-in sessions or bounds. */
    default boolean recordProgress(UUID partyId, UUID eventId, String provider, String goal, double sharedXp) { return false; }
}
