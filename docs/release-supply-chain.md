# Release supply-chain gate

Public release promotion is intentionally blocked by
`scripts/check_release_supply_chain.js` while
`release/supply-chain-status.json` has `releaseReady: false`.

This is not a missing checkbox that can be safely replaced with a generic Gradle
SBOM plugin. Such a plugin can inventory the locked Maven graph and identify
`dev.jdtech.mpv:libmpv:0.4.1`, but it cannot discover the exact mpv and FFmpeg
versions, patches, configure flags, effective licenses, or source commits
embedded inside that AAR. Publishing that output as a complete application SBOM
would be misleading.

To open the gate, a maintainer must:

1. resolve and review the embedded mpv/FFmpeg inventory and binary hashes;
2. generate a pinned CycloneDX JSON or SPDX JSON file for the locked Gradle
   graph and review it alongside `gradle.lockfile` and
   `gradle/verification-metadata.xml`;
3. generate a separately reviewed CycloneDX/SPDX native inventory that names
   every embedded ABI artifact, its exact mpv/FFmpeg inputs, hashes, and
   effective licenses;
4. create a durable corresponding-source archive containing exact native source
   trees, patches, configuration, and build scripts;
5. record repository-relative paths, distinct release filenames, SHA-256
   digests, and at least three reviewed Gradle component sentinels in
   `release/supply-chain-status.json`;
6. map each native component to one distinct archive path using
   `{ "component": "...", "path": "..." }` records in
   `nativeSourceArchive.requiredEntries`;
7. change `releaseReady` only after the material is independently reviewed.

The checker rejects empty files, symlinks and repository escapes; validates
CycloneDX/SPDX structure and required components; matches the reviewed native
versions, licenses, sources and hashes; and inspects the source archive for
unsafe paths, links and required entries. The workflow publishes all three
reviewed artifacts beside the APK, verifies every remote digest while the
release is still a draft, and only then makes it public. Native contents still
require independent review; flipping `releaseReady` is not a substitute for
that review. The gate has no bypass input. APK provenance attestation proves
where the APK was built; it does not substitute for component inventory or
corresponding source.
