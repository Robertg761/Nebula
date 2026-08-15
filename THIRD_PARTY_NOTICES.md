# Third-party notices

Nebula depends on open-source libraries distributed through Google Maven and
Maven Central. Their own licenses continue to apply. This notice does not
replace the license text shipped by a dependency or constitute a legal
conclusion about a release.

Key runtime components include:

| Component | Version in source | Upstream | Declared license |
| --- | --- | --- | --- |
| AndroidX / Jetpack Compose / TV | See `app/build.gradle.kts` and lockfiles | <https://github.com/androidx/androidx> | Apache-2.0 |
| Kotlin and kotlinx libraries | See build files and lockfiles | <https://github.com/JetBrains/kotlin> | Apache-2.0 |
| OkHttp | 4.12.0 | <https://github.com/square/okhttp> | Apache-2.0 |
| Coil | 2.6.0 | <https://github.com/coil-kt/coil> | Apache-2.0 |
| ZXing | 3.5.3 | <https://github.com/zxing/zxing> | Apache-2.0 |
| NanoHTTPD | 2.3.1 | <https://github.com/NanoHttpd/nanohttpd> | BSD-3-Clause |
| libmpv-android wrapper | 0.4.1 | <https://github.com/jarnedemeulemeester/libmpv-android/releases/tag/v0.4.1> | MIT per published POM |
| Outfit font | Bundled files | <https://github.com/Outfitio/Outfit-Fonts> | SIL Open Font License 1.1 |

The libmpv Android AAR includes mpv 0.39.0 and FFmpeg 7.1 native libraries.
This build enables GPL and version 3 code, making the distributed playback
bundle GPL-3.0-or-later effective. Its exact source revisions, build flags,
per-ABI file hashes, and corresponding-source generator are pinned and checked
for each release. See the
[native source requirements](docs/native-source-requirements.md) and the
[release supply-chain gate](docs/release-supply-chain.md).

The Outfit license text is preserved at
`apps/android-tv-host/licenses/Outfit-OFL.txt`.

Nebula itself is licensed under GPL-3.0-or-later; see [LICENSE](LICENSE).
