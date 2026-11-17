// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, Icon.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.util.regex.Pattern;

/**
 * Symbol für native Elemente, allgemein genug für eigene Items (Vystorm-Items):
 * <ul>
 *   <li>{@code {"item":"minecraft:diamond_sword"}}, optional {@code "model":"vystorm:items/flame_blade"} für ein
 *       Item-Modell aus dem Server-Ressourcenpaket,</li>
 *   <li>{@code {"sprite":"minecraft:hud/heart/full"}} – GUI-Sprite (Vanilla oder Ressourcenpaket),</li>
 *   <li>{@code {"image":"/m/items/icons/flame_blade.png"}} – PNG vom festgepinnten Web-Ursprung des Servers
 *       (nur Pfad, kein Host; höchstens 64 KiB und 128×128 px; siehe PROTOCOL.md).</li>
 * </ul>
 * Beliebige URLs gibt es nicht.
 */
public record Icon(Kind kind, String id, String model) {
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    private static final Pattern IMAGE_PATH = Pattern.compile("/[A-Za-z0-9_./-]{1,160}\\.png");

    public enum Kind { ITEM, SPRITE, IMAGE }

    /** Absoluter Pfad auf dem Web-Ursprung, endet auf {@code .png}, ohne {@code ..}, {@code //}, Query oder Fragment. */
    public static boolean validImagePath(String path) {
        return path != null && IMAGE_PATH.matcher(path).matches() && !path.contains("..") && !path.contains("//");
    }

    /** {@code namespace:path} wie Minecrafts {@code Identifier}, ohne {@code ..}. */
    public static boolean validResource(String id) {
        return id != null && RESOURCE.matcher(id).matches() && !id.contains("..");
    }
}
