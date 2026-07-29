#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)"
ANDROID_PROJECT="$REPO_ROOT/apps/android-tv-host"
CANDIDATE_DIR="$ANDROID_PROJECT/app/build/baseline-profile-candidate"
SOURCE_STATUS="$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all)"
SOURCE_WORKTREE_DIRTY=false
if [ -n "$SOURCE_STATUS" ]; then
  SOURCE_WORKTREE_DIRTY=true
fi
SOURCE_STATUS_SHA256="$(
  printf '%s' "$SOURCE_STATUS" |
    sha256sum |
    cut -d' ' -f1
)"

if [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "Set ANDROID_SERIAL to one dedicated API 33+ Android TV device." >&2
  exit 1
fi
if [ "${NEBULA_PROFILE_FIXTURE_CONFIRMED:-}" != "1" ]; then
  echo "Set NEBULA_PROFILE_FIXTURE_CONFIRMED=1 only after the device has the documented test fixture." >&2
  exit 1
fi
if [ -e "$CANDIDATE_DIR" ]; then
  echo "Refusing to overwrite the existing candidate at $CANDIDATE_DIR." >&2
  echo "Review or relocate it before generating another profile." >&2
  exit 1
fi

ADB=(adb -s "$ANDROID_SERIAL")
"${ADB[@]}" get-state >/dev/null
API_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if ! [[ "$API_LEVEL" =~ ^[0-9]+$ ]] || [ "$API_LEVEL" -lt 33 ]; then
  echo "Baseline Profiles require an API 33+ connected device; found API ${API_LEVEL:-unknown}." >&2
  exit 1
fi

cd "$ANDROID_PROJECT"

# saveInSrc=true is required by the Baseline Profile plugin when automatic release-build
# generation is disabled. Generation therefore stages its output below one variant's
# src/<variant>/generated/baselineProfiles directory. Refuse to touch a pre-existing directory:
# anything already there belongs to the checkout, not this run.
mapfile -d '' -t preexisting_profile_dirs < <(
  find app/src -type d -path '*/generated/baselineProfiles' -print0
)
if [ "${#preexisting_profile_dirs[@]}" -ne 0 ]; then
  echo "Refusing to run while generated Baseline Profile source output already exists:" >&2
  printf '  %s\n' "${preexisting_profile_dirs[@]}" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "$ANDROID_PROJECT/app/build/baseline-profile-work.XXXXXX")"
generation_succeeded=0

evacuate_generated_source() {
  local index=0
  local profile_dir
  local parent_dir
  mapfile -d '' -t generated_profile_dirs < <(
    find app/src -type d -path '*/generated/baselineProfiles' -print0
  )
  for profile_dir in "${generated_profile_dirs[@]}"; do
    index=$((index + 1))
    mv "$profile_dir" "$WORK_DIR/generated-source-$index"
    parent_dir="$(dirname "$profile_dir")"
    # Only remove the plugin's now-empty "generated" container. Never remove a
    # source-set directory, and leave the container alone if another tool uses it.
    rmdir "$parent_dir" 2>/dev/null || true
  done
}

on_exit() {
  local status=$?
  evacuate_generated_source
  if [ "$generation_succeeded" -ne 1 ]; then
    echo "Generation did not produce an accepted candidate." >&2
    echo "Any source-staged output was preserved outside src at $WORK_DIR." >&2
  fi
  exit "$status"
}
trap on_exit EXIT

./gradlew :app:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile

mapfile -d '' -t generated_profiles < <(
  find app/src -type f -path '*/generated/baselineProfiles/*' \
    -name '*baseline-prof*.txt' -print0
)
if [ "${#generated_profiles[@]}" -ne 1 ] || [ ! -s "${generated_profiles[0]:-}" ]; then
  echo "Expected exactly one non-empty source-staged baseline profile; found ${#generated_profiles[@]}." >&2
  exit 1
fi
GENERATED="${generated_profiles[0]}"

required_paths=(
  "com/stremioshell/host/tv/TvAppActivity"
  "com/stremioshell/host/tv/ui/HomeScreen"
  "com/stremioshell/host/tv/ui/DetailsScreen"
  "com/stremioshell/host/tv/ui/StreamsScreen"
  "com/stremioshell/host/tv/ui/SearchScreen"
  "com/stremioshell/host/tv/ui/SettingsScreen"
  "com/stremioshell/host/tv/player/MpvPlayerActivity"
)
for path in "${required_paths[@]}"; do
  if ! rg -q "$path" "$GENERATED"; then
    echo "Refusing candidate: generated profile did not exercise $path." >&2
    exit 1
  fi
done

cp "$GENERATED" "$WORK_DIR/baseline-prof.txt"

{
  echo "generated_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "source_commit=$(git -C "$REPO_ROOT" rev-parse HEAD)"
  echo "source_worktree_dirty=$SOURCE_WORKTREE_DIRTY"
  echo "source_status_sha256=$SOURCE_STATUS_SHA256"
  echo "device_serial=$ANDROID_SERIAL"
  echo "device_manufacturer=$("${ADB[@]}" shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "device_model=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
  echo "android_api=$API_LEVEL"
  echo "build_fingerprint=$("${ADB[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "security_patch=$("${ADB[@]}" shell getprop ro.build.version.security_patch | tr -d '\r')"
  echo "profile_sha256=$(sha256sum "$WORK_DIR/baseline-prof.txt" | cut -d' ' -f1)"
  echo "profile_lines=$(wc -l < "$WORK_DIR/baseline-prof.txt")"
  echo "generator=:app:generateBaselineProfile"
} > "$WORK_DIR/evidence.txt"

# Move every plugin-created source artifact into the candidate before publishing
# it, then disable the failure trap so the final directory rename is atomic.
evacuate_generated_source
generation_succeeded=1
trap - EXIT
mv "$WORK_DIR" "$CANDIDATE_DIR"

echo "Candidate (committed profile was not changed):"
echo "  $CANDIDATE_DIR/baseline-prof.txt"
echo "Evidence:"
echo "  $CANDIDATE_DIR/evidence.txt"
echo "Review and benchmark it using docs/baseline-profile.md before copying it into src/main."
