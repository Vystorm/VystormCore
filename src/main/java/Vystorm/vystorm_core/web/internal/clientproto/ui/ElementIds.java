// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, ElementIds.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.util.regex.Pattern;

/**
 * Element-IDs: {@code [a-z0-9][a-z0-9_.:/-]{0,63}}. Konvention: {@code <plugin>:<name>}, z. B. {@code skills:xp},
 * damit jedes Plugin mit CLEAR {@code skills:} nur die eigenen Elemente entfernt.
 */
public final class ElementIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.:/-]{0,63}");

    private ElementIds() {}

    public static boolean valid(String id) { return id != null && ID.matcher(id).matches(); }

    /** Präfix für CLEAR: dieselben Zeichen, 1..64 lang. */
    public static boolean validPrefix(String prefix) { return valid(prefix); }
}
