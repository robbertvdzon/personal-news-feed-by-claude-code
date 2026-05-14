#!/usr/bin/env bash
#
# Maak een screenshot van URL en sla 'm op in OUTPUT (PNG). Wrapper
# rond `npx playwright screenshot` met defaults voor de Flutter-app:
#   * viewport 1280×800 (kun je overschrijven via 3e arg "WxH")
#   * wait-for-timeout 5000ms — geeft hydratie tijd om te settelen
#   * --full-page voor langere routes/feeds
#
# Bedoeld om door de tester-agent vanuit Claude's Bash-tool aangeroepen
# te worden. Sla het resultaat in /tmp/screenshots/ — alles in die map
# wordt aan het einde van de tester-run als attachment aan de JIRA-
# story toegevoegd.
#
# Voorbeelden:
#   take-screenshot.sh "$PREVIEW_URL" /tmp/screenshots/home.png
#   take-screenshot.sh "$PREVIEW_URL/feed" /tmp/screenshots/feed.png 1024x768
#
set -euo pipefail

URL="${1:?usage: take-screenshot.sh URL OUTPUT.png [WIDTHxHEIGHT]}"
OUTPUT="${2:?usage: take-screenshot.sh URL OUTPUT.png [WIDTHxHEIGHT]}"
VIEWPORT="${3:-1280x800}"

mkdir -p "$(dirname "$OUTPUT")"
echo "[take-screenshot] $URL → $OUTPUT (viewport $VIEWPORT)"

# Note: --viewport-size accepteert WxH ('1280x800'). --wait-for-timeout
# is een hard wait nadat de page geladen is — voor Flutter is dat veiliger
# dan networkidle (web-sockets / streaming-renderers houden idle uit).
npx playwright screenshot \
    --browser=chromium \
    --viewport-size="$VIEWPORT" \
    --wait-for-timeout=5000 \
    --full-page \
    "$URL" "$OUTPUT"

bytes=$(stat -c '%s' "$OUTPUT" 2>/dev/null || wc -c < "$OUTPUT")
echo "[take-screenshot] done: ${bytes} bytes"
