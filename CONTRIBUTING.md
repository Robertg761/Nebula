# Contributing

Nebula is a TV application, so a change is not complete merely because it
compiles on a desktop. Focus behavior, Back behavior, remote profiles, API
level, and physical playback boundaries must be stated explicitly.

## Before starting

The root project-license decision is still pending. Do not assume a license
grant beyond GitHub's terms. External contributors should discuss substantial
work with the maintainer before investing time; the maintainer must settle
project licensing and contribution terms before accepting outside code.

Never use production TMDB, addon, debrid, or signing credentials in tests,
screenshots, issues, commits, shell history, or CI artifacts.

## Local checks

Requires JDK 17 and an Android SDK:

```bash
source scripts/android-env.sh
(
  cd apps/android-tv-host
  ./gradlew \
    :app:testDebugUnitTest \
    :app:lintDebug \
    :app:assembleDebug \
    :baselineprofile:assembleBenchmarkRelease
)
```

Credential-free emulator smoke tests use official Android TV images:

```bash
./scripts/run-tv-instrumentation.sh 34
./scripts/run-tv-instrumentation.sh 26
```

API 26 may require installing
`system-images;android-26;android-tv;x86`. Hardware-only playback cases remain
in [docs/tv-qa-matrix.md](docs/tv-qa-matrix.md).

## Pull requests

- Keep a PR to one coherent change.
- Add or update deterministic tests for behavior changes.
- Keep generated output, APKs, credentials, logs, and device serials out of Git.
- Record local commands and their results.
- Link CI runs after they complete.
- State which physical TV/device cases were run and which remain unverified.
- Update the changelog and docs when behavior or configuration changes.
- Do not bump libmpv across a major version without the full physical playback
  matrix and a review of native third-party licensing/source obligations.

The checklist in `.github/pull_request_template.md` mirrors these requirements.
