# Web-Plattform: Sicherheitsmodell und Tunnel-Protokoll (Core 0.17.0, Live/Daten/UI 0.18.0, Härtung 0.18.1)

Dieses Dokument richtet sich an Reviewer. Es beschreibt die Vertrauensgrenzen, was jede Schicht durchsetzt, die Konfigurationsschalter und das Tunnel-Protokoll zwischen Core und dem Node.js-Gateway ([Vystorm/vystorm-web-gateway](https://github.com/Vystorm/vystorm-web-gateway), dort `SECURITY.md`).

Code: `src/main/java/Vystorm/vystorm_core/web/` (öffentliche API) und `web/internal/` (Core-intern). Die Tests liegen unter `src/test/java/Vystorm/vystorm_core/web/internal/`.

## Grundsatz

Browser, Client-Mod und Gateway sind **nie** Autorität. Core ist die einzige Instanz, die Sitzungen ausstellt, Rechte prüft und Zustand ändert. Jede Änderung läuft durch einen `WebHandler` eines Plugins auf dem Server. Der Browser hat keinen Zugriff auf die Datenbank.

## Akteure und Vertrauensgrenzen

```
Browser ──HTTPS──▶ [Reverse-Proxy/TLS] ──HTTP──▶ Core embedded (127.0.0.1:8190)
Browser ──HTTPS──▶ Gateway (Node.js) ◀──wss, HMAC-Tunnel── Core (ausgehend)
Spieler im Spiel ──/web──▶ Core ──Chat-Link mit Einmal-Token──▶ Browser
```

| Grenze | Vertrauen | Durchgesetzt durch |
| --- | --- | --- |
| Browser → Core/Gateway | keines | Host-Allowlist, Pfad-Normalisierung, Grenzen, Ratenlimits, Sitzung, CSRF, Origin, Rechte je Anfrage |
| Reverse-Proxy → Core (embedded) | nur IPs in `trusted-proxies` | `X-Forwarded-For`/`-Proto` werden nur von diesen IPs gelesen. Es gilt der rechteste nicht vertrauenswürdige Eintrag. Einträge müssen IP-Literale sein, es gibt keine DNS-Auflösung. |
| Gateway → Core | nur nach gegenseitiger HMAC-Authentisierung | Die Client-IP und das `secure`-Flag aus dem Tunnel gelten nur, weil der Kanal authentisiert ist. Alle anderen Prüfungen macht Core trotzdem selbst. |
| Plugin-Handler → Core | Plugins sind vertrauenswürdiger Servercode | Timeout, begrenzte Parallelität, Fehler gekapselt (`ProviderFailures`, 500 ohne Details) |

## Angreifer und Gegenmaßnahmen

| Bedrohung | Gegenmaßnahme |
| --- | --- |
| Login-Link wird mitgelesen oder weitergegeben | Das Token hat 256 Bit (`SecureRandom`), gilt 60 s (`session.login-token-seconds`) und nur einmal (atomares `remove`). Es gehört zu genau einem Spieler (UUID). Es steht im **Fragment** und damit nie in Server-, Proxy- oder Gateway-Logs oder im Referer. `login.js` entfernt es sofort aus der Adresszeile. Pro Spieler sind höchstens 3 Links offen. |
| Token/Cookie im Speicher-Dump | Core speichert nur SHA-256-Hashes von Login-Token und Sitzungs-Cookie. Das CSRF-Token liegt roh im Speicher, weil es an die Seite zurückgegeben wird. |
| Brute Force auf Token oder Cookie | 256-Bit-Raum. Login ist je IP begrenzt (`rate-limits.login-per-minute`, Burst 5), dazu das globale IP-Limit. Format-Prüfung vor dem Hashing. Alle Grenzen je IP zählen IPv6-Adressen je /64 (0.18.1), sonst könnte ein Anschluss mit eigenem /64 die Limits umgehen und die Bucket-Tabelle fluten. |
| CSRF | Das Cookie ist `SameSite=Strict`. Jede Nicht-GET-Anfrage (auch Login und Logout) braucht einen `Origin`, der exakt dem öffentlichen Ursprung gleicht, und, falls gesendet, `Sec-Fetch-Site: same-origin`. Nicht-GET-API-Aufrufe und Logout brauchen zusätzlich den Header `X-Vystorm-CSRF` (32 Byte je Sitzung, Vergleich in konstanter Zeit). GET-Routen können nie `mutating` sein (das prüft der Builder). |
| Session-Fixation | Beim Login wird eine mitgebrachte alte Sitzung beendet und immer ein neues Cookie ausgestellt. |
| Gestohlenes Cookie | `HttpOnly`, bei HTTPS `Secure` und `__Host-`-Präfix (kein `Domain`, `Path=/`). Die Sitzung läuft gleitend 30 min, höchstens 12 h, maximal 5 je Spieler. Sie endet bei `/web logout`, Bann (Kick-Event und Prüfung jede Sekunde) und Serverstopp. Eine Bindung an die IP gibt es bewusst nicht (Mobilnetze). |
| Rechteausweitung | Pro Anfrage gelten `vystorm.web.use`, die Modul- und die Routenberechtigung aus einem Schnappschuss, den der Server-Thread jede Sekunde erneuert (Bukkit ist nicht threadsicher). Ein fehlender Knoten wird synchron nachgeladen, bei einem Fehler wird verweigert. Offline gilt der letzte Online-Wert, sonst der Bukkit-Standard (OP-abhängig). Seit 0.18.1 werden die Rechte beim Ausstellen des Links (Spieler online) sofort gemessen, und Inhaber offener Links zählen beim Refresh mit. Vorher fiel ein Link, den ein Spieler im Spiel holte und erst nach dem Ausloggen einlöste, auf die Standardwerte zurück: ein per LuckPerms negierter `default: true`-Knoten war dann erlaubt. Rechteänderungen, während der Spieler offline ist, sieht Core erst beim nächsten Login (bewusst, siehe Restrisiken). `requiresOnline`-Routen antworten offline mit 409. |
| XSS | Die CSP ist `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`. Es gibt keine Inline-Skripte. `web.js` baut DOM nur über `textContent`/`el()` und lässt keine `javascript:`-Links zu. JSON-Antworten werden mit HTML-Escaping serialisiert. Dazu `X-Content-Type-Options: nosniff` und eine Allowlist der Content-Types. |
| Clickjacking | `frame-ancestors 'none'` und `X-Frame-Options: DENY` |
| Pfad-Traversal | Moduldateien werden beim Registrieren in den Speicher geladen, danach wird nur per exaktem Schlüssel gesucht. Pfade werden streng dekodiert: `.`/`..`, leere Segmente, `%2F`, `\`, Steuerzeichen, ungültiges UTF-8 und Punkt-Dateien werden abgelehnt. Nur erlaubte Endungen kommen durch, Symlinks werden ignoriert. |
| DNS-Rebinding / fremde Hosts | Es gibt eine `Host`-Allowlist (Host der `public-url`, `allowed-hosts`, ohne `public-url` nur Loopback:Port). Sonst kommt 421. |
| DoS / Slowloris (embedded) | Anfragezeile ≤ 8 KiB, Header ≤ 16 KiB und ≤ 64 Stück. Kopf und Körper müssen innerhalb von 10 s fertig sein (Frist über alle Leseoperationen, nicht pro Byte). Leerlauf-Timeout 15 s. Schreib-Timeout 30 s (`limits.write-timeout-seconds`, 0.18.1): hängt ein Schreibvorgang länger, weil der Client nicht liest („Slow Read“, auch mit gepipelinten Anfragen oder einem nie gelesenen Live-Kanal), schließt ein Wächter die Verbindung. Vorher blieb der Platz dauerhaft belegt. Höchstens 256 Verbindungen gesamt und 32 je IP bzw. IPv6-/64 (Proxys ausgenommen), Körper ≤ 64 KiB (Route kleiner). Token-Bucket je IP, Sitzung und Route. Höchstens 32 Handler gleichzeitig, danach 503. Handler-Timeout 10 s, danach 504. Ein Handler, der den Timeout ignoriert, behält seinen Platz, bis er wirklich endet (0.18.1), sonst ließen sich hängende Handler unbegrenzt anhäufen. |
| Request-Smuggling hinter Proxy | `Transfer-Encoding` → 501, doppelte oder ungültige `Content-Length` → 400. Header-Folding, nacktes CR/LF und NUL → 400. Nur `origin-form`-Ziele. Die Antworten haben immer `Content-Length`. |
| JSON-Bomben | Striktes Parsen (Gson `Strictness.STRICT`), gültiges UTF-8, Tiefe ≤ 32, keine Daten nach dem Wert, Größenlimit vor dem Parsen, `Content-Type: application/json` Pflicht |
| Live-Kanal (SSE) | `GET /core/live` braucht eine gültige Sitzung und `vystorm.web.use`. `Sec-Fetch-Site` (falls gesendet) muss `same-origin` sein, `Origin` (falls gesendet) exakt passen. EventSource ist wie `fetch` an die Same-Origin-Policy gebunden, es gibt kein CORS, und das Cookie ist `SameSite=Strict`, also kann keine fremde Seite mitlesen. Verbindungsaufbau höchstens 30/min je Sitzung, 5 Verbindungen je Spieler, 2000 insgesamt. Zustellung nur bei gültiger Modulberechtigung (je Ereignis geprüft). Ereignisse ≤ 16 KiB, je Spieler zusammengefasst (≤ 128 offene Schlüssel, sonst `resync`). Die Warteschlange je Verbindung ist begrenzt (256), ein Client, der nicht mitliest, fliegt mit `bye: resync`. Logout, Entzug, Bann, Sitzungsende und fehlendes Grundrecht beenden den Kanal. Ein offener Kanal hält die Sitzung gleitend aktiv, aber nie über das absolute Ende (12 h). |
| Spieldaten `/data/…` | Veröffentlichen darf nur der Owner des Moduls. Name `[a-z0-9-]`, ≤ 8 MiB, ≤ 64 je Modul. Module ohne Berechtigung liefern öffentlich (wie Moduldateien), Module mit Berechtigung nur mit Sitzung und Recht (`Cache-Control: private`). |
| Oberflächen-Build (`web.ui-dir`, sonst der im Jar gebündelte Build, 0.24.0) | wird beim Start in den Speicher geladen, mit denselben Regeln wie Moduldateien (Endungs-Allowlist, keine Symlinks oder Punktdateien, Größenlimits). Er gilt nur für `/`, `/login`, `/app/**`, `/assets/**` und Dateien im Wurzelverzeichnis. Core-Pfade haben Vorrang. Der gebündelte Build besteht dieselbe CSP-Prüfung wie `npm run build` (`BundledUiTest`). |
| Einstellungen im Web (`/api/settings/*`, 0.24.0) | Lesen und Speichern laufen auf dem Server-Thread über `SettingsRegistry.view/save`, also mit denselben Prüfungen wie der Dialog im Spiel: Abschnitts- und Einstellungsrechte am echten `Player` (Server-Abschnitte nur mit ihrem Recht bzw. `vystorm_core.admin`), Typ, Bereich, Optionen, Länge, danach entscheidet der Setter des Plugins. Unbekannte oder verborgene IDs werden nicht geschrieben. Speichern braucht CSRF, Origin und den Spieler online (409), höchstens 64 Werte und 8 KiB je Anfrage, Audit wie jede ändernde Route. |
| Informationslecks | Keine Stacktraces. Fehlerantworten sind `{"error","message"}`, 500er sind generisch. Kein `Server`-Header. `Referrer-Policy: no-referrer`, `Cache-Control: no-store` für API, Login und Fehler, COOP/CORP `same-origin`, `Permissions-Policy` restriktiv, HSTS nur bei HTTPS-`public-url`. |
| Log-Injection / Audit-Flut | Audit-Werte ohne Steuerzeichen (auch U+2028/2029), gekürzt. Tokens, Cookies, CSRF und Körper werden nie geloggt. Abweisungen ohne Sitzung (falscher `Origin` bei Login/API) landen je Client höchstens 20-mal pro Minute im Log (0.18.1), damit anonymer Verkehr die rotierende Datei nicht flutet und echte Einträge verdrängt. |
| Kompromittiertes Gateway | Das Gateway sieht den Klartext-Verkehr (TLS endet dort), also auch Login-Token und Cookies seiner Nutzer. Es kann **keine** Sitzungen erzeugen, keine Rechte umgehen und keine Aktionen ohne gültige Browser-Sitzung und CSRF-Token auslösen. Es kann aber die Client-IP fälschen (betrifft Audit und IP-Limits). Deshalb läuft das Gateway nur auf eigener, vertrauenswürdiger Infrastruktur. |
| Gestohlenes Tunnel-Geheimnis | Wer es hat, kann ein Gateway spielen. Das Geheimnis steht nur in Cores `config.yml` und in der Gateway-Umgebung. Es ist ≥ 32 Zeichen lang, wird automatisch mit 384 Bit erzeugt und nie geloggt. Nach dem automatischen Erzeugen nimmt Core „anderen“ Systembenutzern die Rechte an `config.yml` (POSIX, 0.18.1). Tausch: in beiden Konfigurationen ändern und neu starten. |

## Audit

`plugins/vystorm_core/web/audit.log` bekommt eine Zeile je Ereignis: `login` (ok/denied mit Grund), `logout`, `revoke` (Bann, Befehl, API), `link` (ausgestellt, von `chat`/`player`/`console`/Plugin-Issuer), `api` (jede `mutating`-Route mit Status sowie verweigerte CSRF-/Origin-/Rechte-Versuche), `tunnel` (verbunden, abgebrochen, abgelehnte Signatur) und `platform` (Start/Stopp). Rotation bei `audit.max-file-mb`, höchstens `audit.files` Dateien. Geschrieben wird asynchron mit begrenzter Warteschlange (4096). Was darüber hinausgeht, wird verworfen und gezählt.

## Konfiguration (`config.yml`, Abschnitt `web`)

| Schlüssel | Standard | Bedeutung |
| --- | --- | --- |
| `enabled` | `false` | Plattform an/aus |
| `mode` | `embedded` | `embedded` oder `gateway` |
| `public-url` | `""` | öffentliche Basis-URL ohne Pfad. Bestimmt Links, Origin, Host-Allowlist sowie `Secure`/`__Host-`/HSTS (bei `https://`). Im Gateway-Modus Pflicht. |
| `allowed-hosts` | `[]` | weitere erlaubte `Host`-Header |
| `embedded.bind` / `port` | `127.0.0.1` / `8190` | Adresse des eigenen Servers |
| `embedded.trusted-proxies` | `[]` | IPs/CIDRs, deren `X-Forwarded-*` gelten |
| `embedded.max-connections` / `-per-ip` | `256` / `32` | Verbindungslimits |
| `gateway.url` | `wss://…/_vystorm/tunnel` | Tunnel-Endpunkt. `ws://` nur zu Loopback oder mit `allow-insecure` |
| `gateway.secret` | `""` → automatisch | gemeinsames Geheimnis (≥ 32 Zeichen) |
| `gateway.allow-insecure` | `false` | `ws://` zu entfernten Hosts, nur in privaten verschlüsselten Netzen (Tailscale) |
| `gateway.max-in-flight` | `64` | gleichzeitige Tunnel-Anfragen |
| `gateway.clock-skew-seconds` | `30` | erlaubte Uhrabweichung beim Handshake |
| `session.login-token-seconds` | `60` | Gültigkeit des Login-Links |
| `session.idle-minutes` / `max-hours` / `max-per-player` | `30` / `12` / `5` | Sitzungsdauer und Anzahl |
| `limits.*` | siehe `config.yml` | Körper, Header, Timeouts (auch `write-timeout-seconds`, Standard 30), Handler-Parallelität |
| `rate-limits.ip-per-minute` / `login-per-minute` / `session-per-minute` | `600` / `10` / `300` | Token-Bucket-Limits |
| `audit.enabled` / `max-file-mb` / `files` | `true` / `5` / `5` | Audit-Log |
| `live.coalesce-ms` / `max-event-bytes` / `max-per-player` / `max-connections` | `100` / `16384` / `5` / `2000` | Live-Kanal |
| `ui-dir` | `web/ui` | gebaute Oberfläche als Rückfall (sonst eingebaute Übersicht) |

Alle Werte werden auf sichere Bereiche begrenzt (`WebSettings`). Bei Konfigurationsfehlern startet die Plattform nicht, Core läuft weiter.

## Tunnel-Protokoll v1 (Core ↔ Gateway)

Beide Seiten implementieren genau diese Spezifikation: Java in `web/internal/TunnelProtocol.java` und `GatewayTunnel.java`, JS in `vystorm-web-gateway/src/protocol.js` und `src/gateway.js`. Die gemeinsamen Testvektoren stehen in `TunnelProtocolTest` und `test/protocol.test.js`.

Notation: `HMAC(k, m)` = HMAC-SHA256, `b64u` = base64url ohne Padding, `secret` = UTF-8-Bytes von `web.gateway.secret`. Nonces sind 32 Zufallsbytes als `b64u` (43 Zeichen), Zeitstempel sind Epoch-Millisekunden.

1. **Upgrade (Core → Gateway):** `GET <gateway.url>` als WebSocket-Upgrade mit den Headern
   `X-Vystorm-Tunnel: 1`, `X-Vystorm-Ts: <ts>`, `X-Vystorm-Nonce: <cN>`,
   `X-Vystorm-Signature: b64u(HMAC(secret, "vystorm-tunnel-v1\ncore-hello\n" + ts + "\n" + cN))`.
   Das Gateway prüft vor dem Upgrade: Pfad, `|jetzt - ts| ≤ Fenster` (Standard 30 s), `cN` wohlgeformt und im Fenster noch nicht gesehen (Replay-Cache), Signatur in konstanter Zeit. Sonst `401` und Socket zu. Nur fehlgeschlagene Versuche zählen gegen die Grenze je IP, ein gültiger Handshake wird nie gedrosselt (Gateway 0.2.1; vorher konnte jeder über die gemeinsame Proxy-IP Core dauerhaft aussperren).
2. **Welcome (Gateway → Core, erste Nachricht, unversiegelt):**
   `{"t":"welcome","v":1,"ts":<ts2>,"nonce":"<gN>","sig":b64u(HMAC(secret, "vystorm-tunnel-v1\ngateway-welcome\n" + cN + "\n" + gN + "\n" + ts2))}`.
   Core prüft Zeitfenster und Signatur. Weil `cN` frisch ist, taugt ein mitgeschnittenes Welcome nichts. Ohne gültiges Welcome innerhalb von 10 s bricht Core ab.
3. **Sitzungsschlüssel:** `K = HMAC(secret, "vystorm-tunnel-v1\nsession\n" + cN + "\n" + gN)`.
4. **Alle weiteren Nachrichten** (Text-Frames) haben die Form `b64u(HMAC(K, dir + "\n" + json)) + "." + json` mit `dir` = `c2g` bzw. `g2c`. `json` enthält `"seq"`, lückenlos ab 1 je Richtung. Ein falscher MAC, eine falsche Richtung, eine falsche Sequenz, ein Binär-Frame oder eine Übergröße beenden die Verbindung. Das verhindert Replay, Umordnung und Reflexion auch ohne TLS (bei `ws://` bleibt die **Vertraulichkeit** ungeschützt, deshalb `wss://` oder Tailscale).
5. **Nachrichtentypen:**
   - `c2g hello` `{"t":"hello","seq":1,"core":"<version>","rev":<n>,"maxBody":<bytes>}`: erste versiegelte Nachricht von Core. Erst danach nimmt das Gateway den Tunnel in Betrieb und ersetzt eine ältere Core-Verbindung.
   - `g2c req` `{"t":"req","seq":…,"id":<n>,"method":"GET","path":"/api/x?y","headers":{"host":"…","cookie":"…",…},"ip":"<client-ip>","secure":true,"body":"<base64>"}`. Die Header-Namen sind klein geschrieben, das Gateway reicht nur eine Allowlist durch.
   - `c2g res` `{"t":"res","seq":…,"id":<n>,"status":200,"headers":[["Content-Type","…"],…],"body":"<base64>"}`
   - Gestreamte Antworten (Live-Kanal, 0.18.0): `c2g res` mit `"stream":true` und ohne Körper, dann beliebig viele `c2g chunk` `{"t":"chunk","id":<n>,"data":"<text>"}` (SSE-Text inklusive Heartbeat-Kommentaren alle 20 s) und zum Schluss `c2g end` `{"t":"end","id":<n>}`. Trennt der Browser, schickt das Gateway `g2c cancel` `{"t":"cancel","id":<n>}`, und Core beendet das Abonnement. Das Gateway begrenzt Streams gesamt (4000) und je IP (16).
   - `ping`/`pong` in beiden Richtungen (Heartbeat 15 s, Abbruch nach 45 s Stille)
   - `c2g assets` `{"t":"assets","seq":…,"rev":<n>}`: Moduldateien haben sich geändert, das Gateway leert seinen Cache.
   - Unbekannte Typen werden ignoriert (Vorwärtskompatibilität).
6. **Grenzen:** Core nimmt höchstens `2·max-body + 2·max-header + 64 KiB` Zeichen je Nachricht an und bearbeitet höchstens `gateway.max-in-flight` Anfragen gleichzeitig (sonst sofort 503). Ein zu langes Ziel (> 4096), zu viele Header oder ein zu großer Körper in einer `req` beantwortet Core mit 414/431/413; nur ein kaputtes Format gilt als Protokollverletzung (0.18.1, vorher beendete eine einzige solche Browser-Anfrage den Tunnel für alle). Das Gateway übernimmt `maxBody` aus dem `hello` als Obergrenze für Körper, begrenzt URLs auf höchstens 4096 Zeichen, Core-Nachrichten auf 16 MiB (Spieldaten bis 8 MiB als Base64), Anfragen im Flug und die Wartezeit je Anfrage (→ 504). Neuverbindung mit exponentiellem Backoff 1 bis 60 s plus Jitter.

## Restrisiken und Betrieb (Review 0.18.1)

- **TLS endet vor Core bzw. am Gateway.** Im eingebetteten Modus nur `bind: 127.0.0.1` hinter einem Reverse-Proxy mit HTTPS betreiben, `public-url` auf `https://…` setzen (erst dann `Secure`, `__Host-`, HSTS) und `trusted-proxies` genau auf die Proxy-Adresse. Ein direkt offener Port ohne TLS überträgt Login-Token und Cookie im Klartext.
- **Rechte offline:** Ändert ein Admin die Rechte eines Spielers, während dieser offline ist, gilt im Web bis zu dessen nächstem Login der zuletzt online gemessene Stand (Bukkit kann Offline-Rechte nicht threadsicher abfragen). Zum sofortigen Entzug `/web logout` für den Spieler (Konsole: Bann) bzw. `WebServices.revokeSessions` nutzen.
- **IP-Bans** (`/ban-ip`) beenden keine Web-Sitzungen, nur Spieler-Banns.
- **Handler-Idempotenz:** Läuft `request.sync(...)` in den Timeout, während die Aufgabe schon auf dem Server-Thread läuft, wird sie noch fertig, der Browser bekommt aber 503. Ändernde Routen sollten deshalb idempotent sein oder eine Anfrage-ID prüfen.
- **Tunnel-Sendepuffer:** Core puffert ausgehende Tunnel-Nachrichten ohne feste Obergrenze. Das Gateway ist vertrauenswürdig und liest zügig; ein hängendes Gateway fällt über den Heartbeat (45 s) auf.
- **Login-CSRF:** Wer einem Opfer seinen eigenen, frischen Login-Link unterschiebt, meldet es mit dem eigenen Konto an. Die Oberfläche zeigt den Spielernamen deutlich an.

## Client-Mod-Kanal `vystorm:web` (0.19.0)
- Der optionale Mod (Repo `Vystorm/vystorm-client`, dort `PROTOCOL.md` und `SECURITY.md`) bekommt über den Plugin-Kanal nur die Basis-URL und – auf Anfrage – ein Einmal-Token aus `WebServices.issueLogin(…, "client")`: dieselben Prüfungen (Plattform an, `vystorm.web.use`, Modulrecht), dasselbe Audit, dieselbe Lebensdauer wie ein `/web`-Link. Das Token geht nur an genau diesen Spieler, steht nie im Log und wird im Client nur als Fragment von `/login#…` benutzt. Danach gilt alles aus diesem Dokument (Cookie, CSRF, Origin, Rechte je Anfrage).
- Nachrichten sind unvertrauenswürdige Eingaben: ≤ 1 KiB, strenger Codec (Richtung, Längen, UTF-8, keine überzähligen Bytes), HELLO einmal pro Verbindung, Token-Anfragen je Spieler begrenzt (`web.client.open-per-minute`, Burst 3; Core begrenzt offene Tokens ohnehin auf 3 je Spieler), nach 20 kaputten Nachrichten wird der Spieler bis zum Rejoin ignoriert.
- `ClientServices.show` gibt nichts frei, es bittet den Client nur, eine Seite des eigenen Ursprungs zu zeigen (Seitenpfad streng geprüft). Der Client pinnt den Ursprung aus dem WELCOME, fragt den Spieler beim ersten Mal um Zustimmung und lässt im eingebetteten Browser nichts außerhalb dieses Ursprungs laden.
- Sitzungen merken sich den Kanal ihres Tokens (0.20.0). Beim Quit beendet Core die Sitzungen und offenen Tokens mit Issuer `client` (`web.client.end-sessions-on-quit`, Standard `client`; `all` = auch Browser-Tabs, `none` = keine), mit Audit-Eintrag `revoke … client quit`. Der Mod löscht seine Cookies beim Trennen zusätzlich selbst.

## Native Elemente `vystorm:ui` (0.20.0)
- Nur Anzeige: Elemente tragen keine Aktionen, keine Klick-/Hover-Ereignisse und keine Rechte. Der Client prüft sie streng (Schema v1, Grenzen, Steuerzeichen/`§` entfernt); Core prüft **vorher** mit demselben Schema, damit fehlerhafte Plugins nichts Ungültiges schicken, und nur an Spieler, deren Mod die passende Fähigkeit gemeldet hat.
- Namensraum je Plugin (`<plugin>:<name>`): ein Plugin kann über `ClientUi` nur eigene Elemente ändern, entfernen oder leeren; `CLEAR ""` (alles) sendet Core nie.
- Rate: je Spieler 30 Nachrichten/s (Burst 100), ≤ 32 je Tick, Warteschlange ≤ 384 – unter den Grenzen des Clients (40/s, Burst 120, 64 je Tick, 512). PATCHes je Element höchstens 4×/s, zusammengefasst.
- Vom Client: nur REJECT (≤ 1 KiB, strenger Codec, ≤ 5/s je Spieler, nach 20 kaputten Nachrichten ignoriert). Details sind Feldnamen, kein Inhalt; im Log höchstens einmal pro Minute je Plugin und Code, ohne Steuerzeichen.
