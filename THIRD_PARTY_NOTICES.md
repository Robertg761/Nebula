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

The libmpv Android AAR includes native mpv and FFmpeg-family libraries. Their
effective license and source obligations depend on the exact upstream build
configuration, not only the wrapper POM. The release owner must verify that
configuration and preserve corresponding source/build information before each
public release. See the
[native source requirements](docs/native-source-requirements.md) and the
blocking [release supply-chain gate](docs/release-supply-chain.md).

The Outfit license text is preserved at
`apps/android-tv-host/licenses/Outfit-OFL.txt`.

There is intentionally no root `LICENSE` yet. Choosing the project's own
license is a maintainer decision tracked in [docs/roadmap.md](docs/roadmap.md).
