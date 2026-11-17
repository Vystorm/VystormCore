# Befehle auf Vystorm Core umstellen (ab Core 0.45.0)

Seit 0.45.0 kann jedes Vystorm-Plugin seine Befehle über Core ausgeben (`CoreCommands`, Capability `commands`, englische
Referenz: `docs/API.md` §24). Core übernimmt dann Parsen, Rechte, „nur Spieler“, Abklingzeiten, Tab-Vervollständigung,
Hilfe, Fehlermeldungen und die Übersetzung von Unterbefehlen, Argument-Schlüsselwörtern und Haupt-Aliasen. Pilot-Plugins:
**Vystorm Rails** (`/rails`, `/schienen`, `/铁轨`) und **Vystorm Chat** (alle 10 Befehle).

## Was Spieler davon haben

- `/rails ziel Hafen`, `/rails dest Hafen` und `/rails 目的地 Hafen` tun dasselbe – jeder Spieler darf die Namen aller
  installierten Sprachen benutzen, Englisch geht immer.
- Tab-Vervollständigung, Hilfe (`/rails hilfe`) und Verwendungszeilen kommen in der eigenen Sprache.
- `/vhelp` (`/vhilfe`, `/befehle`, `/v帮助`) zeigt alle Befehle aller Vystorm-Plugins, die man benutzen darf, nach Plugin
  sortiert und anklickbar.

## Die Einschränkung: Befehlsnamen sind global

Minecraft hat **einen** Befehlsbaum pro Server. `/schienen` gibt es also für alle oder für niemanden. Darum:

- Der englische Name (`/rails`) und die festen Aliase (`/vrails`, `/cart`) gibt es immer.
- Übersetzte **Haupt-Aliase** stehen in der Sprachdatei unter `commands.<befehl>.aliases`. Welche aktiv sind, entscheidet
  der Admin in Cores `config.yml`: `commands.localized-aliases: all` (alle Sprachen, Standard), `default` (nur die
  Serversprache) oder `none`. Einzelne Aliase lassen sich in `/vadmin commands` per Klick abschalten.
- Core nimmt nie einen Namen, den schon ein anderer Befehl hat (wird übersprungen und in `/vadmin commands` als
  Kollision angezeigt), und setzt die Aliase erst im ersten Server-Tick, wenn alle Plugins ihre eigenen Befehle haben.
- **Unterbefehle** und Schlüsselwörter (`an`/`aus`) sind dagegen pro Spieler übersetzt – dort gibt es die Einschränkung nicht.

## Drei Stufen

| Stufe | Aufwand | Was man tut | Ergebnis |
| --- | --- | --- | --- |
| 0 – nur Texte | Minuten | `commands.<befehl>.description` in `en.yml`, `de.yml`, `zh_cn.yml` eintragen | Übersetzte Zeile in `/vhelp`; sonst unverändert |
| 1 – Adapter | 1–3 h | plugin.yml-Befehl behalten, `CoreCommands.adopt(this, lang, spec)` mit den Unterbefehlen (ohne Handler) | Übersetzte Unterbefehle und Haupt-Aliase, Core-Hilfe, gemischte Tab-Vervollständigung; der alte Executor bekommt die englischen IDs |
| 2 – vollständig | siehe Tabelle | Baum mit `executes(...)` je Knoten, `CoreCommands.register(...)`, Befehl aus plugin.yml löschen | Alles von Core: Argumenttypen, Fehlermeldungen, Abklingzeiten, Paper-Brigadier |

Stufe 1 lässt sich schrittweise zu Stufe 2 ausbauen: ein deklarierter Knoten mit eigenem `executes(...)` läuft über
Core, alle anderen weiter über den alten Executor.

## Vorgehen für Stufe 2 (Muster: vystorm-rails, vystorm-chat)

1. `CoreApi.has(CoreApi.Capability.COMMANDS)` prüfen (mit `catch (LinkageError)`), sonst im Log auf Core 0.45.0 hinweisen.
2. Den Baum in einer statischen Methode bauen: `static CommandSpec commandSpec(MeinPlugin p)` – so können Tests `null`
   übergeben (`CommandTesting.detached(...)`).
3. `switch (args[0])` in `sub(...)`-Knoten zerlegen; `instanceof Player`-Prüfungen werden `playerOnly()`,
   `hasPermission` wird `permission(...)`, Zahlen/Spieler/Welten werden `Arg.integer/player/world`, feste Wortlisten
   `Arg.choice` oder `Arg.bool`. Deutsche Inline-Aliase im Code (`case "karte"`) wandern in `de.yml`.
4. Sprachdateien: Block `commands:` nach der Konvention unten in **alle** Sprachdateien (gleiche Schlüssel, `aliases: []`
   wo eine Sprache keine hat). Alte Hilfe-/Verwendungstexte entfernen, Core erzeugt sie.
5. Befehl aus `plugin.yml` löschen (Rechte bleiben).
6. Stellen, die Befehlsnamen vergleichen (Mute-Filter, Weiterleitungen), über `CoreCommands.find(label)` auf den
   englischen Namen abbilden – Vystorm Chat macht das für gesperrte Befehle stummgeschalteter Spieler.
7. Test mit `CommandTesting.detached(...)`: Vervollständigung je Sprache, Hilfe, `conflicts()` leer.

```yaml
commands:
  rails:
    description: "Loren: Ziel, Streckenkarte, Loren-Info und Lorenblöcke"
    aliases: [schienen]                 # Haupt-Aliase dieser Sprache (serverweit, Admin-Einstellung)
    sub:
      dest:
        name: ziel                      # Name in dieser Sprache
        aliases: [reiseziel]            # weitere Namen in dieser Sprache
        description: "Ziel anzeigen oder setzen"
        args:
          name: "BAHNHOF|löschen"       # Anzeigename des Arguments (keine <tags>)
```

## Stand der Plugins und Schätzung

S = unter 2 h, M = 2–6 h, L = über 6 h (Stufe 2 inklusive en/de/zh_cn-Namen und Tests). „Wurzeln“ = Hauptbefehle.

| Plugin (Repo) | Wurzeln | Unterbefehle ca. | Aufwand | Schätzung | Anmerkung |
| --- | --- | --- | --- | --- | --- |
| **VystormRails** (vystorm-rails) | 1 | 6 | – | erledigt | Pilot |
| **VystormChat** (vystorm-chat) | 10 | 12 | – | erledigt | Pilot; Kanal-Kurzbefehle (`/g`, `/l`) bleiben dynamisch im Command-Map |
| Vystorm Core selbst (`/menu`, `/einstellungen`, `/vadmin`, `/web`, `/vregion`, `/vworlds`, `/vtrade`, `/nbt`, `/language`) | 9 | ~45 | M | 5 h | Beschreibungen schon übersetzt (Stufe 0); `/vadmin` mit vielen Modulen zuerst per `adopt` |
| bloodmoon (BloodMoon) | 1 | 4 | S | 1 h | kleiner Executor |
| vystorm-gallery (VystormGallery) | 1 | 4 | S | 1 h | `onCommand` im Plugin |
| CustomTrees | 1 | 14 | S | 1,5 h | hat schon `command.*`-Texte |
| WinterIsComing | 1 | 8 | S | 1,5 h | flache Texte |
| vystorm-boards (VystormBoards) | 1 | 12 | S | 1,5 h | `onCommand` im Plugin |
| vystorm-paddock (VystormPaddock) | 1 | 13 | S | 1,5 h | `onCommand` im Plugin |
| vystorm-snapshot (VystormSnapshot) | 1 | 9 | S | 1,5 h | `onCommand` im Plugin |
| vystorm-portals (VystormPortals) | 1 | 7 (+ verschachtelt) | S | 1,5 h | Texte mit `{0}`-Platzhaltern |
| vystorm-veins (VystormVeins) | 2 | 15 | S | 2 h | nur softdepend auf Core: Baum nur registrieren, wenn Core da ist, sonst alter Executor |
| Vystorm-Frontier | 1 | ~10 | S–M | 2,5 h | |
| VystormFarms | 2 | 10 | S–M | 2,5 h | hat `command.*`-Texte |
| insomenia (VystormInsomnia) | 4 | ~20 | S–M | 2,5 h | Texte mit `&`-Farbcodes |
| SpawnDirector | 1 | ~28 | M | 3 h | |
| vystorm-strata-paper (VystormStrata) | 1 | 17 | M | 3 h | nur de/en – zh_cn fehlt noch |
| vystorm-worlds (VystormWorlds) | 1 | ~21 | M | 4 h | reiche Tab-Vervollständigung (Welten, Generatoren, `key:`-Tokens) als eigene `Suggester`; unbekannter Unterbefehl = Weltname → Wurzel-Argument |
| BlockIntegrations | 1 | 20 | M | 4 h | Dispatch in 1300-Zeilen-Klasse |
| DeepInventory | 1 | ~47 | M | 4 h | viele kleine Fälle |
| Vystorm-Factions | 1 | 27 | M | 4 h | WorldDirector leitet `/worlddirector factions …` weiter: nach Umstellung über `CommandRegistration.execute` |
| XPVaultEconomy | 4 | 20 | M | 4 h | Befehle in 2000-Zeilen-Klasse |
| vystorm-build (VystormBuild) | 1 | 29 | M | 4 h | |
| vystorm-dangerous-sky (VystormDangerousSky) | 1 | 43 | M | 4 h | |
| vystorm-dangerous-underground (VystormDangerousUnderground) | 1 | 54 | M | 4 h | |
| vystorm-estates (VystormEstates) | 1 | 21 | M | 4 h | Admin-Varianten, Menü nutzt dieselben Lambdas |
| vystorm-items (VystormItems) | 1 | ~35 | M | 4 h | eigenes `SubCommand`-Modulsystem passt fast 1:1 auf `sub(...)` |
| vystorm-parties (VystormParties) | 2 | 35 | M | 4 h | |
| vystorm-pets (VystormPets) | 1 | 30 | M | 4 h | Wrapper `PetRootCommand` entfällt |
| WorldDirector | 2 | ~40 | M | 5 h | eigener Router auf fremde Plugins (`DirectorContentCommands`) – mit `CoreCommands.find(...).execute(...)` ersetzen |
| vystorm-skills (VystormSkills) | 3 | ~40 | M | 5 h | noch fest kodierte deutsche Texte in `/talente` |
| vystorm-npc (VystormNPC) | 1 | 26 | M | 5 h | keine Sprachdateien und nur softdepend: erst Lang einführen |
| vystorm-mobs (VystormMobs) | 6 | ~35 | M–L | 6 h | sechs Wurzeln in mehreren Diensten |
| VystormCityClaim | 1 | ~78 | L | 8 h | meiste Unterbefehle, dicht gepackt; Adapter zuerst sinnvoll |
| VystormMarket | 6 | ~45 | L | 8 h | sechs Wurzeln in getrennten Modulen |
| Arena | 1 | ~40 | L | 9 h | fünf Wrapper-Schichten (`Arena*RootWrapper`) – zuerst `adopt`, dann Schicht für Schicht |
| Vystorm-WorldForge | 5 | ~75 | L | 12 h | eingebetteter WorldDirector, Admin-Panel/Web rufen `onCommand` direkt auf → auf `CommandRegistration.execute` umstellen |

Summe der offenen Plugins: rund **140 Stunden** (Summe der Tabelle: 137,5 h) für Stufe 2. Stufe 0 (nur `description`-Texte für `/vhelp`) geht für
alle zusammen in etwa 2–3 Stunden, Stufe 1 (Adapter) für die großen L-Plugins in je 2–3 Stunden.

## Empfohlene Reihenfolge

1. Stufe 0 für alle Plugins in einem Rutsch (übersetzte `/vhelp`-Liste sofort).
2. Die S-Plugins vollständig (zusammen ~13 h) – kleiner Aufwand, viele Spieler-Befehle.
3. Die Spieler-nahen M-Plugins: parties, pets, worlds, skills, estates, items.
4. Die L-Plugins erst per `adopt`, dann knotenweise.
5. Router (WorldDirector, WorldForge) zuletzt, damit sie gleich auf `CommandRegistration.execute` umziehen.

## Offene Punkte für die Entscheidung

- Standard für `commands.localized-aliases`: derzeit `all`. Alternative `default` (nur Serversprache) hält die
  Befehlsliste schlanker, deutsche Spieler auf einem englischen Server sähen dann aber kein `/schienen`.
- Kurze deutsche Haupt-Aliase wie `/stumm`, `/kanal`, `/langsam` können mit Fremd-Plugins kollidieren; Core überspringt sie
  dann – `/vadmin commands` zeigt es.
- Kanal-Kurzbefehle von Vystorm Chat (`/g`, `/l` aus der Kanal-Konfiguration) bleiben bewusst außerhalb des Baums.
