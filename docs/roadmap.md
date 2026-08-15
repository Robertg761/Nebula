# Roadmap

This is a quality-first roadmap. A milestone is complete only when its stated
evidence exists; a local build is not a substitute for GitHub or physical-TV
proof.

## Now: make every release auditable

- Land credential-free Android TV emulator tests on API 26 and API 34.
- Require JVM tests, zero-new-warning lint, debug assembly, Baseline Profile
  producer assembly, dependency review, and both TV jobs before `main`.
- Configure remote branch protection and the protected `release` environment
  from `docs/repository-settings.md`.
- Use manual release promotion, immutable action pins, Gradle wrapper checksum,
  dependency locks/verification, APK digest checks, and provenance attestation.
- Regenerate and benchmark the Baseline Profile for the current UI/player
  changes on a physical Google TV class. Adopt both outputs from the split
  generator so `startup-prof.txt` contains only the launch collection.
- Record physical playback signoff for at least one Google TV and, when
  available, one non-Google OEM device.

## Recently implemented

- The project is licensed under GPL-3.0-or-later, matching the GPLv3-effective
  native playback bundle.
- The locked release graph now has a deterministic CycloneDX inventory. Every
  embedded native ABI file is hashed, and all 18 pinned native source trees can
  be rebuilt into a digest-gated corresponding-source archive.
- Bounded, redacted local diagnostics and explicit share reports now include
  app/device/network context and recent `ApplicationExitInfo`; Settings can
  erase all app data.
- Direct media now uses a tokened loopback proxy. OkHttp owns each remote DNS
  lookup and redirect, while origin-bound addon headers never reach libmpv.
- Bounded HLS master/media playlists now stay inside that boundary: child
  playlists, segments, keys, and maps receive opaque loopback routes and the
  same public-network policy. DASH and Smooth Streaming remain explicitly out
  of scope instead of being passed through to libmpv.
- Baseline Profile generation now records launch-only startup rules separately
  from the full navigation/playback journey. The candidate wrapper rejects
  identical files and requires startup rules to be a strict subset.
- Search pagination, trailer launch, pairing validation, stream filtering, and
  playback-default controls now have product paths and policy tests.

## Next: reliability and diagnosability

- Continue extracting mpv, audio-route/focus, display-mode, MediaSession, and
  Up Next state controllers behind injectable interfaces.
- Split the remaining `TvAppViewModel` responsibilities into screen
  repositories/use cases with coroutine-test coverage.
- Add deterministic update installation tests using old/new test-signed APKs,
  including wrong signer, downgrade, truncation, and launcher/data retention.
- Add coverage reporting for pure policy/orchestration packages after the
  lifecycle extraction makes the metric meaningful.

## Then: platform and dependency modernization

- Move compile/target SDK one platform level at a time and run min/current
  emulator plus physical playback evidence for each behavior change.
- Update AndroidX/Kotlin/Compose in coherent, reviewable groups.
- Treat libmpv major updates as player migrations with native license review and
  the full hardware matrix.
- R8/resource shrinking now ships in `release` with explicit JNI/serialization
  keep rules; retain it only while APK/startup measurements justify the risk.
- Keep the CycloneDX generators, reviewed component counts, embedded native
  inputs, and corresponding-source digest pinned as dependencies evolve.

## Product work after the quality floor

Product features should be ranked from real diagnostic/issue evidence. Likely
themes are discovery/personalization, subtitle reliability, accessible
navigation, additional addon resilience, and device-specific playback tuning.
