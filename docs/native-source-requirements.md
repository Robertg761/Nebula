# Native corresponding-source requirements

Official APKs include the Maven artifact
`dev.jdtech.mpv:libmpv:0.4.1` (published AAR SHA-256
`361f4fecb2769fd77697e02f83e24e767431859560282225facc5310a60f6ab2`).
Its wrapper source is tagged at:

- <https://github.com/jarnedemeulemeester/libmpv-android/releases/tag/v0.4.1>
- commit `293d1a606413be36886a2ec922aee3d358690e07`

That repository contains the wrapper and build definitions and pins its native
inputs. The AAR also contains mpv and FFmpeg-family binaries whose applicable
license/source obligations depend on the exact build flags.

This repository does not currently make a formal written source offer or claim
that the existing public APK has a complete corresponding-source package. The
maintainer must obtain license advice appropriate to the exact native build and
choose a compliant distribution/source-availability method before another
public release.

Before the next public release, the release owner must:

1. verify the exact libmpv-android tag/commit and its pinned mpv/FFmpeg inputs;
2. determine the native build's effective licenses and required notices;
3. archive those source trees, patches, configuration, and build scripts in a
   durable public release location;
4. link that archive and its SHA-256 from the GitHub Release notes; and
5. preserve all required license texts in the distribution or adjacent release
   materials.

The repository currently documents this obligation but does not yet automate
the native source archive. Public promotion is blocked until the inventory,
SBOM, and archive satisfy the checks in
[release-supply-chain.md](release-supply-chain.md); APK signing and provenance
cannot prove those materials.
