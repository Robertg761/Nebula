#!/usr/bin/env bash
set -euo pipefail

apk="${1:-}"
if [ -z "$apk" ]; then
  echo "Usage: $0 <signed-apk>" >&2
  exit 2
fi
if [ ! -f "$apk" ]; then
  echo "APK does not exist: $apk" >&2
  exit 2
fi

fail() {
  echo "$*" >&2
  exit 1
}

sdk_roots=()
for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
  if [ -n "$sdk_root" ] && [ -d "$sdk_root" ]; then
    sdk_roots+=("$sdk_root")
  fi
done

resolve_build_tool() {
  local tool_name="$1"
  local candidates=()
  local sdk_root
  for sdk_root in "${sdk_roots[@]}"; do
    while IFS= read -r candidate; do
      [ -x "$candidate" ] && candidates+=("$candidate")
    done < <(
      find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 \
        \( -type f -o -type l \) -name "$tool_name" -print 2>/dev/null
    )
  done
  if [ "${#candidates[@]}" -gt 0 ]; then
    printf '%s\n' "${candidates[@]}" | sort -V | tail -n 1
    return 0
  fi
  command -v "$tool_name" 2>/dev/null
}

resolve_readelf() {
  if [ -n "${LLVM_READELF:-}" ]; then
    [ -x "$LLVM_READELF" ] || fail "LLVM_READELF is not executable: $LLVM_READELF"
    printf '%s\n' "$LLVM_READELF"
    return 0
  fi

  local command_name
  for command_name in llvm-readelf readelf; do
    if command -v "$command_name" >/dev/null 2>&1; then
      command -v "$command_name"
      return 0
    fi
  done

  local candidates=()
  local sdk_root
  for sdk_root in "${sdk_roots[@]}"; do
    while IFS= read -r candidate; do
      [ -x "$candidate" ] && candidates+=("$candidate")
    done < <(
      find "$sdk_root/ndk" \
        \( -type f -o -type l \) \
        -path '*/toolchains/llvm/prebuilt/*/bin/llvm-readelf' \
        -print 2>/dev/null
    )
  done
  if [ "${#candidates[@]}" -gt 0 ]; then
    printf '%s\n' "${candidates[@]}" | sort -V | tail -n 1
    return 0
  fi
  return 1
}

apksigner="$(resolve_build_tool apksigner)" ||
  fail "Could not find apksigner in the Android SDK or PATH."
zipalign="$(resolve_build_tool zipalign)" ||
  fail "Could not find zipalign in the Android SDK or PATH."
readelf_tool="$(resolve_readelf)" ||
  fail "Could not find llvm-readelf or readelf in the Android NDK or PATH."

if ! "$apksigner" verify "$apk" >/dev/null; then
  fail "APK signature verification failed: $apk"
fi

zipalign_error=""
if ! zipalign_error="$("$zipalign" -c -P 16 4 "$apk" 2>&1)"; then
  printf '%s\n' "$zipalign_error" >&2
  if printf '%s' "$zipalign_error" | grep -q "invalid option.*P"; then
    fail "zipalign lacks 16 KB page checks. Install Android Build Tools 35.0.0 or newer."
  fi
  fail "APK native libraries are not zip-aligned for 16 KB pages: $apk"
fi

mapfile -t arm64_libraries < <(
  unzip -Z1 "$apk" |
    sed -n '/^lib\/arm64-v8a\/.*\.so$/p' |
    LC_ALL=C sort
)
if [ "${#arm64_libraries[@]}" -eq 0 ]; then
  fail "APK has no arm64-v8a shared libraries to verify: $apk"
fi

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

checked=0
for library in "${arm64_libraries[@]}"; do
  checked=$((checked + 1))
  extracted="$work_dir/$checked.so"
  if ! unzip -p "$apk" "$library" > "$extracted" || [ ! -s "$extracted" ]; then
    fail "Could not extract $library from $apk"
  fi

  elf_header="$("$readelf_tool" -hW "$extracted" 2>&1)" || {
    printf '%s\n' "$elf_header" >&2
    fail "$library is not a readable ELF shared object."
  }
  if ! printf '%s\n' "$elf_header" | grep -Eq 'Machine:[[:space:]]+AArch64'; then
    fail "$library is in arm64-v8a but its ELF machine is not AArch64."
  fi

  program_headers="$("$readelf_tool" -lW "$extracted" 2>&1)" || {
    printf '%s\n' "$program_headers" >&2
    fail "Could not read program headers from $library."
  }
  mapfile -t load_alignments < <(
    printf '%s\n' "$program_headers" | awk '$1 == "LOAD" { print $NF }'
  )
  if [ "${#load_alignments[@]}" -eq 0 ]; then
    fail "$library has no ELF LOAD segments."
  fi
  for alignment in "${load_alignments[@]}"; do
    if ! [[ "$alignment" =~ ^0x[0-9A-Fa-f]+$ ]]; then
      fail "$library has an unreadable LOAD alignment: $alignment"
    fi
    alignment_value=$((alignment))
    if (( alignment_value < 16384 || alignment_value % 16384 != 0 )); then
      fail "$library has LOAD alignment $alignment; every segment needs at least 0x4000."
    fi
  done
done

echo "Verified signed APK 16 KB zip alignment and $checked arm64 ELF libraries: $apk"
