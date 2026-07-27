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

## Automated checks

Run before manual device signoff:

```bash
source scripts/android-env.sh
cd apps/android-tv-host
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

That is the whole automated gate, and it is what CI runs. There is no
instrumentation suite in the repo: the androidTest sources went with the
WebView shell, so everything below the JVM tests is manual.

Run the manual checklist on a local TV emulator plus hardware. The emulator
needs `-gpu host` on Linux; headless software renderers crash emulator 36.x.

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
