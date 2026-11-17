package Vystorm.vystorm_core.web;

/**
 * HTTP-Methoden, die eine {@link WebRoute} annehmen kann. {@link #GET} ist immer lesend; alle anderen Methoden
 * verlangen eine Sitzung mit CSRF-Token und passenden {@code Origin}.
 *
 * @since 0.17.0
 */
public enum WebMethod {
    GET, POST, PUT, PATCH, DELETE;

    /** Ob die Methode Zustand ändern darf (alles außer {@link #GET}). */
    public boolean unsafe() { return this != GET; }
}
