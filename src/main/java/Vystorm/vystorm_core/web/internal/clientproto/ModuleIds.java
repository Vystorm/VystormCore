// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, ModuleIds.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

import java.util.regex.Pattern;

/** Modul-IDs wie in Cores {@code WebModule.ID}: {@code [a-z0-9-]{1,32}}; leer = Übersicht. */
public final class ModuleIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern PAGE = Pattern.compile("/[A-Za-z0-9/_=&.,:~+-]{0,159}(#[A-Za-z0-9/_=&.,:~+-]{0,127})?");

    private ModuleIds() {}

    /** Gültige Modul-ID oder leer (Übersicht). */
    public static boolean valid(String id) {
        return id != null && (id.isEmpty() || ID.matcher(id).matches());
    }

    /**
     * Seite auf dem Web-Ursprung, relativ: Pfad ab {@code /} und optional ein Fragment, z. B. {@code /app/skills#node/12}
     * oder {@code /m/shop/#npc/12}. Leer = „die Seite, auf der der Login landet“. Erlaubt:
     * {@code /[A-Za-z0-9/_=&.,:~+-]{0,159}(#[A-Za-z0-9/_=&.,:~+-]{0,127})?} ohne {@code ..}, {@code //}, Query oder
     * Prozent-Kodierung.
     */
    public static boolean validPage(String page) {
        return page != null && (page.isEmpty() || (PAGE.matcher(page).matches() && !page.contains("..") && !page.contains("//")));
    }

    /** @throws ProtocolException wenn ungültig */
    public static String checkPage(String field, String page) {
        if (!validPage(page)) throw new ProtocolException(field + " is not a valid page");
        return page;
    }

    /** @throws ProtocolException wenn ungültig */
    public static String check(String field, String id) {
        if (!valid(id)) throw new ProtocolException(field + " is not a valid module id");
        return id;
    }
}
