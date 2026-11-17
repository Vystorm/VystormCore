#!/usr/bin/env bash
# Builds vystorm-web-ui and stores its dist/ as webui/vystorm-web-ui-dist.zip (bundled into Core's jar as the default
# web UI under web/bundled-ui/). Core's Gradle build needs no npm; run this script whenever the web UI changes and commit
# the zip together with webui/web-ui.properties.
#
#   scripts/update-web-ui.sh [path/to/vystorm-web-ui] [--no-build]
#
# Default path: $VYSTORM_WEB_UI or ../../MythicPluginsBuild/vystorm-web-ui (relative to this repo) or
# ~/MythicPluginsBuild/vystorm-web-ui. `npm run build` includes the CSP check (scripts/check-dist.mjs).
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
ui="${VYSTORM_WEB_UI:-}"
build=1
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    *) ui="$arg" ;;
  esac
done
if [ -z "$ui" ]; then
  for candidate in "$repo/../../MythicPluginsBuild/vystorm-web-ui" "$HOME/MythicPluginsBuild/vystorm-web-ui"; do
    if [ -f "$candidate/package.json" ]; then ui="$candidate"; break; fi
  done
fi
if [ -z "$ui" ] || [ ! -f "$ui/package.json" ]; then
  echo "vystorm-web-ui not found; pass its path or set VYSTORM_WEB_UI" >&2
  exit 1
fi
ui="$(cd "$ui" && pwd)"

if [ "$build" = 1 ]; then
  (cd "$ui" && { [ -d node_modules ] || npm ci; } && npm run build)
fi
[ -f "$ui/dist/index.html" ] || { echo "$ui/dist/index.html missing (build first)" >&2; exit 1; }

version="$(node -p "require('$ui/package.json').version")"
commit="$(git -C "$ui" rev-parse --short HEAD 2>/dev/null || echo unknown)"
dirty="$(git -C "$ui" status --porcelain 2>/dev/null | head -c1)"
[ -n "$dirty" ] && commit="$commit-dirty"

# Deterministic zip: sorted entries, fixed timestamps (identical dist -> identical bytes).
python3 - "$ui/dist" "$repo/webui/vystorm-web-ui-dist.zip" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
files = []
for root, dirs, names in os.walk(src):
    dirs.sort()
    for name in sorted(names):
        path = os.path.join(root, name)
        if os.path.islink(path) or name.startswith('.'):
            continue
        files.append(os.path.relpath(path, src).replace(os.sep, '/'))
tmp = out + '.tmp'
with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for rel in sorted(files):
        info = zipfile.ZipInfo(rel, date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(os.path.join(src, rel), 'rb') as f:
            z.writestr(info, f.read())
os.replace(tmp, out)
print(f'{out}: {len(files)} files, {os.path.getsize(out)} bytes')
PY

cat > "$repo/webui/web-ui.properties" <<PROPS
# Written by scripts/update-web-ui.sh; bundled as web/bundled-ui.properties
version=$version
commit=$commit
PROPS
echo "bundled vystorm-web-ui $version ($commit)"
