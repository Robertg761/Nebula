# Quality Gates

Pass/fail, not advisory. Everything here is about one artifact: the Android TV
APK built from `apps/android-tv-host`.

## Every change

- JVM unit tests pass on JDK 17:
  `cd apps/android-tv-host && ./gradlew :app:testDebugUnitTest`
- Android lint passes: `./gradlew :app:lintDebug`
- Existing reviewed lint findings remain in `app/lint-baseline.xml`; any new
  non-dependency warning fails the build. Dependency freshness is handled by
  Dependabot rather than mutable online lint metadata. Finding-by-finding
  dispositions are recorded in `docs/lint-baseline.md`.
- Debug APK assembles: `./gradlew :app:assembleDebug`
- The signed debug APK passes the same native page-size check used for release.
  From the repository root, run
  `./scripts/verify-android-16k-page-size.sh apps/android-tv-host/app/build/outputs/apk/debug/app-debug.apk`.
- The Baseline Profile producer compiles without requiring or modifying a
  connected TV: `./gradlew :baselineprofile:assembleBenchmarkRelease`
  (use that exact variant task; the aggregate producer `assemble` task may
  include connected instrumentation).
- CI (`.github/workflows/ci.yml`) is green - it runs all four.
- Credential-free debug tests pass on isolated Android TV API 26 and API 34 emulators:
  `./scripts/run-tv-instrumentation.sh 26` and
  `./scripts/run-tv-instrumentation.sh 34` from the repository root.
- Never point `:app:connectedDebugAndroidTest` directly at a personal TV. The tests reject
  unguarded physical hardware and Gradle retains the target APK by default, but the supported
  hardware workflow is `scripts/run-tv-instrumentation-physical.sh`; it is the layer that can
  restore DataStore after the instrumentation process dies or disconnects.
- The wrapper JAR/distribution, dependency locks, and verification metadata
  match the reviewed build graph.
- Docs updated when behavior or configuration changes.
- No generated output committed: `build/`, `dist/`, `artifacts/`, APKs.

## Before a release candidate

- Manual TV QA matrix in `docs/tv-qa-matrix.md` passes on at least one Google
  TV class device; a second non-Google Android TV/OEM device when available.
- Cold launch reaches a usable UI with focus on a real control, no black
  screen.
- D-pad reaches every interactive control on Home, Details, Streams, Search and
  Settings, with a visible focus indicator.
- Back policy holds: modal close, then route back, then app exit.
- Playback runs for at least 30 seconds, survives a surface teardown and
  rebuild, and resumes at the stored position rather than 0:00.
- Settings survives a save with empty fields (the save guard keeps stored
  values) and a deliberate Clear still clears.
- No open P0 issues; every P1/P2 has an owner.
- GitHub evidence is attached to the candidate. Local emulator results do not
  stand in for missing required checks.

## Release

- `versionCode` and `versionName` bumped in
  `apps/android-tv-host/app/build.gradle.kts`, with a matching `CHANGELOG.md`
  section.
- Release workflow verifies the APK signature with `apksigner` before upload
  and requires its SHA-256 signer fingerprint to match the repository Actions
  variable `SS_SIGNING_CERT_SHA256`.
- The signed release APK passes Build Tools 35 `zipalign -P 16` and every
  `arm64-v8a` ELF `LOAD` segment has alignment of at least 16 KB. The verifier
  discovers Android SDK/NDK tools from environment variables or `PATH`; it
  does not depend on one workstation layout.
- Release workflow reruns unit tests, lint, the Baseline Profile producer
  compile, and the credential-free minified `emulatorRelease` instrumentation suite on both API
  26 and API 34 for the exact release SHA. This target copies release R8, resource shrinking,
  non-debuggable code, and network policy. It swaps the ARM ABIs and release certificate for x86
  emulator ABIs and a CI test certificate, and preserves only the library/app members called
  directly across the separately minified test APK boundary.
- Regenerate and measure the committed Baseline Profile after changing startup
  or critical navigation/playback paths, following `docs/baseline-profile.md`.
  Never hand-edit or claim a regenerated profile without the recorded device
  evidence required there.
- The shipping `release` build runs R8 with resource shrinking and narrow
  JNI/serialization keep rules. Any keep-rule change must pass the
  JNI/serialization and playback smoke on the physical TV benchmark device.
- The signed shipping APK remains an ARM artifact and cannot be installed on the workflow's x86
  Android TV AVD. Its signature and contents are checked in CI; runtime signoff stays a physical-TV
  release gate.
- The published release contains exactly one
  `StremioShell-tv-<version>.apk` plus the reviewed Gradle SBOM, native SBOM,
  and corresponding-source archive. The updater matches the APK by its exact
  name, so ABI splits or a rename break self-update.
- Installing the new APK over the previous one keeps the app on TV home
  screens (launcher alias name unchanged).
- Promotion is a manual dispatch from `main` through the protected `release`
  environment. Tag suffix and prerelease state agree; all asset names, sizes,
  and SHA-256 digests are verified on a recoverable draft before it is made
  public, followed by a final target-SHA/title/state verification.
- The published APK has a GitHub build-provenance attestation.
- Native third-party license/source obligations in
  `THIRD_PARTY_NOTICES.md` and `docs/native-source-requirements.md` are
  satisfied for the exact libmpv/FFmpeg build.
- Regenerate both SBOMs and the native source archive, then require
  `node scripts/check_release_supply_chain.js` to match every reviewed digest
  and inventory check in `docs/release-supply-chain.md`.
