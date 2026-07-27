# Android TV Host

This folder contains the Android TV app for Stremio Shell TV: a native Jetpack
Compose (TV) app talking to TMDB for catalog metadata, a Stremio addon (Comet)
for streams, and libmpv for playback. There is no WebView shell any more - it
was deleted in favour of the native app, along with the JS host bridge, the
bundled `apps/web/dist` assets and the JsSandbox core runtime.

`apps/android-tv-host/host-bridge-contract.json` is retained only as the mirror
of the web-side contract in `apps/web`; nothing in this module reads it.

## What is implemented

- Single TV-only variant, package `com.stremioshell.host`, application id
  `com.stremioshell.host.tv`.
- `com.stremioshell.host.tv` - Compose TV UI (home rails, details, streams,
  search, settings, phone pairing).
- `com.stremioshell.host.tv.player` - libmpv player activity (track selection,
  refresh-rate matching, up-next).
- `com.stremioshell.host.update` - GitHub-release self-updater (`-tv-` named
  APK asset) plus the background update worker.

## Build and run

Prerequisites:

- Android SDK installed and `ANDROID_HOME` set.
- JDK 17 installed and active (`java -version` should report 17).

```bash
# 0) Set up JDK 17 / Android SDK paths (Linux/macOS)
source scripts/android-env.sh

# 1) Build
cd apps/android-tv-host
./gradlew :app:assembleDebug
```

On Windows use `.\gradlew.bat` instead, or run `pnpm android:tv:assemble` from
the repo root on any platform. No Node/pnpm step is needed: the Android build
no longer consumes any JS bundle.

Install to connected device/emulator:

```bash
./gradlew :app:installDebug
```

## TV-only app

The app is Android TV-only (single variant, no flavors): the manifest requires
leanback, marks touchscreen as not required, and registers the Leanback
launcher alias with the TV banner. The alias
(`com.stremioshell.host.TvLauncherActivity`) has to keep its name so updates do
not drop the app off TV home screens.

## ABIs

Release packages `arm64-v8a` + `armeabi-v7a` only - no TV device is x86, and
libmpv's native libs are the bulk of the APK. Debug adds `x86_64` for the dev
emulator. Deliberately one universal APK rather than ABI splits: the release
workflow publishes a single `StremioShell-tv-<version>.apk` and the in-app
updater selects the release asset by that name.
