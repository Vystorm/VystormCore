package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.Visibility;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Sieht {@code viewer} den Besucher {@code other}? Fehler der Party-Abfrage zählen als „nicht sichtbar“. */
public final class VisibilityRules {
    private VisibilityRules() {}

    public static boolean visible(Visibility mode, UUID viewer, UUID other, BiPredicate<UUID, UUID> sameParty) {
        if (viewer.equals(other)) return true;
        return switch (mode) {
            case ALL -> true;
            case NONE -> false;
            case PARTY -> {
                try { yield sameParty.test(viewer, other); } catch (RuntimeException | LinkageError failure) { yield false; }
            }
        };
    }
}
