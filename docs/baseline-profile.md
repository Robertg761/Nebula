# Baseline Profile regeneration

The committed `apps/android-tv-host/app/src/main/baseline-prof.txt` is a
device-generated performance artifact, not a rule list to edit by hand. The
repository now has a `:baselineprofile` producer that records cold startup,
Home navigation, Search, Settings, one fixed movie's Details and Streams, and a
15-second playback sample. The producer now drives a sustained multi-rail
D-pad path; `NavigationBenchmark` measures the same path with and without the
profile. Generation is explicit and does not run during a normal build.

The generator intentionally fails before producing an acceptable candidate if
the fixture cannot reach Details, a non-empty stream list, and playback. This
prevents a green startup-only run from replacing broader profile coverage.

## Profiling device and fixture

Use a dedicated physical Android/Google TV device on API 33 or later. A rooted
AOSP emulator is acceptable for a diagnostic run, but release evidence should
come from the physical TV class being optimized.

1. Connect exactly the intended device and note its `adb` serial.
2. Install the non-minified target:

   ```bash
   source scripts/android-env.sh
   cd apps/android-tv-host
   ./gradlew :app:installNonMinifiedRelease
   ```

   The shipping `release` build type runs R8 with resource shrinking, so the
   minified path is exercised by the standard release smoke rather than a
   separate trial variant.

3. On that installed build, configure a non-production TMDB key and an HTTPS
   stream addon backed by a dedicated test account. Never put either credential
   in source, shell history, screenshots, or evidence.
4. Confirm that the movie at TMDB id `550` opens from Details, `Play` reaches a
   non-empty stream list, and the first release plays. The generator depends on
   this stable fixture and fails if any leg is missing.
5. Disable unrelated background workloads and leave display/network settings
   unchanged for the generation and measurement runs.

## Generate a candidate without overwriting source

From the repository root:

```bash
export ANDROID_SERIAL="<adb serial>"
export NEBULA_PROFILE_FIXTURE_CONFIRMED=1
bash scripts/regenerate-baseline-profile.sh
```

The wrapper verifies API level, refuses to overwrite a previous candidate,
runs `:app:generateBaselineProfile`, requires all critical app paths in the
output, and records whether the source worktree was dirty before generation.
It writes untracked build artifacts:

- `apps/android-tv-host/app/build/baseline-profile-candidate/baseline-prof.txt`
- `apps/android-tv-host/app/build/baseline-profile-candidate/evidence.txt`
- `apps/android-tv-host/app/build/baseline-profile-candidate/generated-source-*`

The Gradle plugin temporarily stages generated output beneath
`app/src/<variant>/generated/baselineProfiles`, because source output is the
supported explicit-generation mode. The wrapper refuses to run if that
directory already exists, then moves every directory it created into the
candidate before exiting—even on a failed coverage check. It never overwrites
`src/main/baseline-prof.txt`, and it does not delete unknown source content.
Review the instrumentation result and evidence first. Keep the evidence with
the pull request or QA artifacts; do not commit device serials or generated
reports to the repository. A dirty-worktree run remains useful diagnostically,
but is not reproducible evidence for adopting or releasing a profile: commit
the intended source, regenerate, and require
`source_worktree_dirty=false` in `evidence.txt`.

## Adopt and measure the candidate

Only after the generator passed on the documented device:

```bash
cp apps/android-tv-host/app/build/baseline-profile-candidate/baseline-prof.txt \
  apps/android-tv-host/app/src/main/baseline-prof.txt

cd apps/android-tv-host
./gradlew :baselineprofile:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

The `StartupBenchmark` runs ten cold starts with no compilation and ten with
`BaselineProfileMode.Require`, recording startup and frame metrics.
`NavigationBenchmark` runs five sustained Home D-pad iterations in both modes.
A candidate is acceptable only when:

- the baseline-profile benchmark actually runs (the `Require` mode must not
  fall back silently);
- median startup does not regress against the no-compilation case or the last
  accepted device run;
- the generated file still contains every critical path enforced by the
  wrapper;
- the normal unit-test, lint, and assemble gates pass; and
- the release APK contains the compiled profile:

  ```bash
  ./gradlew :app:assembleRelease
  unzip -l app/build/outputs/apk/release/*.apk | grep -E 'baseline\\.prof(m)?$'
  ```

Record the source commit, device model/build fingerprint, Android security
patch, candidate SHA-256, benchmark medians, and result location in the release
or pull-request QA notes. If device evidence or metrics are absent, leave the
committed profile unchanged and report regeneration as outstanding.

The producer follows Android's supported Baseline Profile Gradle-plugin and
Macrobenchmark flow:
<https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile>.
