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
startup_settle_seconds="${NEBULA_TV_PERF_STARTUP_SETTLE_SECONDS:-2}"
settle_seconds="${NEBULA_TV_PERF_SETTLE_SECONDS:-2}"
logcat_lines="${NEBULA_TV_PERF_LOGCAT_LINES:-2000}"
diagnostic_lines="${NEBULA_TV_PERF_DIAGNOSTIC_LINES:-160}"
command_timeout_seconds="${NEBULA_TV_PERF_COMMAND_TIMEOUT_SECONDS:-15}"

fail() {
  echo "$*" >&2
  exit 2
}

require_integer_between() {
  local name="$1"
  local value="$2"
  local minimum="$3"
  local maximum="$4"
  if ! [[ "$value" =~ ^[0-9]+$ ]] || (( value < minimum || value > maximum )); then
    fail "$name must be an integer between $minimum and $maximum."
  fi
}

require_seconds() {
  local name="$1"
  local value="$2"
  if ! [[ "$value" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    fail "$name must be a non-negative number of seconds."
  fi
}

if ! [[ "$label" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,79}$ ]]; then
  fail "NEBULA_TV_PERF_LABEL must be 1-80 path-safe characters."
fi
require_seconds "NEBULA_TV_PERF_KEY_DELAY_SECONDS" "$key_delay_seconds"
require_seconds "NEBULA_TV_PERF_STARTUP_SETTLE_SECONDS" "$startup_settle_seconds"
require_seconds "NEBULA_TV_PERF_SETTLE_SECONDS" "$settle_seconds"
require_integer_between "NEBULA_TV_PERF_LOGCAT_LINES" "$logcat_lines" 1 10000
require_integer_between "NEBULA_TV_PERF_DIAGNOSTIC_LINES" "$diagnostic_lines" 1 160
require_integer_between \
  "NEBULA_TV_PERF_COMMAND_TIMEOUT_SECONDS" "$command_timeout_seconds" 1 120

if [ -e "$run_dir" ]; then
  fail "Refusing to overwrite the existing performance capture at $run_dir."
fi
mkdir -p "$run_dir"

adb_binary="${ADB:-adb}"
if ! command -v "$adb_binary" >/dev/null 2>&1; then
  fail "adb is unavailable. Set ADB to its executable path or add it to PATH."
fi
adb_cmd=("$adb_binary" -s "$serial")

run_with_timeout() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "${command_timeout_seconds}s" "$@"
  else
    "$@"
  fi
}

# Keep dumpsys and command failures useful without letting one vendor service produce an
# unbounded artifact. A command failure is recorded in its own file and does not erase the rest
# of the capture.
capture_bounded() {
  local output="$1"
  local maximum_bytes="$2"
  shift 2
  local partial="${output}.partial"
  local status=0
  if run_with_timeout "$@" > "$partial" 2>&1; then
    status=0
  else
    status=$?
  fi
  local original_bytes
  original_bytes="$(wc -c < "$partial")"
  head -c "$maximum_bytes" "$partial" > "$output"
  if (( original_bytes > maximum_bytes )); then
    printf '\n[truncated from %s to %s bytes]\n' "$original_bytes" "$maximum_bytes" >> "$output"
  fi
  if (( status != 0 )); then
    printf '\n[command exited with status %s]\n' "$status" >> "$output"
  fi
  rm -f -- "$partial"
}

device_property() {
  local value=""
  value="$("${adb_cmd[@]}" shell getprop "$1" 2>/dev/null | tr -d '\r')" || true
  printf '%s' "${value:-unknown}"
}

press_key() {
  "${adb_cmd[@]}" shell input keyevent "$1"
  sleep "$key_delay_seconds"
}

"${adb_cmd[@]}" wait-for-device
if ! "${adb_cmd[@]}" get-state >/dev/null; then
  fail "The selected Android device is not ready."
fi

source_status="$(git -C "$repo_root" status --porcelain=v1 --untracked-files=all)"
source_worktree_dirty=false
if [ -n "$source_status" ]; then
  source_worktree_dirty=true
fi
{
  echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "source_commit=$(git -C "$repo_root" rev-parse HEAD)"
  echo "source_describe=$(git -C "$repo_root" describe --always --dirty)"
  echo "source_worktree_dirty=$source_worktree_dirty"
  echo "source_status_sha256=$(printf '%s' "$source_status" | sha256sum | cut -d' ' -f1)"
  echo "capture_script=scripts/capture-tv-performance.sh"
} > "$run_dir/build.txt"

{
  echo "manufacturer=$(device_property ro.product.manufacturer)"
  echo "model=$(device_property ro.product.model)"
  echo "device=$(device_property ro.product.device)"
  echo "product=$(device_property ro.product.name)"
  echo "android_release=$(device_property ro.build.version.release)"
  echo "android_api=$(device_property ro.build.version.sdk)"
  echo "security_patch=$(device_property ro.build.version.security_patch)"
  echo "build_fingerprint=$(device_property ro.build.fingerprint)"
  echo "supported_abis=$(device_property ro.product.cpu.abilist)"
  echo "primary_abi=$(device_property ro.product.cpu.abi)"
  echo "hardware=$(device_property ro.hardware)"
  printf 'kernel='
  "${adb_cmd[@]}" shell uname -a | tr -d '\r'
} > "$run_dir/device.txt"

"${adb_cmd[@]}" shell input keyevent KEYCODE_WAKEUP || true
"${adb_cmd[@]}" shell am force-stop "$package_name"
"${adb_cmd[@]}" shell dumpsys gfxinfo "$package_name" reset || true
"${adb_cmd[@]}" logcat -c || true
"${adb_cmd[@]}" shell am start -W -n "$package_name/.TvAppActivity" > "$run_dir/launch.txt"
sleep "$startup_settle_seconds"

# This is intentionally the same sustained path used by NavigationBenchmark. On a device with the
# documented TMDB/addon fixture it measures real rails; on a credential-free install it still
# captures setup/focus behavior rather than pretending that an empty page is catalog navigation.
for _ in $(seq 1 12); do press_key KEYCODE_DPAD_RIGHT; done
for _ in $(seq 1 5); do
  press_key KEYCODE_DPAD_DOWN
  for _ in $(seq 1 6); do press_key KEYCODE_DPAD_RIGHT; done
done
for _ in $(seq 1 4); do press_key KEYCODE_DPAD_LEFT; done

sleep "$settle_seconds"

app_pid="$("${adb_cmd[@]}" shell pidof "$package_name" 2>/dev/null | tr -d '\r')" || true
app_paths="$("${adb_cmd[@]}" shell pm path "$package_name" 2>/dev/null | tr -d '\r')" || true
{
  echo "package=$package_name"
  echo "process_id=${app_pid:-unavailable}"
  printf 'installed_apk_paths=%s\n' "${app_paths//$'\n'/;}"
  "${adb_cmd[@]}" shell dumpsys package "$package_name" 2>/dev/null |
    tr -d '\r' |
    awk '
      /versionCode=|versionName=|firstInstallTime=|lastUpdateTime=|primaryCpuAbi=|secondaryCpuAbi=|flags=\[|privateFlags=\[/ {
        if (printed < 24) print
        printed++
      }
    '
} > "$run_dir/app.txt"

capture_bounded "$run_dir/gfxinfo.txt" 1048576 \
  "${adb_cmd[@]}" shell dumpsys gfxinfo "$package_name"
capture_bounded "$run_dir/meminfo.txt" 1048576 \
  "${adb_cmd[@]}" shell dumpsys meminfo "$package_name"
capture_bounded "$run_dir/display.txt" 524288 \
  "${adb_cmd[@]}" shell dumpsys display
capture_bounded "$run_dir/window.txt" 262144 \
  "${adb_cmd[@]}" shell dumpsys window displays
capture_bounded "$run_dir/thermal.txt" 262144 \
  "${adb_cmd[@]}" shell dumpsys thermalservice
capture_bounded "$run_dir/battery.txt" 65536 \
  "${adb_cmd[@]}" shell dumpsys battery
capture_bounded "$run_dir/exit-info.txt" 262144 \
  "${adb_cmd[@]}" shell dumpsys activity exit-info "$package_name"

# These are the app's real logcat tags. NebulaDiagnostics intentionally writes only to the
# private, redacted ring captured below and never emits a logcat tag of its own.
capture_bounded "$run_dir/logcat.txt" 1048576 \
  "${adb_cmd[@]}" logcat -d -v threadtime -t "$logcat_lines" -s \
  TvFocus:I TvHttp:I TvPersistence:V WatchNext:V \
  StremioHostUpdateWorker:V AndroidRuntime:E '*:S'

private_diagnostics="unavailable"
private_error="$run_dir/private-events.error"
if "${adb_cmd[@]}" exec-out run-as "$package_name" \
  tail -n "$diagnostic_lines" no_backup/diagnostics/events.log \
  > "$run_dir/private-events.txt" 2> "$private_error"; then
  private_diagnostics="captured"
  if [ ! -s "$run_dir/private-events.txt" ]; then
    echo "No private diagnostic events were recorded." > "$run_dir/private-events.txt"
  fi
else
  {
    echo "Private diagnostic events are unavailable."
    echo "Android run-as permits this read only for a debuggable installed build."
    head -c 2048 "$private_error"
  } > "$run_dir/private-events.txt"
fi
rm -f -- "$private_error"

cat > "$run_dir/README.txt" <<EOF
package=$package_name
serial=$serial
label=$label
path=12 right, 5 down plus 6 right, 4 left
startup_settle_seconds=$startup_settle_seconds
key_delay_seconds=$key_delay_seconds
settle_seconds=$settle_seconds
logcat_lines=$logcat_lines
private_diagnostic_lines=$diagnostic_lines
private_diagnostics=$private_diagnostics
command_timeout_seconds=$command_timeout_seconds
artifacts=app.txt battery.txt build.txt device.txt display.txt exit-info.txt gfxinfo.txt launch.txt logcat.txt meminfo.txt private-events.txt thermal.txt window.txt
EOF

echo "Performance capture written to $run_dir"
