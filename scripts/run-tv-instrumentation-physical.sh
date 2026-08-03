#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(CDPATH='' cd -- "$script_dir/.." && pwd)"
android_project="$repo_root/apps/android-tv-host"
package_name="com.stremioshell.host.tv"
backup_argument="nebula.externalDataStoreBackupCreated"
debug_apk="$android_project/app/build/outputs/apk/debug/app-debug.apk"
backup_dir=""
restore_needed=0

usage() {
  cat >&2 <<'EOF'
Usage:
  ANDROID_SERIAL=<serial> NEBULA_PHYSICAL_TV_TEST_CONFIRMED=1 \
    ./scripts/run-tv-instrumentation-physical.sh

  ANDROID_SERIAL=<serial> \
    ./scripts/run-tv-instrumentation-physical.sh --restore <backup-directory>

The normal mode is intentionally physical-device-only. If Nebula is already installed, it must
be a debuggable build so the wrapper can snapshot its private DataStore before any test runs.
EOF
}

fail() {
  echo "$*" >&2
  exit 1
}

resolve_adb() {
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$sdk_root" ] && [ -x "$sdk_root/platform-tools/adb" ]; then
    printf '%s\n' "$sdk_root/platform-tools/adb"
    return
  fi
  command -v adb || return 1
}

adb_bin="$(resolve_adb)" || fail "adb is not available; source scripts/android-env.sh first."
serial="${ANDROID_SERIAL:-}"
[ -n "$serial" ] || fail "Set ANDROID_SERIAL to exactly one physical TV."
adb_cmd=("$adb_bin" -s "$serial")

device_is_online() {
  [ "$("${adb_cmd[@]}" get-state 2>/dev/null || true)" = "device" ]
}

wait_for_reconnect() {
  local _
  for _ in $(seq 1 30); do
    if device_is_online; then
      return 0
    fi
    sleep 2
  done
  return 1
}

package_is_installed() {
  "${adb_cmd[@]}" shell pm path "$package_name" 2>/dev/null | grep -q '^package:'
}

package_is_debuggable() {
  "${adb_cmd[@]}" shell run-as "$package_name" true >/dev/null 2>&1
}

validate_archive() {
  local archive="$1"
  local entry
  local listing
  local verbose_listing
  local -a entries=()
  local -a verbose_entries=()
  local index
  local type
  local regular_file_found=0
  [ -s "$archive" ] || return 1
  # A process-substitution failure would be invisible to the loop's exit status. Capture the
  # listing first so a truncated/corrupt tar cannot be accepted as the only recovery copy.
  listing="$(tar -tf "$archive")" || return 1
  verbose_listing="$(tar -tvf "$archive")" || return 1
  [ -n "$listing" ] || return 1
  mapfile -t entries <<< "$listing"
  mapfile -t verbose_entries <<< "$verbose_listing"
  [ "${#entries[@]}" -eq "${#verbose_entries[@]}" ] || return 1
  for index in "${!entries[@]}"; do
    entry="${entries[$index]}"
    type="${verbose_entries[$index]:0:1}"
    case "/$entry/" in
      */../*|*/./*) return 1 ;;
    esac
    case "$entry" in
      files/datastore|files/datastore/|files/datastore/*) ;;
      *) return 1 ;;
    esac
    if [[ "$entry" == */ ]]; then
      [ "$type" = "d" ] || return 1
    elif [[ "$entry" == files/datastore/* ]]; then
      [ "$type" = "-" ] || return 1
      regular_file_found=1
    fi
  done
  [ "$regular_file_found" -eq 1 ]
}

discard_backup_after_verified_restore() {
  local target="$1"
  if [ -f "$target/.nebula-managed-physical-tv-backup" ] &&
    [[ "$(basename -- "$target")" == run.* ]]; then
    rm -r -- "$target"
  else
    echo "Restore succeeded. Backup retained because it was not created by this wrapper: $target" >&2
  fi
}

restore_backup() {
  local target="$1"
  local archive="$target/datastore.tar"
  local recorded_serial=""
  local original_dir="$target/original"
  local restored_archive="$target/restored.tar"
  local restored_dir="$target/restored"

  [ -f "$target/device-serial" ] || {
    echo "Backup metadata is missing: $target/device-serial" >&2
    return 1
  }
  IFS= read -r recorded_serial < "$target/device-serial"
  [ "$recorded_serial" = "$serial" ] || {
    echo "Backup belongs to $recorded_serial, not ANDROID_SERIAL=$serial." >&2
    return 1
  }
  validate_archive "$archive" || {
    echo "Refusing invalid or empty DataStore backup: $archive" >&2
    return 1
  }
  wait_for_reconnect || {
    echo "The TV did not reconnect within 60 seconds." >&2
    return 1
  }

  if ! package_is_installed; then
    if [ ! -f "$debug_apk" ]; then
      echo "The target APK was removed and $debug_apk is unavailable." >&2
      echo "Assemble the debug APK, then rerun this script with --restore $target" >&2
      return 1
    fi
    "${adb_cmd[@]}" install -t "$debug_apk" >/dev/null || return 1
  fi
  package_is_debuggable || {
    echo "Installed Nebula package is not debuggable; cannot restore its private DataStore." >&2
    return 1
  }

  "${adb_cmd[@]}" shell am force-stop "$package_name" >/dev/null
  # `shell -T` keeps stdin binary-clean. Overwrite the snapshotted DataStore files only after the
  # app is stopped; the archive stays on the host so an interrupted extraction is recoverable.
  "${adb_cmd[@]}" shell \
    "run-as $package_name sh -c 'if [ -e files/datastore ]; then rm -r files/datastore; fi'"
  "${adb_cmd[@]}" shell -T run-as "$package_name" tar -xf - < "$archive"
  "${adb_cmd[@]}" exec-out run-as "$package_name" tar -cf - files/datastore \
    > "$restored_archive"

  mkdir -p -- "$original_dir" "$restored_dir"
  tar -xf "$archive" -C "$original_dir"
  tar -xf "$restored_archive" -C "$restored_dir"
  if ! diff -qr -- "$original_dir/files/datastore" "$restored_dir/files/datastore" >/dev/null; then
    echo "DataStore restore verification failed; recovery files remain at $target" >&2
    return 1
  fi

  restore_needed=0
  echo "Verified external DataStore restore on $serial."
  discard_backup_after_verified_restore "$target"
}

restore_on_exit() {
  local status=$?
  trap - EXIT INT TERM

  if [ "$restore_needed" -eq 1 ]; then
    if ! restore_backup "$backup_dir"; then
      echo "Nebula's recovery snapshot is still available at: $backup_dir" >&2
      echo "After the TV reconnects, run:" >&2
      echo "  ANDROID_SERIAL=$serial $0 --restore $backup_dir" >&2
      status=1
    fi
  fi
  exit "$status"
}

if [ "${1:-}" = "--restore" ]; then
  [ "$#" -eq 2 ] || { usage; exit 2; }
  recovery_dir="$2"
  restore_backup "$recovery_dir"
  exit 0
fi
if [ "$#" -ne 0 ]; then
  usage
  exit 2
fi

[ "${NEBULA_PHYSICAL_TV_TEST_CONFIRMED:-}" = "1" ] || {
  echo "Refusing a physical-TV run without explicit confirmation." >&2
  echo "Set NEBULA_PHYSICAL_TV_TEST_CONFIRMED=1 after checking ANDROID_SERIAL." >&2
  exit 1
}
device_is_online || fail "ANDROID_SERIAL=$serial is not online."

is_qemu="$("${adb_cmd[@]}" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
[ "$is_qemu" != "1" ] || fail "Use scripts/run-tv-instrumentation.sh for isolated emulators."

if package_is_installed; then
  package_is_debuggable || fail \
    "Nebula is installed but not debuggable, so its private DataStore cannot be protected. Refusing to run."

  "${adb_cmd[@]}" shell am force-stop "$package_name" >/dev/null
  if "${adb_cmd[@]}" shell run-as "$package_name" test -d files/datastore >/dev/null 2>&1; then
    umask 077
    state_home="${XDG_STATE_HOME:-${HOME:?HOME is required}/.local/state}"
    backup_root="${NEBULA_TV_TEST_BACKUP_ROOT:-$state_home/nebula/physical-tv-test-backups}"
    mkdir -p -- "$backup_root"
    chmod 700 "$backup_root"
    backup_dir="$(mktemp -d "$backup_root/run.XXXXXX")"
    : > "$backup_dir/.nebula-managed-physical-tv-backup"
    printf '%s\n' "$serial" > "$backup_dir/device-serial"
    "${adb_cmd[@]}" exec-out run-as "$package_name" tar -cf - files/datastore \
      > "$backup_dir/datastore.tar"
    validate_archive "$backup_dir/datastore.tar" || fail \
      "External DataStore backup failed validation: $backup_dir/datastore.tar"
    restore_needed=1
    echo "Protected the installed DataStore outside the app sandbox: $backup_dir"
  else
    echo "Nebula has no DataStore directory yet; no personal app data needs snapshotting."
  fi
else
  echo "Nebula is not installed; no existing app-private state needs snapshotting."
fi

trap restore_on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Running guarded instrumentation on physical TV $serial."
(
  cd "$android_project"
  ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest --console=plain \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterTest=true \
    "-Pandroid.testInstrumentationRunnerArguments.$backup_argument=true"
)
