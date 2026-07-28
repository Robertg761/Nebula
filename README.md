# stremio-shell-tv

Nebula: a native Android/Google TV client for Stremio content. Jetpack Compose
for TV UI, TMDB for catalog and metadata, Stremio stream addons (Comet and
friends) for stream links, and libmpv for playback. There is no WebView and no
bundled web bundle: the Compose app is the whole product.

The repo, the Gradle module and the application id still say `stremio-shell`;
only the product is called Nebula. Renaming the application id would break
self-updates for every installed copy, so it stays.

## Repo layout

- `apps/android-tv-host`: the Android app (the only build in this repo).
- `scripts`: JDK/SDK env helper, Gradle wrapper runner, release version and
  changelog helpers used by CI.
- `docs`: quality gates and the manual TV QA matrix.

## Build and run

Requires JDK 17 and an Android SDK. `.java-version` pins 17 for version
managers that read it.

```bash
source scripts/android-env.sh   # sets JAVA_HOME / ANDROID_HOME if unset
cd apps/android-tv-host
./gradlew :app:assembleDebug    # or :app:installDebug
```

From the repo root, `npm run android:tv:assemble` and `npm run android:tv:test`
are the same thing through `scripts/run-gradle.mjs` (plain Node, no install
step - the repo has no JS dependencies). Debug APK lands in
`apps/android-tv-host/app/build/outputs/apk/debug/`.

Unit tests: `./gradlew :app:testDebugUnitTest`. There is no instrumentation
suite in the repo right now; device coverage is the manual matrix in
`docs/tv-qa-matrix.md`.

## First-run configuration

Everything the app needs is entered on its Settings screen, on device:

- **TMDB API key** - from themoviedb.org > Settings > API. Without it no rails
  or search results load.
- **Stream addons** - one or more Stremio addon manifest URLs, in priority
  order (the first addon offering a release wins; the rest are merged in and
  sorted by quality). A Comet instance configured with your own Real-Debrid key
  is the usual first entry. Up to 8 addons.
- **Advanced > Subtitles addon URL** - blank uses the built-in OpenSubtitles v3
  addon.

Typing URLs on a remote is miserable, so **Settings > Set up with phone** shows
a QR code for a one-shot LAN web form: the phone submits the TMDB key and addon
URL from its own keyboard. That form is plain HTTP on your local network,
guarded by a single-use token shown only in the QR code, write-only (it never
renders the stored values back), and it dies when you leave the pairing screen.

### Addon URLs and cleartext

Use `https://` addon URLs. A bare host you type gets `https://` prefixed
automatically, but an explicit `http://` URL is sent in cleartext: the manifest
and stream requests, including anything embedded in the addon path, travel
unencrypted and are readable by anything on the network path. That matters here
because a Comet manifest URL typically embeds your Real-Debrid token in the
path. Release builds set `usesCleartextTraffic=false`, so `http://` addons fail
outright there; debug builds allow cleartext for local testing.

## Releases and in-app updates

`.github/workflows/release.yml` builds and publishes TV releases:

1. Triggers on `main` pushes touching release files, or `workflow_dispatch`.
2. Reads the version from `apps/android-tv-host/app/build.gradle.kts`.
3. Builds and signs `:app:assembleRelease`, verifies the signature with
   `apksigner`.
4. Creates GitHub Release `v<version>` with `StremioShell-tv-<version>.apk`.

The in-app updater polls GitHub Releases on startup and hourly in the
background (release builds only), matches the `-tv-` named asset, and
downloads it in the background. Update source defaults live in
`app/build.gradle.kts` (`githubReleaseOwner`, `githubReleaseRepo`) and can be
overridden with `-PgithubReleaseOwner=... -PgithubReleaseRepo=...`.

Before pushing a release:

1. Bump `versionCode` and `versionName` in
   `apps/android-tv-host/app/build.gradle.kts`.
2. Add the matching `## [x.y.z] - YYYY-MM-DD` section to `CHANGELOG.md`.
3. Confirm signing secrets exist: `SS_SIGNING_STORE_BASE64`,
   `SS_SIGNING_STORE_PASSWORD`, `SS_SIGNING_KEY_ALIAS`,
   `SS_SIGNING_KEY_PASSWORD`, optional `SS_SIGNING_STORE_TYPE`.

## Further reading

- `apps/android-tv-host/README.md` - module layout, TV-only manifest, ABIs.
- `docs/quality-gates.md` - what has to pass before a release.
- `docs/tv-qa-matrix.md` - manual device/remote QA checklist.
