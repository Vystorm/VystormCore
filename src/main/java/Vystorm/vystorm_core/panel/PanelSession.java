package Vystorm.vystorm_core.panel;

/** Serverseitiger Speicher einer offenen Panel-Sitzung (z. B. Filtertext, gewählter Reiter im Dialog). Nur Server-Thread. @since 0.25.0 */
public interface PanelSession {
    int id();

    <T> T get(String key);

    void put(String key, Object value);

    void remove(String key);
}
