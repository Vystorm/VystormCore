package Vystorm.vystorm_core.worlds.internal;

import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Befehls-Erlaubnisliste während eines Besuchs (Review C1): Erlaubt ist ein Befehl nur, wenn sein getipptes Label (ohne
 * {@code /}) oder der Hauptname des aufgelösten Befehls in der Liste steht. Ein Namespace-Alias muss zusätzlich auf
 * denselben registrierten Befehl wie sein Hauptname zeigen. Alles andere – auch unbekannte Befehle – wird abgelehnt.
 */
public final class CommandAllowList {
    private CommandAllowList() {}

    /** Label der Befehlszeile ({@code "/Deep:Market verkaufen 5"} → {@code "deep:market"}). */
    public static String label(String message) {
        if (message == null) return "";
        String text = message.strip();
        if (text.startsWith("/")) text = text.substring(1);
        int space = text.indexOf(' ');
        return (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    /** Label ohne Namensraum ({@code "deep:market"} → {@code "market"}). */
    public static String plain(String label) {
        int colon = label.lastIndexOf(':');
        return colon < 0 ? label : label.substring(colon + 1);
    }

    /**
     * @param resolvePrimary liefert zum Label den Hauptnamen des registrierten Befehls (klein) oder {@code null}
     */
    public static boolean allowed(String message, Set<String> allowed, Function<String, String> resolvePrimary) {
        return allowed(message, allowed, resolvePrimary, (label, primary) -> false);
    }

    /**
     * @param resolvePrimary liefert zum Label den Hauptnamen des registrierten Befehls (klein) oder {@code null}
     * @param sameCommand bestätigt für Namespace-Labels die Identität mit dem unter dem Hauptnamen registrierten Befehl
     */
    public static boolean allowed(String message, Set<String> allowed, Function<String, String> resolvePrimary,
                                  BiPredicate<String, String> sameCommand) {
        String label = label(message);
        if (label.isEmpty() || allowed.isEmpty()) return false;
        if (!label.contains(":") && allowed.contains(label)) return true;
        String primary;
        try { primary = resolvePrimary.apply(label); } catch (RuntimeException failure) { primary = null; }
        if (primary == null) return false;
        primary = plain(primary.toLowerCase(Locale.ROOT));
        if (!allowed.contains(primary) && !allowed.contains(label)) return false;
        if (!label.contains(":")) return true;
        try { return sameCommand.test(label, primary); } catch (RuntimeException failure) { return false; }
    }
}
