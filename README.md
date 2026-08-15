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

Fast automated gate:
`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:baselineprofile:assembleBenchmarkRelease`.
CI additionally runs credential-free Android TV instrumentation on isolated
official API 26 and API 34 emulators. Baseline-profile generation and
hardware-dependent product QA
remain the explicit procedures in `docs/baseline-profile.md` and
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
Use it only on trusted private Wi-Fi or Ethernet: the token prevents unauthorized
changes, but it does not encrypt credentials from the access point or anyone
able to observe that LAN traffic.

### Addon URLs and cleartext

Use `https://` addon URLs. A bare host gets `https://` prefixed automatically;
explicit `http://` addon and subtitle URLs are rejected in every build. This is
intentional because configured Stremio URLs commonly carry debrid credentials
in their path or query. The one-shot phone pairing form remains local
cleartext HTTP as described above; it never returns stored credentials.

## Releases and in-app updates

`.github/workflows/release.yml` builds and publishes manually promoted TV
releases through the protected `release` environment:

1. Is dispatched explicitly from `main`; ordinary pushes never publish.
2. Reads the version from `apps/android-tv-host/app/build.gradle.kts`.
3. Runs unit tests, lint, Baseline Profile producer assembly, and credential-free
   TV instrumentation on API 26 and API 34 for that exact SHA.
4. Verifies the APK signature and compares its SHA-256 signer fingerprint with
   the configured release identity.
5. Attests the signed APK, creates or repairs a draft GitHub Release, and
   verifies the APK plus reviewed Gradle SBOM, native SBOM, and native source
   archive by name, size, and digest before making the release public.

The in-app updater polls GitHub Releases on startup and every six hours in
the background on an unmetered connection (release builds only), matches the
`-tv-` named asset, and
downloads it in the background. Update source defaults to `Robertg761/Nebula` in
`app/build.gradle.kts` through `githubReleaseOwner` and `githubReleaseRepo`, and can
be overridden with `-PgithubReleaseOwner=... -PgithubReleaseRepo=...`.

Before dispatching a release:

1. Bump `versionCode` and `versionName` in
   `apps/android-tv-host/app/build.gradle.kts`.
2. Add the matching `## [x.y.z] - YYYY-MM-DD` section to `CHANGELOG.md`.
3. Confirm signing secrets exist: `SS_SIGNING_STORE_BASE64`,
   `SS_SIGNING_STORE_PASSWORD`, `SS_SIGNING_KEY_ALIAS`,
   `SS_SIGNING_KEY_PASSWORD`, optional `SS_SIGNING_STORE_TYPE`. Also configure
   the repository Actions variable `SS_SIGNING_CERT_SHA256` with the expected
   signing-certificate fingerprint (64 hexadecimal characters; colons are
   accepted).
4. Confirm the protected environment and branch/tag rules in
   `docs/repository-settings.md` are configured.
5. Regenerate and review the Gradle SBOM, native SBOM, and deterministic native
   source archive as described in `docs/release-supply-chain.md`. The workflow
   refuses public promotion if any reviewed digest or inventory check differs.

## Further reading

- `apps/android-tv-host/README.md` - module layout, TV-only manifest, ABIs.
- `docs/quality-gates.md` - what has to pass before a release.
- `docs/lint-baseline.md` - reviewed lint findings and their dispositions.
- `docs/release-supply-chain.md` - the explicit SBOM/native-source release gate.
- `docs/baseline-profile.md` - device-backed profile regeneration procedure.
- `docs/tv-qa-matrix.md` - manual device/remote QA checklist.
- `docs/architecture.md` and `docs/roadmap.md` - trust boundaries and planned
  quality work.
- `PRIVACY.md`, `SECURITY.md`, and `THIRD_PARTY_NOTICES.md` - data handling,
  private reporting, and third-party/source obligations.

## License

Nebula is free software licensed under
[GPL-3.0-or-later](LICENSE). The native playback bundle has the same effective
license because its FFmpeg build enables GPL and version 3 code. See
`THIRD_PARTY_NOTICES.md` and `docs/release-supply-chain.md` for dependency and
corresponding-source details.
