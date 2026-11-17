package Vystorm.vystorm_core.web;

/**
 * Bearbeitet eine API-Anfrage. Läuft <b>nicht</b> auf dem Server-Thread (Core begrenzt die gleichzeitigen Aufrufe und
 * bricht nach einem Timeout ab); Bukkit-Zugriffe über {@link WebRequest#sync(java.util.concurrent.Callable)}.
 *
 * <p>Wirft der Handler eine {@link WebException}, geht deren Status und Meldung an den Browser. Jede andere Exception
 * wird über {@code ProviderFailures} geloggt und als HTTP 500 ohne Details beantwortet.
 *
 * @since 0.17.0
 */
@FunctionalInterface
public interface WebHandler {
    WebResponse handle(WebRequest request) throws Exception;
}
