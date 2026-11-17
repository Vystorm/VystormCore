# Vystorm Core – Anleitung

Öffne `/vmenu`, wähle **Tutorials** und danach **Vystorm Core · Anleitung**. Direkt geht es mit `/vmenu tutorials`.

## Einstieg mit `/vmenu`

1. Gib `/vmenu` im Chat ein.
2. Wähle **Tutorials**.
3. Wähle **Pluginname · Anleitung** und danach ein Thema.
4. „Alle Tutorials“ bringt dich zur Plugin-Auswahl; dort führt „Zurück zu /vmenu“ zum Hauptmenü.

Core verbindet die Bedienung der eigenen Plugins. Inventare mit echten Items bleiben Inventare; Eingaben, Bestätigungen und Anleitungen öffnen sich als Minecraft-Dialoge.

## Anleitungen finden

1. Öffne `/vmenu` und wähle **Tutorials**.
2. Wähle „Pluginname · Anleitung“.
3. Wähle ein Thema. „Weiter“ und „Zurück“ blättern; „Alle Themen“ öffnet die Übersicht.

Mit `/vmenu list` siehst du verfügbare Menü-Schlüssel. Einen Schlüssel kannst du direkt hinter `/vmenu` setzen, zum Beispiel `/vmenu deepinventory:tutorial`. Die Tab-Taste hilft bei der Auswahl.

## Dialoge bedienen

Fülle bei Eingaben die beschrifteten Felder aus und drücke „Übernehmen“ oder den angezeigten Aktionsknopf. Lies bei Bestätigungen Ziel und Kosten, bevor du bestätigst; „Abbrechen“ führt die Aktion nicht aus. Anleitungsdialoge blättern nur und kaufen, bauen oder verändern nichts.

Dialoge laufen nach ungefähr einer Minute ab. Öffne das Menü erneut, wenn eine alte Antwort nicht mehr angenommen wird. Zwischenzeitlich geänderte Rechte, Bestände oder Ziele können eine Aktion ebenfalls ungültig machen.

## Wenn etwas nicht verfügbar ist

Fehlt ein Eintrag, prüfe zuerst `/vmenu list`. Ein Plugin kann abgeschaltet sein oder dir kann die Berechtigung fehlen. Lies bei einer abgelehnten Aktion die Chatmeldung und das passende Tutorial: Häufig fehlen Platz, Guthaben, ein vollständiger Aufbau oder Zugriff auf das Ziel.

Hat sich seit dem Öffnen etwas geändert, öffne die Ansicht erneut. Bleibt der Fehler, melde dem Serverteam Plugin, Aktion und genauen Meldungstext.

## Entwickler: eigenes Tutorial registrieren

Lege `tutorial.yml` als Plugin-Ressource unter `src/main/resources/` ab und registriere sie nach dem Laden des Core über `api.registerTutorial(this);`. Beispielstruktur:

```yaml
title: Mein Plugin
permission: meinplugin.use
pages:
  - title: Einstieg
    permission: meinplugin.special # optional; Thema nur mit dieser Berechtigung sichtbar
    text: |
      Beschreibung und Schritte für dieses Thema.
```

Die Anleitung benötigt `title`; jedes Thema benötigt `title` und `text`. Die globale `permission` ist optional und schützt den gesamten Eintrag; eine Berechtigung pro Thema ist ebenfalls optional. Beim Deaktivieren des Plugins entfernt Cores vorhandener Disable-Listener die registrierten Menüs und offenen Dialoge über `api.releaseAdvancedResources(owner)`. Für die Anleitung ist kein eigener Listener oder regelmäßiger Task nötig.
