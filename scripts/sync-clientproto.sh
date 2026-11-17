#!/bin/bash
# Kopiert den Protokoll-Code des Client-Mods nach web/internal/clientproto (Paket umbenannt) – dieselben Prüfklassen wie
# im Client (Schema, Patch, Aktionsprüfung). Dateien in clientproto, die nicht (mehr) in der Liste stehen, werden gelöscht.
#
#   ./scripts/sync-clientproto.sh [pfad-zum-vystorm-client]           kopieren
#   ./scripts/sync-clientproto.sh --check [pfad-zum-vystorm-client]   nur prüfen: Exit 1, wenn die Kopie abweicht
#                                                                     (Inhalt ohne Kopfzeile, fehlende oder alte Dateien)
# ClientProtoSyncTest ruft --check auf, wenn der Client-Checkout da ist (VYSTORM_CLIENT_DIR oder Standardpfad).
set -euo pipefail
CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
CLIENT="${1:-${VYSTORM_CLIENT_DIR:-$HOME/MythicPluginsBuild/vystorm-client}}"
SRC="$CLIENT/protocol/src/main/java/at/esoren/vystorm/client/protocol"
DST="$(cd "$(dirname "$0")/.." && pwd)/src/main/java/Vystorm/vystorm_core/web/internal/clientproto"
[ -d "$SRC" ] || { echo "client protocol sources not found: $SRC" >&2; exit 2; }
VER=$(grep '^version=' "$CLIENT/gradle.properties" | cut -d= -f2)
FILES="Protocol.java Wire.java ProtocolException.java TokenBucket.java LoginToken.java ModuleIds.java UiMessage.java WebMessage.java
ui/Anchor.java ui/Colors.java ui/ElementIds.java ui/Icon.java ui/ToastSpec.java ui/TooltipData.java ui/UiElement.java ui/UiLimits.java ui/UiSchema.java ui/UiText.java
panel/PanelLimits.java panel/PanelMessage.java panel/Deadline.java panel/Transfer.java panel/PanelIds.java panel/PanelColor.java
panel/PanelIcon.java panel/PanelNode.java panel/PanelDocument.java panel/PanelJson.java panel/PanelSchema.java panel/PanelPatch.java
panel/ActionCheck.java"

body() { sed 's/at\.esoren\.vystorm\.client\.protocol/Vystorm.vystorm_core.web.internal.clientproto/g' "$1"; }

drift=0
n=0
for f in $FILES; do
  if [ "$CHECK" = 1 ]; then
    if [ ! -f "$DST/$f" ] || ! diff -q <(body "$SRC/$f") <(tail -n +2 "$DST/$f") > /dev/null; then
      echo "out of sync: $f"
      drift=1
    fi
  else
    mkdir -p "$DST/$(dirname "$f")"
    { echo "// Kopie aus Vystorm/vystorm-client (protocol, $VER, $(basename "$f")). Nicht von Hand ändern, sondern dort ändern und neu kopieren."
      body "$SRC/$f"; } > "$DST/$f"
  fi
  n=$((n+1))
done

# Alte Dateien, die nicht mehr in der Liste stehen
while IFS= read -r existing; do
  rel="${existing#"$DST"/}"
  case " $(echo $FILES) " in
    *" $rel "*) ;;
    *) if [ "$CHECK" = 1 ]; then echo "stale file: $rel"; drift=1; else rm -- "$existing"; echo "deleted stale $rel"; fi ;;
  esac
done < <(find "$DST" -type f -name '*.java' | sort)

if [ "$CHECK" = 1 ]; then
  [ "$drift" = 0 ] && echo "clientproto in sync with client $VER ($n files)"
  exit "$drift"
fi
echo "synced $n files from client $VER"
