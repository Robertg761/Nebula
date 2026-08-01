#!/usr/bin/env bash
# Installs a build and configures the TV test fixture on it, end to end:
# install -r, open the pairing screen, read its one-time URL out of the
# accessibility tree, POST the credentials from ~/.config/nebula/credentials.env,
# and verify Home actually shows a rail before returning.
#
# Exists because connected-test cleanup uninstalls the app - and with it the
# private DataStore holding the fixture credentials - so every benchmark or
# profile run otherwise starts from a factory-fresh setup screen. Running this
# first, with the same variant APK the connected run will install, works
# because `install -r` over an existing install preserves app data.
#
# Usage: ANDROID_SERIAL=<serial> scripts/seed-tv-fixture.sh <apk-path>
set -euo pipefail

APK="${1:?usage: seed-tv-fixture.sh <apk-path>}"
CREDS="$HOME/.config/nebula/credentials.env"
COMPONENT="com.stremioshell.host.tv/com.stremioshell.host.tv.TvAppActivity"

if [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "Set ANDROID_SERIAL to the fixture device." >&2
  exit 1
fi
if [ ! -f "$CREDS" ]; then
  echo "Missing $CREDS (TMDB_API_KEY / COMET_ADDON_MANIFEST_URL)." >&2
  exit 1
fi
# shellcheck disable=SC1090
set -a; source "$CREDS"; set +a
: "${TMDB_API_KEY:?not set in credentials.env}"
: "${COMET_ADDON_MANIFEST_URL:?not set in credentials.env}"

ADB=(adb -s "$ANDROID_SERIAL")
"${ADB[@]}" get-state >/dev/null

ui_dump() {
  "${ADB[@]}" shell uiautomator dump /sdcard/seed-fixture-ui.xml >/dev/null 2>&1
  "${ADB[@]}" shell cat /sdcard/seed-fixture-ui.xml
}

rails_visible() { ui_dump | grep -q 'text="Trending Movies"'; }

"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP
"${ADB[@]}" install -r "$APK" >/dev/null
"${ADB[@]}" shell am force-stop com.stremioshell.host.tv
"${ADB[@]}" shell am start -n "$COMPONENT" >/dev/null
sleep 8

if rails_visible; then
  echo "Fixture already configured; rails visible."
  exit 0
fi

# The setup screen focuses "Set up with phone"; OK opens pairing and its URL
# carries the one-shot token.
"${ADB[@]}" shell input keyevent KEYCODE_DPAD_CENTER
sleep 4
PAIR="$(ui_dump | grep -oE 'http://[0-9.]+:[0-9]+/\?t=[A-Za-z0-9]+' | head -1)"
if [ -z "$PAIR" ]; then
  echo "Pairing URL not found on screen; is the app on its setup screen?" >&2
  exit 1
fi
TOKEN="${PAIR##*t=}"
HOSTPORT="${PAIR%%/\?t=*}"
CODE="$(curl -sS -o /dev/null -w '%{http_code}' "$HOSTPORT/config?t=$TOKEN" \
  --data-urlencode "tmdb=$TMDB_API_KEY" \
  --data-urlencode "addon=$COMET_ADDON_MANIFEST_URL")"
if [ "$CODE" != "200" ]; then
  echo "Pairing POST failed with HTTP $CODE." >&2
  exit 1
fi
sleep 3
# Continue past the confirmation, then give the rails one fetch's worth of time.
"${ADB[@]}" shell input keyevent KEYCODE_DPAD_CENTER
for _ in 1 2 3 4 5 6; do
  sleep 3
  if rails_visible; then
    echo "Fixture configured; rails visible."
    exit 0
  fi
done
echo "Config was accepted but Home never showed a rail; check the addon/network." >&2
exit 1
