# Baseline Profile regeneration

The committed `apps/android-tv-host/app/src/main/baseline-prof.txt` and
`startup-prof.txt` are device-generated performance artifacts, not rule lists
to edit by hand. The `:baselineprofile` producer has a launch-only collection
for startup layout and a separate full collection covering cold startup, Home
navigation, Search, Settings, one fixed movie's Details and Streams, and a
15-second playback sample. The full collection is excluded from startup
layout. `NavigationBenchmark` measures the sustained multi-rail path with and
without the profile. Generation is explicit and does not run during a normal
build.

The generator intentionally fails before producing an acceptable candidate if
the fixture cannot reach Details, a non-empty stream list, and playback. The
wrapper also rejects identical startup/baseline files, a startup file that is
not smaller, or startup rules missing from the full baseline. This prevents a
green launch-only run from replacing broader profile coverage.

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

   The shipping `release` build type runs R8 with resource shrinking. Release CI exercises the same
   shrinker configuration, non-debuggable code, and network policy through the x86
   `emulatorRelease` target. That target has narrow keep rules for APIs called across the separate
   test APK boundary. The shipping ARM APK and its release certificate still require the
   physical-device smoke below.

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
runs `:app:generateBaselineProfile`, checks the launch and full-journey
outputs, and records whether the source worktree was dirty before generation.
It writes untracked build artifacts:

- `apps/android-tv-host/app/build/baseline-profile-candidate/baseline-prof.txt`
- `apps/android-tv-host/app/build/baseline-profile-candidate/startup-prof.txt`
- `apps/android-tv-host/app/build/baseline-profile-candidate/evidence.txt`
- `apps/android-tv-host/app/build/baseline-profile-candidate/generated-source-*`

The Gradle plugin temporarily stages generated output beneath
`app/src/<variant>/generated/baselineProfiles`, because source output is the
supported explicit-generation mode. The wrapper refuses to run if that
directory already exists, then moves every directory it created into the
candidate before exiting, including after a failed coverage check. It never
overwrites either committed profile, and it does not delete unknown source
content.
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
cp apps/android-tv-host/app/build/baseline-profile-candidate/startup-prof.txt \
  apps/android-tv-host/app/src/main/startup-prof.txt

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
- the startup profile remains a smaller strict subset of the full baseline;
- the normal unit-test, lint, and assemble gates pass; and
- the release APK contains the compiled profile:

  ```bash
  ./gradlew :app:assembleRelease
  unzip -l app/build/outputs/apk/release/*.apk | grep -E 'baseline\\.prof(m)?$'
  ```

Record the source commit, device model/build fingerprint, Android security
patch, both candidate SHA-256 values, benchmark medians, and result location in
the release or pull-request QA notes. If device evidence or metrics are absent,
leave the committed profiles unchanged and report regeneration as outstanding.

The producer follows Android's supported Baseline Profile Gradle-plugin and
Macrobenchmark flow:
<https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile>.
