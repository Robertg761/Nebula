# Release supply-chain gate

Nebula releases carry three reviewed machine-readable/source artifacts beside
the APK:

- `release/gradle-sbom.cdx.json` inventories the complete locked
  `releaseRuntimeClasspath`. It records each Maven coordinate, declared
  license, the Gradle verification hashes, and any resolved runtime artifact
  hash.
- `release/native-sbom.cdx.json` inventories every shared library in every ABI
  shipped by `dev.jdtech.mpv:libmpv:0.4.1`. It records the exact file digest,
  size, license, ABI, AAR path, and corresponding source component.
- `dist/Nebula-native-sources.tar.gz` is a deterministic corresponding-source
  archive. It contains the exact 18 upstream revisions, nested submodules,
  build recipe, build flags, and pinned source manifest used for libmpv, mpv,
  FFmpeg, and their native dependencies.

The native review is pinned in `release/native-source-manifest.json`. The
bundle uses mpv 0.39.0 and FFmpeg 7.1. FFmpeg enables GPL and version 3 code, so
the effective bundle and project license are GPL-3.0-or-later.

## Regenerating the materials

Use JDK 17 and resolve the verified release graph first:

```bash
source scripts/android-env.sh
(
  cd apps/android-tv-host
  ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
)
node scripts/generate_release_sboms.js
node scripts/generate_native_source_archive.js
node scripts/check_release_supply_chain.js
```

The SBOM generator reads the dependency lock and Gradle verification metadata.
It refuses a mismatched libmpv AAR and requires a declared or reviewed license
for every locked component. The source generator fetches only HTTPS sources,
pins Git inputs by full commit and archive inputs by SHA-256, rejects unsafe
archive paths, and emits normalized tar and gzip metadata.

A second source-generation run must produce the same SHA-256 before a reviewed
archive digest is changed. Generated source archives belong under `dist/` and
are release artifacts, not tracked Git files.

## What the gate checks

`release/supply-chain-status.json` pins the project license, both generators,
both SBOMs, the native manifest, and the generated source archive by SHA-256.
The gate also verifies:

1. all 127 locked release-runtime components have license and verification
   evidence;
2. reviewed Gradle dependency sentinels still match;
3. all 18 native source revisions and effective GPL build options match;
4. the 11 shared libraries are present and hashed for all four AAR ABIs;
5. the archive has only safe regular paths, contains every pinned source tree,
   and embeds the reviewed source manifest verbatim;
6. the three public release assets have distinct filenames.

There is no bypass input. Any dependency, native input, build recipe, generator,
or reviewed artifact change closes the gate until its new output and digest are
reviewed. APK provenance attestation complements this inventory but does not
replace it.
