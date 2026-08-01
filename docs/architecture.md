# Architecture

## Product boundary

Nebula is one native Android TV application. There is no WebView or JavaScript
runtime in the APK. `apps/android-tv-host` contains the application and the
device-only Baseline Profile producer.

## Runtime layers

- `tv/ui`: Compose TV routes, focus policy, navigation, and shared design
  components.
- `tv/TvAppViewModel`: screen state and orchestration.
- `tv/data`: DataStore-backed settings/history plus HTTP/cache policy.
- `tv/data/tmdb`, `addon`, and `subtitles`: external service clients and
  response models.
- `tv/player`: libmpv lifecycle, Android surface/display integration, audio
  focus/routes, MediaSession, tracks, seeking, and Up Next.
- `tv/channel`: Android TV Watch Next publication and deep links.
- `update`: GitHub release discovery, Download Manager state, archive identity,
  signing-lineage checks, and installer intents.

## Principal flows

```text
Remote / Assistant / Watch Next
              |
       TvAppActivity route
              |
       Compose back stack
              |
       TvAppViewModel
       /      |       \
    TMDB    Addons   DataStore
              |
          stream choice
              |
       MpvPlayerActivity
```

## Trust boundaries

- TMDB keys and addon/debrid URLs are secrets. They remain in app-private
  DataStore, are excluded from backup/transfer, and must be redacted from logs.
- Phone pairing is token-gated but cleartext on the LAN.
- External metadata, addon JSON, subtitles, artwork, and stream URLs are
  untrusted network input and must be size/time bounded.
- Addon-provided playback URLs are reduced to canonical public HTTPS URLs
  before they reach libmpv. Subtitle downloads additionally validate every
  redirect and reject DNS answers containing a loopback, private, link-local,
  multicast, or otherwise non-public address.
- Native libmpv still owns stream redirects and DNS resolution after the
  initial URL check. A complete rebinding/redirect boundary requires a
  policy-enforcing stream transport (or equivalent native resolver hook);
  initial validation must not be described as complete SSRF isolation. Until
  then, only `Accept`, `Accept-Language`, and `User-Agent` addon headers are
  handed to libmpv; credentials and arbitrary custom headers stay out of its
  redirect chain.
- The updater accepts only the canonical release asset and then verifies size,
  package name, version, version code, and Android signing lineage before
  opening the installer.
- Release signing secrets are available only inside the protected `release`
  environment job; checkout credentials are not persisted.

## Verification layers

- JVM tests cover pure parsing, selection, retention, update, focus-label, and
  playback policies.
- Isolated Android TV API 26/34 emulator tests cover lifecycle routing, the
  accessibility tree, D-pad focus, Back, and DataStore without service
  credentials.
- Baseline Profile generation and macrobenchmarks require an explicitly
  provisioned physical TV.
- HDR, cadence switching, HDMI/CEC, passthrough, decoder behavior, and long
  playback remain physical-device signoff.

## Known pressure points

`MpvPlayerActivity` and `TvAppViewModel` still own too many responsibilities.
The roadmap calls for extracting injected player/audio/display/session
controllers and screen repositories so lifecycle races can be tested without
manufacturing a full Activity.

The current libmpv Android callback exposes an event id but no per-file id.
Java-side generation checks make callbacks already queued for an old file
inert, but an old native event delivered only after a replacement begins is
indistinguishable from a new-file event. Device stress coverage must continue
to verify libmpv's event ordering until the player state machine has an
identity-bearing native seam.

Playback worker mutations now pass through a testable session guard, so a
queued mutation for a replaced or destroyed file is dropped before JNI work.
The native event limitation remains a physical-device validation item rather
than something the Java generation counter can prove away. Route, focus,
network, and player-load sections use `PerformanceTrace` so Perfetto captures
can separate frame-time work from async latency.
