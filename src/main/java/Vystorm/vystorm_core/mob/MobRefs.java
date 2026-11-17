package Vystorm.vystorm_core.mob;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.mythic.MythicMobsBridge;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Auflösung von Mob-Referenzen mit fester Reihenfolge "Vystorm vor Mythic":
 * <ol>
 *   <li>Vystorm-Mobs ({@link MobServices}), wenn aktiv und die ID bekannt ist. Importierte MythicMobs-Mobs tragen dort
 *       dieselbe ID (z. B. {@code MM_BLAZE_STANDARD_1}).</li>
 *   <li>MythicMobs ({@link MythicMobsBridge}) nur als Rückfall, wenn Vystorm-Mobs fehlt, inaktiv ist oder die ID nicht kennt.</li>
 * </ol>
 * Referenzformen: {@code ID}, {@code mythic:ID} bzw. {@code mythicmobs:ID} (beide Stufen) und {@code vystorm:ID}
 * (nur Vystorm-Mobs, kein Rückfall). Alle Methoden auf dem Server-Thread aufrufen.
 *
 * <p>Preferred access: {@code api.mobs().refs()}.
 *
 * @since 0.12.0
 */
public final class MobRefs {
    /** Wer eine Referenz bedient. */
    public enum Source { VYSTORM, MYTHIC_MOBS }

    /** Ein Spawn wurde ausgelöst, das Ergebnis ist unklar: nicht wiederholen und nicht durch Vanilla ersetzen. */
    public static final class UnknownOutcomeException extends RuntimeException {
        public UnknownOutcomeException(String message, Throwable cause) { super(message, cause); }
    }

    private MobRefs() {}

    /** Die reine Mob-ID ohne {@code vystorm:}/{@code mythic:}/{@code mythicmobs:}-Präfix. */
    public static String id(String ref) {
        if (ref == null) return "";
        String text = ref.trim();
        int colon = text.indexOf(':');
        if (colon < 0) return text;
        String prefix = text.substring(0, colon).toLowerCase(Locale.ROOT);
        return switch (prefix) {
            case "vystorm", "mythic", "mythicmobs" -> text.substring(colon + 1).trim();
            default -> text;
        };
    }

    /** Ob die Referenz ausdrücklich nur Vystorm-Mobs meint ({@code vystorm:ID}). */
    static boolean vystormOnly(String ref) {
        return ref != null && ref.trim().toLowerCase(Locale.ROOT).startsWith("vystorm:");
    }

    /** Ob Vystorm-Mobs aktiv ist und die ID kennt. */
    public static boolean vystormKnows(String ref) {
        String id = id(ref);
        if (id.isEmpty()) return false;
        MobService service = MobServices.service().orElse(null);
        if (service == null) return false;
        try {
            return service.getDefinition(id).isPresent();
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report("MobRefs", "Mob-Service", "getDefinition", failure);
            return false;
        }
    }

    /** Wer die Referenz jetzt bedienen würde; leer, wenn niemand sie kennt. */
    public static Optional<Source> source(String ref) {
        if (vystormKnows(ref)) return Optional.of(Source.VYSTORM);
        if (vystormOnly(ref)) return Optional.empty();
        String id = id(ref);
        if (!id.isEmpty() && mythicAvailable() && MythicMobsBridge.exists(id)) return Optional.of(Source.MYTHIC_MOBS);
        return Optional.empty();
    }

    /**
     * Spawnt über die erste Quelle, die die ID kennt. Leer, wenn keine sie kennt oder Vystorm-Mobs den Spawn vor dem
     * Entstehen eines Mobs ablehnt (dann sicher durch Vanilla ersetzbar).
     *
     * @throws UnknownOutcomeException wenn ein Spawn ausgelöst wurde, sein Ergebnis aber unklar ist
     */
    public static Optional<Entity> spawn(String ref, Location location, int level, String sourcePlugin) {
        String id = id(ref);
        if (id.isEmpty() || location == null) return Optional.empty();
        if (vystormKnows(id)) {
            MobService service = MobServices.service().orElse(null);
            if (service != null) {
                MobSpawnRequest request = MobSpawnRequest.of(id).at(location).level(level).source(sourcePlugin).build();
                Optional<MobHandle> handle;
                try {
                    handle = service.spawn(request);
                } catch (RuntimeException | LinkageError failure) {
                    throw new UnknownOutcomeException("Vystorm-Mobs spawn outcome is unknown", failure);
                }
                if (handle == null || handle.isEmpty()) return Optional.empty();
                Optional<? extends Entity> entity;
                try {
                    entity = handle.get().entity();
                } catch (RuntimeException failure) {
                    throw new UnknownOutcomeException("Vystorm-Mobs handle without entity", failure);
                }
                if (entity == null || entity.isEmpty()) throw new UnknownOutcomeException("Vystorm-Mobs handle without entity", null);
                return Optional.of(entity.get());
            }
        }
        if (vystormOnly(ref) || !mythicAvailable()) return Optional.empty();
        try {
            return Optional.ofNullable(MythicMobsBridge.spawn(id, location, level));
        } catch (MythicMobsBridge.UnknownOutcomeException unknown) {
            throw new UnknownOutcomeException("MythicMobs spawn outcome is unknown", unknown);
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    private static boolean mythicAvailable() {
        try {
            return MythicMobsBridge.active();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }
}
