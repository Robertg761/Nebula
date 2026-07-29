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
  changes on a physical Google TV class.
- Record physical playback signoff for at least one Google TV and, when
  available, one non-Google OEM device.
- Choose and add a root project license before accepting outside contributions.
- Verify the native libmpv/FFmpeg license configuration and archive the exact
  corresponding source/build inputs for releases.
- Resolve the intentionally failing release supply-chain gate with a reviewed
  Gradle SBOM and complete native inventory/source archive.

## Recently implemented

- Bounded, redacted local diagnostics and explicit share reports now include
  app/device/network context and recent `ApplicationExitInfo`; Settings can
  erase all app data.
- Search pagination, trailer launch, pairing validation, stream filtering, and
  playback-default controls now have product paths and policy tests.

## Next: reliability and diagnosability

- Continue extracting mpv, audio-route/focus, display-mode, MediaSession, and
  Up Next state controllers behind injectable interfaces.
- Put native stream traffic behind a policy-enforcing transport, or add an
  equivalent audited libmpv resolver/redirect hook, so every DNS answer and
  redirect target receives the same public-HTTPS checks already used for
  subtitle downloads.
- Split the remaining `TvAppViewModel` responsibilities into screen
  repositories/use cases with coroutine-test coverage.
- Separate watch-state mutation ordering from wall-clock display timestamps so
  a backward system-time correction cannot make a persisted entry reject valid
  progress or removal until the clock catches up.
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
- Trial R8/resource shrinking with explicit JNI/serialization keep rules;
  retain it only when APK/startup measurements justify the risk.
- Automate the currently blocked, reviewed CycloneDX/SPDX release inventory only
  after its generator/schema and embedded native inputs are pinned and
  dependency-verified.

## Product work after the quality floor

Product features should be ranked from real diagnostic/issue evidence. Likely
themes are discovery/personalization, subtitle reliability, accessible
navigation, additional addon resilience, and device-specific playback tuning.
