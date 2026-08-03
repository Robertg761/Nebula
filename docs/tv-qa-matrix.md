# Android TV QA Matrix (Balanced)

## Device matrix

1. Android TV Emulator (Google APIs, API 34)
2. Google TV physical device (Chromecast with Google TV class)
3. Non-Google OEM Android TV / streamer (different remote profile)

## Remote profiles

1. Standard D-pad remote (arrows, center, back, menu/info)
2. Remote variant with dedicated media keys (play/pause/ff/rew)

## Smoke checklist

1. Cold launch reaches usable UI (no black screen).
2. Initial focus appears on primary route control.
3. D-pad reaches all primary interactive controls on:
   - Home rails
   - Details
   - Streams
   - Search
   - Settings (including phone pairing)
4. Back policy follows: modal close -> route back -> app exit.
5. Watch Next deep link opens the right title at the right episode and
   resume position, and focus is recovered:

   ```bash
   adb shell am start -a android.intent.action.VIEW \
     -d "stremio-tv://watch-next?type=show&tmdb=1396&season=1&episode=2&position=60000"
   ```
6. Playback starts and remote controls work:
   - play/pause
   - seek forward/back
   - subtitles/audio menus
   - playback speed
   - video mode
   - back from player
7. Playback survives the surface being torn down and rebuilt:
   - Home, then return to the player: video comes back (not audio over black).
   - Returning does not restart the stream from the beginning.
   - Film content (23.976fps) that triggers a display-mode switch keeps playing.
8. Seek feel:
   - Holding LEFT/RIGHT scrubs smoothly; the OSD time tracks the remote and one
     seek is issued when the key is released, not one per repeat.
   - The spinner shows while a seek is in flight.
   - Holding RIGHT into the end of the file stops short instead of exiting.
9. Resume:
   - Back out mid-playback, reopen: resumes at that position, first frame is
     already at the resume point (no play-from-0:00 then jump).
   - Continue Watching shows the position after a Back press (not the stale one).
10. Settings round-trip:
   - Saving with a field left blank keeps the stored value and says so; the
     per-value Clear button still clears.
   - Phone pairing QR opens the form, submits, and the values land on the TV.
11. Logcat capture is clean: no focus-recovery give-ups (`TvFocus` tag), no
   unhandled exceptions during the run.

## Physical-device playback signoff

The emulator is useful for navigation and state transitions, but it cannot
sign off display cadence, HDR/Dolby Vision, HDMI passthrough, CEC, audio focus,
or vendor decoder behavior. Run the applicable cases below on both physical
device classes in the device matrix and attach a short recording plus filtered
logcat for every failure.

## Physical navigation performance capture

Run the same sustained D-pad path used by the macrobenchmark on the intended
Streamer build:

```bash
export ANDROID_SERIAL="<device serial>"
bash scripts/capture-tv-performance.sh
```

The capture records `gfxinfo`, `meminfo`, launch timing, and filtered `TvFocus`
and `NebulaDiagnostics` logs beneath
`apps/android-tv-host/app/build/outputs/tv-performance/`. Run it once without
the baseline profile and once with the accepted profile, keeping display mode,
network route, app data, and background workloads unchanged. Compare frame
counts/slow frames, longest frames, memory, GC pressure, and focus warnings;
do not treat emulator numbers as Streamer evidence.

1. Display cadence and surface lifecycle:
   - Play known 23.976, 24, 25, 50, and 59.94 fps samples on a display that
     exposes matching modes. Confirm one mode change, even cadence, correct
     audio sync, and no repeated black flashes.
   - Pause, change playback speed, Home/return, and force an HDMI mode switch.
     The frame-rate vote must clear while paused/backgrounded and reapply after
     playback resumes; video must return instead of leaving audio over black.
   - Repeat with a display that has no exact match. Playback must stay usable at
     the current mode rather than loop through fallback modes.
2. HDR and decoder paths:
   - Play SDR, HDR10, and Dolby Vision samples the device claims to support.
     Check correct colors/black level, stable first frame, and audio sync after
     seek and resume.
   - Play Dolby Vision on a non-Dolby-Vision device. The warning appears once
     for that file, controls remain responsive, and a compatible/fallback
     decode either plays correctly or fails with an actionable error.
   - Include H.264 and HEVC at 1080p plus the highest-bitrate 4K remux the
     device is expected to support. Watch memory, dropped frames, and decoder
     fallback in logcat for at least ten minutes.
3. Audio routes and passthrough:
   - On Android 13/API 33 or newer, connect an AVR/soundbar and verify Decode
     first, then Passthrough with each format the active sink reports: AC-3,
     E-AC-3/JOC, DTS, DTS-HD, and TrueHD where available. Unsupported formats
     must decode instead of becoming silent.
   - With two outputs connected, select TV speakers/Bluetooth while HDMI is
     idle. Passthrough must not borrow the idle HDMI sink's codecs.
   - While passthrough is playing, disconnect the AVR or change to a
     non-digital route. The player must switch to Decode, show the fallback
     message, and recover sound without reopening the app.
   - On API 26-32, confirm the capability-safe fallback: Passthrough is
     unavailable because Android cannot publicly identify the active media
     route, and Decode remains audible.
4. Audio focus, noisy routes, and media controls:
   - Start another media app, then start Nebula. Only the focus owner is
     audible. Exercise transient loss, permanent loss, and ducking with
     Assistant/alarm/another player.
   - A transient loss resumes only playback that Nebula auto-paused. A
     viewer-paused video must remain paused after focus returns.
   - Disconnect headphones/Bluetooth during playback. The video pauses before
     sound can jump to speakers.
   - Verify play, pause, seek, next, and stop from HDMI-CEC, Bluetooth media
     keys, and Assistant while the player is foregrounded. Background the app:
     its MediaSession must become inactive and must not restart playback. Return
     and confirm the paused session becomes controllable again.
5. Slow, stalled, and replaced streams:
   - Throttle a stream so the cache advances slowly. The buffering UI and
     buffered range must advance and the no-progress watchdog must not report a
     false failure while bytes are still arriving.
   - Stop cache progress completely and verify a bounded actionable failure,
     preserved resume position, and a working Retry. Retry an expired signed
     link and confirm the replacement resumes at the saved position.
   - While a track-list read is pending, retry the stream or advance to the next
     episode. Open Audio/Subtitles immediately after the replacement loads; the
     list must belong to the new file and must not remain empty because the old
     read consumed the refresh.
   - Retry repeatedly while the original file is still opening. Delayed
     START_FILE/FILE_LOADED/END_FILE callbacks from the abandoned load must not
     mark the replacement failed, finished, or playable before its own first
     frame.
6. Seek and end-of-file boundaries:
   - Tap and hold LEFT/RIGHT through several repeats, then issue a second seek
     before the first restart callback arrives. The newest preview must remain
     authoritative and clear only when it settles or its own timeout expires.
   - Seek into a rebuffer, Back during the pending seek, and reopen. Resume must
     use the requested target, not the pre-seek time.
   - Check a normal ending, a file truncated well before 90%, and a tiny clip
     shorter than five seconds. Position 0 of the tiny clip must not be marked
     watched; 90% must. The truncated file stays resumable.
7. Up Next and asynchronous races:
   - Let an untouched episode end and verify the countdown. Press a key near the
     end and verify the card waits for OK instead. Background during either the
     countdown or stream resolution: no episode may start until the viewer
     returns and asks.
   - Take every configured stream addon offline. The card must show the addon
     failure with Retry and stay in the player. Restore connectivity and retry.
   - Fail one addon while another answers: automatic matching still uses the
     healthy result. Return a healthy list with no matching release: the app
     opens the next episode's picker.
   - Use Next halfway through an episode, then cancel the resolving/failure
     card. The current episode must remain resumable, not become watched.
   - With TalkBack or Switch Access, invoke Play, Retry, and Cancel from the
     card's custom actions and confirm they match the remote-key behavior.
8. Subtitle sources and episode replacement:
   - Verify embedded, stream-supplied, and fetched external subtitles; change
     language/size/delay and confirm the next episode reapplies the language
     preference against its own track IDs.
   - Fetch a subtitle that same-origin redirects to a different-origin CDN.
     Capture the requests and confirm stream cookies/auth/custom headers are
     not forwarded to the CDN.
   - Exercise a failed response, oversized body, slow cancellation, and a
     response that arrives after Up Next replaces the file. No stale subtitle
     may appear in the new episode, and every failure must leave the menu usable.

## Automated checks

Run before manual device signoff:

```bash
source scripts/android-env.sh
(
  cd apps/android-tv-host
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
)
./scripts/run-tv-instrumentation.sh 26
./scripts/run-tv-instrumentation.sh 34
```

The instrumentation harness creates a temporary, isolated AVD from each
official x86 Android TV image and requires no TMDB or addon credential. It
covers cold launch, focus, D-pad entry to Settings, Back,
search/watch-next routing, and DataStore persistence. It does not load libmpv
or sign off playback hardware. Baseline-profile generation has its own
device-backed procedure in `docs/baseline-profile.md`; the physical playback
cases below remain manual.

Do not substitute a direct `:app:connectedDebugAndroidTest` invocation pointed at a personal TV.
Connected-test cleanup can uninstall the target package after a test-local `@After` has restored
its configuration, and a disconnect can skip that restoration entirely. The default Gradle
property now retains connected-test APKs and the tests refuse unguarded physical hardware. When
device-backed instrumentation is specifically needed, use the guarded wrapper:

```bash
source scripts/android-env.sh
export ANDROID_SERIAL="<adb serial>"
export NEBULA_PHYSICAL_TV_TEST_CONFIRMED=1
./scripts/run-tv-instrumentation-physical.sh
```

If Nebula is already installed, the wrapper requires a debuggable build so `run-as` can read its
private files. It stops the app, creates a mode-600 DataStore snapshot under the host's private
state directory, explicitly tells Gradle to leave the APK installed, then restores and compares
the snapshot after Gradle returns. A TV that stays disconnected cannot be restored remotely; the
wrapper keeps the recovery directory and prints an exact `--restore` command to run after it
reconnects. A non-debuggable personal/release install is refused before testing. The in-app
`@After`/`finally` blocks remain useful between cases, but are not claimed as final data
protection.

Run the manual checklist on a local TV emulator plus hardware. The automated
harness defaults to the host renderer because emulator 36.6 currently crashes
during Android TV boot with its bundled SwiftShader on Linux. The renderer
remains explicit and overridable through `NEBULA_EMULATOR_GPU`.

```bash
emulator -avd stremio_tv_34 -no-window -no-audio -no-boot-anim -gpu host -no-snapshot &
node scripts/run-gradle.mjs :app:installDebug
```

Physical-device signoff requires at least one Google TV class device and one
non-Google Android TV/OEM device when available.

## Artifact policy

Attach screenshots, logcat captures, and APKs to CI runs, GitHub Releases, or an
external QA storage location. Do not commit generated QA artifacts under
`artifacts/`, build output, or release APK files.

## Signoff template

- Build SHA:
- Device:
- Android API level:
- Remote profile:
- Result: PASS / FAIL
- Defects:
- Artifact location:
- Tester:
- Date:
