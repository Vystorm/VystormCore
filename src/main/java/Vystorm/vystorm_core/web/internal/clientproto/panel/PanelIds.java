// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelIds.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import java.util.regex.Pattern;

/** ID-Regeln von {@code vystorm:panel}. */
public final class PanelIds {
    private static final Pattern ELEMENT = Pattern.compile("[a-z0-9][a-z0-9_.:/-]{0,63}");
    /** Von Core vergebene Pfad-IDs für namenlose, nicht interaktive Elemente (Plan-Entscheidung 3). */
    private static final Pattern AUTO = Pattern.compile("~[0-9]{1,4}(/[0-9]{1,4}){0,23}");
    /** {@code <namespace>:<name>}, zusammen ≤ 64 Bytes. */
    private static final Pattern PANEL = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,31}");
    private static final Pattern ROW_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private PanelIds() {}

    public static boolean element(String id) { return id != null && ELEMENT.matcher(id).matches(); }
    public static boolean auto(String id) { return id != null && id.length() <= 64 && AUTO.matcher(id).matches(); }
    public static boolean elementOrAuto(String id) { return element(id) || auto(id); }
    public static boolean panel(String id) { return id != null && id.length() <= 64 && PANEL.matcher(id).matches(); }
    public static boolean rowKey(String key) { return key != null && ROW_KEY.matcher(key).matches(); }
    public static boolean sha256Hex(String hex) { return hex != null && SHA256.matcher(hex).matches(); }
}
