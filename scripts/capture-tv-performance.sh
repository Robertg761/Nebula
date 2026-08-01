#!/usr/bin/env bash
set -euo pipefail

package_name="com.stremioshell.host.tv"
serial="${ANDROID_SERIAL:-}"
if [ -z "$serial" ]; then
  echo "Set ANDROID_SERIAL to the physical TV or benchmark emulator." >&2
  exit 2
fi

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(CDPATH='' cd -- "$script_dir/.." && pwd)"
output_dir="${NEBULA_TV_PERF_ARTIFACT_DIR:-$repo_root/apps/android-tv-host/app/build/outputs/tv-performance}"
label="${NEBULA_TV_PERF_LABEL:-$(date -u +%Y%m%dT%H%M%SZ)}"
run_dir="$output_dir/$label"
key_delay_seconds="${NEBULA_TV_PERF_KEY_DELAY_SECONDS:-0.12}"
mkdir -p "$run_dir"

adb_cmd=(adb -s "$serial")
press_key() {
  "${adb_cmd[@]}" shell input keyevent "$1"
  sleep "$key_delay_seconds"
}

"${adb_cmd[@]}" wait-for-device
"${adb_cmd[@]}" shell input keyevent KEYCODE_WAKEUP || true
"${adb_cmd[@]}" shell am force-stop "$package_name"
"${adb_cmd[@]}" shell dumpsys gfxinfo "$package_name" reset || true
"${adb_cmd[@]}" logcat -c || true
"${adb_cmd[@]}" shell am start -W -n "$package_name/.TvAppActivity" > "$run_dir/launch.txt"
sleep "${NEBULA_TV_PERF_STARTUP_SETTLE_SECONDS:-2}"

# This is intentionally the same sustained path used by NavigationBenchmark. On a device with the
# documented TMDB/addon fixture it measures real rails; on a credential-free install it still
# captures setup/focus behavior rather than pretending that an empty page is catalog navigation.
for _ in $(seq 1 12); do press_key KEYCODE_DPAD_RIGHT; done
for _ in $(seq 1 5); do
  press_key KEYCODE_DPAD_DOWN
  for _ in $(seq 1 6); do press_key KEYCODE_DPAD_RIGHT; done
done
for _ in $(seq 1 4); do press_key KEYCODE_DPAD_LEFT; done

sleep "${NEBULA_TV_PERF_SETTLE_SECONDS:-2}"
"${adb_cmd[@]}" shell dumpsys gfxinfo "$package_name" > "$run_dir/gfxinfo.txt"
"${adb_cmd[@]}" shell dumpsys meminfo "$package_name" > "$run_dir/meminfo.txt"
"${adb_cmd[@]}" logcat -d -v threadtime -s TvFocus:W NebulaDiagnostics:D '*:S' > "$run_dir/logcat.txt" || true

cat > "$run_dir/README.txt" <<EOF
package=$package_name
serial=$serial
label=$label
path=12 right, 5 down plus 6 right, 4 left
startup_settle_seconds=${NEBULA_TV_PERF_STARTUP_SETTLE_SECONDS:-2}
key_delay_seconds=$key_delay_seconds
artifacts=gfxinfo.txt meminfo.txt logcat.txt launch.txt
EOF

echo "Performance capture written to $run_dir"
