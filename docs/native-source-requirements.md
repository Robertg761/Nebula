# Native corresponding-source requirements

Official APKs include the Maven artifact
`dev.jdtech.mpv:libmpv:0.4.1` (published AAR SHA-256
`361f4fecb2769fd77697e02f83e24e767431859560282225facc5310a60f6ab2`).
Its wrapper source is tagged at:

- <https://github.com/jarnedemeulemeester/libmpv-android/releases/tag/v0.4.1>
- commit `293d1a606413be36886a2ec922aee3d358690e07`

That repository contains the wrapper and Android build definitions. The pinned
recipe builds mpv 0.39.0 and FFmpeg 7.1. FFmpeg enables GPL and version 3 code,
so the complete distributed native bundle is GPL-3.0-or-later effective.

For every new public release, the workflow now publishes a deterministic
`Nebula-native-sources.tar.gz` beside the APK. The archive contains the exact
wrapper, mpv, FFmpeg, and dependency source revisions, including separately
pinned submodules and the build recipe. The accompanying native CycloneDX BOM
maps every shared library and ABI to a file hash, license, and source component.

The release gate verifies that:

1. the reviewed AAR digest and exact upstream commits have not changed;
2. GPL and version 3 build options remain enabled and documented;
3. the source archive contains every path in the pinned manifest without links
   or unsafe paths;
4. the archive embeds that manifest verbatim and matches its reviewed SHA-256;
5. the source archive and both SBOMs are uploaded and remotely digest-checked
   before the draft release becomes public.

See [release-supply-chain.md](release-supply-chain.md) for regeneration and
review instructions. This process applies to releases made by the updated
workflow and does not make claims about older published artifacts. APK signing
and provenance complement these materials but cannot replace them.
