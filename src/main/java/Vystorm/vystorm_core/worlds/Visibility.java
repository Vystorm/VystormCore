package Vystorm.vystorm_core.worlds;

/**
 * Wer wen in einer Plugin-Welt sieht (nur zwischen Besuchern derselben Welt; Core setzt {@code hidePlayer/showPlayer}).
 *
 * @since 0.26.0
 */
public enum Visibility {
    /** Alle Besucher sehen sich. */
    ALL,
    /** Nur Besucher derselben Party ({@code PartyServices.sameParty}); ohne Party-Plugin sieht man niemanden. */
    PARTY,
    /** Niemand sieht andere Besucher. */
    NONE
}
