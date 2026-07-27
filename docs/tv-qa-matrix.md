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
   - Board/Home
   - Discover
   - Meta Details / Streams
   - Settings
   - Search
4. Back policy follows: modal close -> route back -> app exit.
5. Deep link opens expected route and focus is recovered.
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
10. Native fallback route behavior does not loop.
11. Diagnostics export contains:
   - recent host events
   - back decision records
   - focus recovery logs

## Automated checks

Run before manual device signoff:

```bash
pnpm typecheck
pnpm lint
pnpm build
pnpm test:contracts
pnpm test:tv-smoke
cd apps/android-tv-host
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The instrumentation matrix must include API 26 and API 34 TV emulators before a
release candidate is marked ready. Physical-device signoff still requires at
least one Google TV class device and one non-Google Android TV/OEM device when
available.

Note: the CI instrumentation jobs are currently non-blocking because emulator
36.6.11 on GitHub runner images crashes with every headless renderer. Until
that is fixed upstream, run the instrumentation gate on a local TV emulator:

```bash
source scripts/android-env.sh
emulator -avd stremio_tv_34 -no-window -no-audio -no-boot-anim -gpu host -no-snapshot &
node scripts/run-gradle.mjs :app:connectedDebugAndroidTest
```

## Artifact policy

Attach screenshots, logcat captures, and APKs to CI runs, GitHub Releases, or an
external QA storage location. Do not commit generated QA artifacts under
`artifacts/`, generated Android web assets, or release APK files.

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
