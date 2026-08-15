# Changelog

## [Unreleased]

## [0.6.2] - 2026-08-15

A detail pass over every surface, driven by an audit that turned up 255 findings. The 0.6.0 overhaul
put the app onto one design system; this makes that system actually hold, and fixes a set of defects
that only show up when you sit in front of the thing.

**Reliability, product and release follow-through.**
- Search keeps paging when a filtered page contains only the other media type, and paging Retry
  hands focus back safely.
- Pairing validates TMDB and every addon before one atomic save; cancelling/ leaving cannot let an
  in-flight validation commit later. The phone form now has associated labels and announced errors.
- The stream picker can filter by availability, dynamic range, resolution, source and size, with a
  recommended view and deliberate focus recovery when filters remove a control.
- Playback defaults now cover languages, subtitle size, audio output, autoplay and countdown.
  Rapid presses serialize against authoritative stored preferences, and unapplied language drafts
  participate in Settings' save/leave guard.
- Addon playback URLs are canonicalized to HTTPS and reject private literals/local names. Subtitle
  DNS answers and every redirect are additionally revalidated, downloads are size/cancellation
  bounded, and TMDB metadata can fall back to stale cache even when a network body fails after its
  headers.
- libmpv has generation-scoped ownership and file-scoped callbacks, so delayed teardown or events
  cannot destroy or mutate a replacement player. TLS certificate verification is explicit.
- Bounded, redacted diagnostics stay local until the viewer shares a uniquely written report.
- Credential-free Android TV checks now run against official API 26 and API 34 images. Dependency
  locks/checksums, pinned Actions, guarded manual promotion and provenance checks raise the release
  floor. The GPL-3.0-or-later project license, reviewed Gradle/native SBOMs, and deterministic
  corresponding-source archive now make the release inventory complete and digest-gated.

**Titles are branded, not typeset.** Home's billboard and the Details header both lead with the
title's own logotype where TMDB has one, falling back to type where it does not. (TMDB files SVG
variants next to the PNGs and Coil cannot decode them, so the pick excludes SVG outright - choosing
one renders an empty gap where the title should be.) The billboard's logo costs one extra request,
written through the cache the Details screen reads - so pressing OK on the billboard, which is the
most likely next press in the app, now opens warm instead of on a spinner.

**The player's key legend teaches, then retires.** The transport panel printed "OK play/pause |
LEFT/RIGHT 10s | UP for controls | DOWN hides" on every open, forever. Deleting it was not an option
- this remote has no MENU, CAPTIONS or transport keys, so "UP for controls" is genuinely
undiscoverable - so it now shows for the first five opens on an install and then stops.

**Contrast, measured rather than judged.**
- The primary button's label was white on violet at 3.69:1 - failing AA in the state it spends most
  of its life in, since the focused state was the only one with dark ink. It is dark ink throughout
  now, so focus reads as one object brightening rather than one inverting.
- The quietest text tone was 3.18:1 on a chip while carrying the player's key legend, the pairing
  instructions and the up-next countdown - none of which is decoration. Raised to clear 4.5:1
  everywhere it appears.
- The tonal ramp's middle step was 1.14:1, so every control that signalled focus by changing surface
  was signalling nothing. Widened, and a fourth step added for focused rows.
- Badge fills are opaque. As translucent tints they took the artwork behind them, so the same chip
  was a different colour on the billboard than in a list.
- A caveat badge no longer wears the failure colour: pausing a film used to put a red error chip on
  the OSD.

**Motion, within the frame budget.** The player's controls fade instead of appearing in one frame -
the most noticeable tell the app had. Dialogs have an entrance. Nothing animates per-item inside a
scrolling row and nothing animates layout, which is what this hardware cannot afford.

**Player.**
- The scrub bar shows how far ahead the stream has actually buffered, so a struggling stream is
  legible instead of mysterious.
- The controls' backing wash stops short of opaque black; it was deleting the bottom fifth of the
  picture whenever the panel was up.

**Fixes.**
- Player recreation now restores the stream and episode actually in progress, including headers,
  subtitles, resume/reset state and an intentional pause, instead of reopening the original intent
  at the replacement episode's position. Pausing also disarms stall failure synchronously, sleep
  expiry retires in-flight resolvers, manual Next uses one watched threshold, MediaSession cannot
  seek behind Up Next, and passthrough routes refresh their exact codec set.
- Legacy Japanese, Korean, Simplified Chinese and Traditional Chinese subtitle files are converted
  with language-appropriate encodings instead of being decoded as Western European text.
- Watch Next provider failures, null cursors/inserts and partial mutations are retryable rather than
  reported as published. Its deep-link resume position now reaches the matching movie or episode as
  a fallback when no local record exists, and a restored filtered-empty Streams screen has a real
  initial D-pad focus target.
- Watch-state and remembered-stream mutations use logical action order rather than wall-clock order,
  so a clock correction cannot freeze progress, watched/reset/remove actions or resurrect a row from
  a delayed save. Malformed addon-list JSON is preserved through both immediate edits and Save, and
  immediate Settings operations keep their busy/result state across recreation.
- Update download metadata, enqueue and ID publication are one process-atomic handoff; prompts and
  permanent rejections are bound to the exact download/immutable GitHub asset, so concurrent checks
  and corrected same-version uploads cannot orphan, cancel or hide the wrong APK. Invalid addon
  stream URLs are discarded before the global result cap, and phone pairing binds only the selected
  Wi-Fi/Ethernet address instead of every interface.
- Watch Next/deep-link Details waits for the stored TMDB key to initialize instead of hanging on
  its cold-start sentinel; a genuinely missing key now offers a focused route to Settings.
- Search results and paging are owned by both query and credential generation, so changing or
  clearing the TMDB key cannot leave or land results fetched with the previous key.
- Returning from Settings retries a stream request that previously failed for having no addon, and
  a settings persistence failure no longer masquerades as a successful Save & leave.
- Start Over durably clears the old resume point even if playback fails or is exited immediately.
  Display-frame-rate discovery survives pause/surface recreation, and a seek's fallback timer now
  starts only after mpv receives that exact seek, so an old timeout cannot clear a newer target.
- Stale disk-cache fallbacks remain visible but immediately refreshable instead of being re-dated
  as live responses for four hours; hero metadata is also re-owned correctly after a key change.
- Watch Next updates explicitly clear nullable provider columns, pairing closes rejected POST bodies
  before keep-alive reuse, and the updater now follows releases from `Robertg761/Nebula`.
- Settings opened with its own heading scrolled off the top: the opening focus request dragged the
  page down against a layout that was still settling, and a single scroll reset was overwritten by
  the animation that caused it. The page is held at its top until you actually move.
- Every rail was reserving vertical space for a focus ring that was never clipped - a scrollable
  only clips its scroll axis. Removing it returns about a quarter of a viewport across eight rails.
- Rail headings sat 16dp right of the posters they label, so Home had two left edges. The accent
  tick now hangs in the margin and the words line up with the artwork.
- The accent ticks rendered as a flat mid-blue: a violet-to-cyan gradient was being run across four
  physical dp. It now runs down their long axis, which is the first time the app's cyan is visible.
- The type scale's leading trim did the opposite of what its comment claimed, so every tuned gap in
  the app was off by the half-leading.
- Unfocused poster captions are dimmed, so a rail reads as one selected thing among many rather than
  twenty equally-shouting titles.
- Cards that respond to a held OK now show that they do; it was announced to screen readers and to
  nobody else.
- Removal actions are styled as removal - "Remove from My List" was a full violet button identical
  to Play.
- Loading a page shows the shape of what is arriving instead of a spinner on black with nothing
  focusable, and short loads no longer flash a spinner at all.
- Empty and failure states are measured and centred; their icons were rendering a third smaller than
  the code asked for, because padding was shrinking the box the glyph was drawn into.
- Settings states its version - the only place in the app that says what build you are running.
- A test now enforces that the four colours `colors.xml` shares with the palette actually match;
  they drive the launcher icon, the TV banner and the first frame before Compose exists.

## [0.6.1] - 2026-07-27
Two fixes to the new player controls, both found on a Google TV Streamer.

- The controls no longer disappear while you are looking at them. The panel hid itself five seconds after opening whether or not the highlight was sitting on a button, so pausing to decide cost you the panel - and the next press seeked the film instead of moving the highlight. It now stays for fifteen seconds once it holds focus, and every press restarts that.
- LEFT and RIGHT no longer fall out of the button row. The scrub bar underneath is full width, so the focus search preferred it to nothing at the ends of the row and dropped focus onto it, seeking as it went. The ends of the row are now dead ends.

## [0.6.0] - 2026-07-27
The app is now called **Nebula**, and it looks like it: a full visual overhaul onto one design system, its own logo and launcher banner, and a player whose every control is reachable from a Google TV Streamer remote.

- Named and badged: the app is Nebula everywhere - launcher label, TV banner, notifications, and the pairing page. The application id is unchanged, so this installs over your existing copy and keeps its place on the TV home screen.
- Custom logo: an adaptive launcher icon and a 16:9 TV banner drawn as vectors (a ringed orb with a violet-to-cyan gradient), so both stay sharp at whatever size a launcher asks for. Includes a monochrome layer for themed icons.
- One design system: a deep-space palette, the Outfit typeface bundled at four weights and scaled for 10-foot viewing, and shared shapes, spacings, buttons, badges, headings, progress bars and empty states. Every screen is built from those pieces rather than ad-hoc padding.
- Focus you can see across the room: cards and buttons now grow, gain a violet ring and glow when focused, instead of the near-invisible default outline.
- Home: a taller hero billboard with layered scrims, metadata as badges with the score picked out in accent, rails with accent-ticked headings, and Continue Watching cards that say how much is left ("22m left", "Watched").
- Navigation rail rebuilt with the Nebula mark, a hairline edge, and labels that stay readable when the rail is collapsed.
- Details, Search, Streams, Settings, pairing and the update prompt all restyled onto the same system.
- **Every player control now reachable from a Google TV Streamer remote.** That remote has no MENU, CAPTIONS or transport keys, so audio tracks, subtitles, speed and delay were genuinely unreachable on it. Pressing UP over the picture now opens an on-screen control row - Play/Pause, Restart, Audio & subtitles, Playback options, and Next episode when there is one - driven entirely by the D-pad. Every legacy keycode still works for other remotes and HDMI-CEC.
- Player OSD rebuilt: a scrub bar with a thumb that grows on focus, what is playing shown as chips (audio, subtitles, frame rate) instead of a pipe-separated line, the time you are seeking to shown as a signed offset while the seek is pending, and "Ends at HH:MM".
- Fixed the OSD staying on screen for the rest of the film after a pause that was ended by a media key or by another app releasing audio focus.
- Fixed the in-player menu never highlighting the focused tab.
- Buffering and seeking now say which they are, and a playback failure is a proper card with a Retry that takes focus.

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
