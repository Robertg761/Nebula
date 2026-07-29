#!/usr/bin/env bash
set -euo pipefail

api="${1:-}"
if [ "$api" != "26" ] && [ "$api" != "34" ]; then
  echo "Usage: $0 <26|34>" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
android_project="$repo_root/apps/android-tv-host"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk_root" ]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK." >&2
  exit 2
fi

adb="$sdk_root/platform-tools/adb"
avdmanager="$sdk_root/cmdline-tools/latest/bin/avdmanager"
emulator="$sdk_root/emulator/emulator"
for executable in "$adb" "$avdmanager" "$emulator"; do
  if [ ! -x "$executable" ]; then
    echo "Required Android SDK executable is missing: $executable" >&2
    exit 2
  fi
done

image="system-images;android-${api};android-tv;x86"
image_dir="$sdk_root/system-images/android-${api}/android-tv/x86"
if [ ! -f "$image_dir/package.xml" ]; then
  echo "Missing Android TV image: $image" >&2
  echo "Install it with: $sdk_root/cmdline-tools/latest/bin/sdkmanager \"$image\"" >&2
  exit 2
fi

artifact_dir="${NEBULA_TV_TEST_ARTIFACT_DIR:-$android_project/app/build/outputs/tv-emulator}"
mkdir -p "$artifact_dir"
emulator_log="$artifact_dir/api-${api}-emulator.log"
logcat_file="$artifact_dir/api-${api}-logcat.txt"
: > "$emulator_log"
: > "$logcat_file"

avd_home="$(mktemp -d -t "nebula-tv-${api}-avd.XXXXXX")"
export ANDROID_AVD_HOME="$avd_home"
avd_name="nebula_tv_${api}"
emulator_pid=""
serial=""

cleanup() {
  status=$?
  trap - EXIT INT TERM

  if [ -n "$serial" ] && "$adb" -s "$serial" get-state >/dev/null 2>&1; then
    "$adb" -s "$serial" logcat -d -v threadtime > "$logcat_file" 2>/dev/null || true
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
  if [ -n "$emulator_pid" ]; then
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      if ! kill -0 "$emulator_pid" 2>/dev/null; then
        break
      fi
      sleep 1
    done
    if kill -0 "$emulator_pid" 2>/dev/null; then
      kill "$emulator_pid" 2>/dev/null || true
    fi
    wait "$emulator_pid" 2>/dev/null || true
  fi
  rm -r -- "$avd_home" || true

  echo "Emulator log: $emulator_log"
  echo "Logcat: $logcat_file"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'no\n' |
  "$avdmanager" create avd \
    --force \
    --name "$avd_name" \
    --package "$image" \
    --device tv_1080p >/dev/null

for port in $(seq 5554 2 5584); do
  candidate="emulator-$port"
  if ! "$adb" devices | awk 'NR > 1 {print $1}' | grep -Fxq "$candidate"; then
    serial="$candidate"
    break
  fi
done
if [ -z "$serial" ]; then
  echo "No free emulator console port was found." >&2
  exit 1
fi

# Emulator 36.6 currently crashes during Android TV boot with its bundled
# SwiftShader on Linux. Host mode is also the renderer used by the repository's
# existing local TV procedure and remains overridable for other hosts.
gpu_mode="${NEBULA_EMULATOR_GPU:-host}"
"$emulator" \
  -avd "$avd_name" \
  -port "${serial#emulator-}" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -no-metrics \
  -gpu "$gpu_mode" \
  > "$emulator_log" 2>&1 &
emulator_pid=$!

boot_deadline=$((SECONDS + 300))
while true; do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo "Android TV emulator exited before boot completed." >&2
    tail -n 100 "$emulator_log" >&2
    exit 1
  fi

  boot_completed="$(
    "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null |
      tr -d '\r' ||
      true
  )"
  if [ "$boot_completed" = "1" ]; then
    break
  fi
  if [ "$SECONDS" -ge "$boot_deadline" ]; then
    echo "Timed out waiting for $serial to boot." >&2
    tail -n 100 "$emulator_log" >&2
    exit 1
  fi
  sleep 2
done

"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0
"$adb" -s "$serial" shell input keyevent KEYCODE_WAKEUP || true
# Some API 26 TV images deny clearing one of the platform-owned buffers. A
# failed clear must not prevent tests; the captured artifact is still bounded
# to this fresh AVD's lifetime.
"$adb" -s "$serial" logcat -c || true

echo "Running credential-free instrumentation on $serial (Android TV API $api)."
(
  cd "$android_project"
  ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest --console=plain
)
