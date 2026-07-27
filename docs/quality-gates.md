# Quality Gates

Pass/fail, not advisory. Everything here is about one artifact: the Android TV
APK built from `apps/android-tv-host`.

## Every change

- JVM unit tests pass on JDK 17:
  `cd apps/android-tv-host && ./gradlew :app:testDebugUnitTest`
- Debug APK assembles: `./gradlew :app:assembleDebug`
- CI (`.github/workflows/ci.yml`) is green - it runs exactly those two.
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

## Release

- `versionCode` and `versionName` bumped in
  `apps/android-tv-host/app/build.gradle.kts`, with a matching `CHANGELOG.md`
  section.
- Release workflow verifies the APK signature with `apksigner` before upload
  (it does; do not remove that step).
- The published asset is a single `StremioShell-tv-<version>.apk` - the in-app
  updater matches on that name, so ABI splits or a rename break self-update.
- Installing the new APK over the previous one keeps the app on TV home
  screens (launcher alias name unchanged).
