# Changelog

## [0.5.0] - 2026-07-27
Feature release from a deep audit against established TV media players: Home, Details, Search, playback, and Settings all grew, and the legacy WebView shell is fully gone - the release APK drops from 116 MB to 50 MB.

- Home: a hero billboard for the top trending title, five genre rails, and infinite row paging (up to 100 items per rail). Rails paint as they arrive instead of waiting for the slowest one.
- My List: save any title from its Details screen; a My List rail on Home renders offline like Continue Watching, with long-press removal.
- Watch Next: Continue Watching now publishes to the Android TV home screen's Watch Next row, and pressing a row there opens the title's Details at the right episode.
- Details enriched: cast row with headshots, "More like this", a fuller metadata line (years, rating, runtime, score, genres), per-episode air dates with unaired episodes dimmed, Specials listed last, and expandable overviews.
- Instant back navigation: details and episode lists are cached in memory, so returning to a title you just left paints immediately (stale copies refresh in place; a changed TMDB key flushes everything).
- Search: All/Movies/Shows filter chips, year and type captions on results, exact-title matches ranked first, and honest searching / no-results / failed states with a Retry.
- Multiple stream addons: up to eight in your priority order, queried in parallel with a 20s per-addon budget, merged into one quality-sorted list with cross-addon duplicate removal, source badges, and an inline notice naming any addon that failed. Existing single-addon installs migrate automatically, and binge autoplay asks every addon too.
- Binge loop: an up-next card with a 15s countdown autoplays the next episode on the same release, watched history is durable (ticks on episode lists, Watch again / Start over), and your last-used stream pick per series is remembered and preselected.
- In-player track menu: MENU opens an Audio / Subtitles / Options panel with persistent preferred languages, subtitle size, playback speed, and audio/subtitle delay.
- External subtitles: a "Get subtitles" section fetches community OpenSubtitles for the current title, grouped by language with your preferred language first.
- Audio passthrough option (AC3/E-AC3/DTS/TrueHD) for AVR owners, and a one-line notice when a Dolby Vision profile-5 file plays on a display without DV (the green/purple-cast case).
- Playback hardening: a 45s stall watchdog with an in-place Retry, demuxer cache scaled to device RAM, pause when audio output disconnects, an OLED screen-on guard, and remaining-time plus end-of-film clock on the OSD.
- Focus and accessibility: every row remembers the card you left and returns focus to it, long episode lists compose lazily (no more season-switch jank), and cards, the hero, episodes, and buttons carry proper TalkBack labels.
- Settings hardening: saving with a blank field keeps the stored value and says so (explicit Clear buttons do the deliberate thing), addon list management with inline validation, a configurable subtitles addon URL under Advanced, and several D-pad focus traps fixed.
- Pairing security: the phone pairing page is gated by a one-time token from the QR code and no longer echoes your stored TMDB key or debrid URL; the form is write-only.
- Privacy and resilience: the TMDB key is scrubbed from every error message and log line, catalog requests are served from a disk cache for up to 7 days when the network fails, and Continue Watching renders offline.
- The auto-updater now runs in the native app, with an Install prompt when a verified newer APK is ready; downloads verify their size and are refused on metered or roaming connections.
- Artwork polish: tonal placeholders and title-text fallbacks for posters that fail to load, and the Details backdrop no longer shows gradient banding.
- Removed the legacy WebView shell and its entire web workspace; the launcher entry is unchanged, so the app keeps its place on TV home screens.

## [0.4.0] - 2026-07-27
Playback and navigation overhaul: fifteen fixes from a deep audit of the player and Compose navigation, validated by unit tests, instrumentation, and an emulator QA drive.

- Fixed the app being killed by pressing OK twice on a stream: the second press launched a second player over the first, and creating libmpv twice aborts the process. Stream launches are now single-flight and the player is single-top.
- Fixed fast navigation playing the wrong content or saving resume progress under the wrong title: screen loads are now cancelled and keyed per request, so stale responses can't overwrite the current screen.
- Dead or unreachable streams now show "Playback failed" with the mpv error and a "Press BACK to try another stream" hint (with a 60s load watchdog) instead of an infinite spinner.
- Seeking: rapid LEFT/RIGHT presses coalesce into one seek with an instant OSD preview, presses are never dropped, and exiting mid-seek saves the seek target instead of the stale position.
- Resume entries are no longer wrongly deleted: only actually reaching the end of a known duration (~98%) marks a title watched. Truncated downloads, seeking past the end, or pausing near the end all keep your place.
- Navigation state now survives: the back stack, scroll positions, and selected season persist across screen changes and activity recreation (an HDR display-mode switch no longer resets the app), and BACK from the nav drawer returns to content instead of exiting.
- Continue Watching now resumes shows at the correct episode, and the details screen highlights the resume episode with its progress.
- The search field no longer traps the D-pad: DOWN moves into results when there are any, and focus lands on the first result.
- Proper audio focus and a MediaSession: playback pauses for phone-cast audio or the assistant, ducks during transient interruptions, and remote play/pause/stop and media keys work.
- Every screen guarantees a focus target (with retry across frames), so focus can no longer silently die leaving the remote unresponsive.
- Watch progress is now also saved every 30 seconds during playback, so a power cut or force-stop keeps your place; the currently playing title stays on top of Continue Watching.
- Fixed a crash opening a stream list where two streams shared the same URL.
- Blocking mpv property reads moved off the main thread (worker thread + debounced track info), removing OSD-driven frame hitches.
- Saving Settings no longer blanks the Home screen while it re-verifies: rails refresh in place, and pairing from a phone applies the new key immediately.

## [0.3.9] - 2026-07-20
- Smooth video playback: the player now detects each title's frame rate and switches the TV's display refresh rate to match it (e.g. 24Hz for 23.976/24fps film, 25/50Hz for PAL, 30/60Hz for 30fps), so motion plays with even cadence instead of the uneven 3:2-pulldown judder you get forcing 24fps film onto a fixed 60Hz panel. The display returns to its normal refresh rate when you leave the player. The current frame rate is shown in the on-screen info.

## [0.3.7] - 2026-07-20
- Made up/down row navigation a single smooth scroll: the focused row now glides to its focus line in one motion instead of the two-step nudge-then-settle that felt choppy. (Uses Compose foundation 1.7's bring-into-view customization.)

## [0.3.6] - 2026-07-20
- Fixed the focused poster growing over its own title (smaller focus scale plus more spacing), and made vertical navigation settle each focused row at a consistent position with the neighbouring rows peeking above and below, instead of leaving it jammed against the screen edge.

## [0.3.5] - 2026-07-19
- Added a baseline profile so the Compose UI paths (rows, cards, focus, image loading) are ahead-of-time compiled, cutting cold-start and first-interaction jank; it's embedded in the APK and installed at first run.
- Trimmed the poster memory cache further for RAM-constrained TV hardware to reduce swap/GC pressure during navigation.

## [0.3.4] - 2026-07-19
- Smoother navigation: posters now decode as RGB_565 with a bounded image cache, cutting memory use and the garbage-collection pauses that caused multi-hundred-millisecond freezes while moving around poster rows (99th-percentile frame time measured dropping from ~300ms to ~48ms on a Google TV Streamer). The QR code is now generated off the main thread.

## [0.3.3] - 2026-07-19
- Set up with your phone: the TV shows a QR code, you scan it and paste your TMDB key and Comet URL from your phone's keyboard, and they push straight to the TV over your home network - no more typing a long URL on the remote. Available from the welcome screen and Settings. Manual entry is still there as a fallback.

## [0.3.2] - 2026-07-19
- Fixed Settings being unusable by remote: the text fields trapped the D-pad, so you could not reach the second field or the Save button. Up/Down now walk cleanly between the TMDB field, addon field, and Save. Verified on a physical Google TV Streamer with the full chain (browse -> Comet streams -> Real-Debrid playback in libmpv).

## [0.3.1] - 2026-07-19
- Focus lands on content when the app opens and when screens change - no more stranded focus in the nav rail.
- New welcome screen on first run with a one-press path to Settings; Save now tests both connections and reports "TMDB: connected | Addon: connected (Comet)".
- Continue Watching cards show a watched-progress bar; movie details show Resume with time remaining.
- Player: buffering spinner, on-screen controls hint, current audio/subtitle track display, MENU cycles subtitles, and audio-track key cycles audio.
- Loading spinners and Retry buttons on every network screen; search now waits for you to stop typing instead of querying per keystroke.

## [0.3.0] - 2026-07-19
- New native Compose TV app is now the launcher: TMDB catalogs (trending/popular/search, details with seasons and episodes), Comet addon stream picker, and Continue Watching with resume — no Stremio account or services required.
- Playback moved to libmpv: plays formats the device lacks hardware decoders for (HEVC 10-bit, TrueHD/DTS audio, ASS/PGS subtitles) via software decoding, with a TV OSD, D-pad/media-key controls, and resume positions.
- First run: enter your TMDB API key and Comet manifest URL (configured with your Real-Debrid key) under Settings.
- The WebView shell remains intent-reachable as a fallback this release and will be removed next release.

## [0.2.0] - 2026-07-19
- Fixed TV D-pad navigation dead ends on Board: overlay detection no longer lets substring selectors (e.g. the 49x49 nav-menu button matching `[class*="popup"]`) trap focus in an empty container.
- Made the sidebar reachable by D-pad: upstream nav tabs carry `tabindex="-1"` and were excluded from the focus candidate pool entirely.
- Promoted the zone-aware `tv_nav_v2` navigation engine to default (validated on an Android TV API 34 emulator); `tv_nav_v2=0` falls back to the legacy engine.
- Fixed zone transfers landing on the wrong element: transfers now only target elements that actually resolve to the destination zone.
- Made the app TV-only: removed the phantom `device` flavor dimension; Gradle tasks lose the `Tv` infix and the leanback launcher moved to the main manifest (applicationId keeps the `.tv` suffix so self-updates keep working).
- Hardened security: external URLs open as browsable-only intents; synthesized local streaming-server responses use an origin allowlist instead of wildcard CORS.
- Expanded tests: host-bridge envelope and `playback.open` normalization suites (web), navigation-core route/zone-transfer suite, `BackgroundUpdateWorker` retry policy, `NativePlaybackContracts` edge cases; Android instrumentation suite green on an API 34 TV emulator.
- Replaced Windows/macOS-only scripts with cross-platform Node tooling (`upstream:sync`, `core:use-local`, `android:tv:assemble`) and a Linux/macOS `scripts/android-env.sh`.
- CI now runs lint, web unit tests, TV smoke tests, Android JVM tests, and API 26/34 TV-emulator instrumentation; releases verify APK signatures before upload.
- Removed committed build outputs, QA captures, and release APKs from the repo (~100MB) with ignore rules to keep them out.

## [0.1.1] - 2026-02-22
- Added deterministic Android TV back-handling handshake (`back.pressed` + `back.handled`) with timeout fallback.
- Hardened TV D-pad focus recovery, route-aware initial focus, deep-link host-event routing, and visible focus ring injection in TV shortcuts overlay.
- Improved host diagnostics export with dedicated host-event and back-decision sections.
- Added native player remote ergonomics improvements (media keys, menu/info handling, controller focus behavior, unsupported-setting notice).
- Extended host bridge contract/types/docs with `requestId` on `back.pressed`, plus `back.handled` and `updates.check` command coverage.
- Added Android instrumentation smoke tests and web TV smoke tests (`pnpm test:tv-smoke`) plus balanced manual TV QA matrix documentation.

## [0.1.0] - 2026-02-21
- Initial public release baseline for Stremio Shell Android host (`mobile` and `tv` flavors).
