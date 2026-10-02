# Preview correction — 0.1.1

The 0.1.0 Pixel 10/API 37 user report confirmed an accepted physical PRIVATE preview pair and capture results from both main and telephoto cameras. It also identified incorrect orientation and a distorted telephoto preview.

The 0.1.1 correction separates Camera2's automatic PRIVATE sensor rotation from the app's display compensation, reverses TextureView stretching using the sensor-oriented dimensions, and fits with one pixel scale on both axes. Raw YUV uses its own sensor-to-display transform. Finder calibration shares the fitted content rectangle. A display listener handles 180° turns even when the configuration and view size do not change. Diagnostics include preview buffer/view dimensions and sensor/display/relative rotation.

Four new geometry tests check known Pixel portrait/landscape corner positions, PRIVATE/YUV agreement, roundness, centring, and fitting for 128 sensor/display/view/buffer combinations. Together with the existing policy tests this gives 15 unit tests. The Android workflow runs `testDebugUnitTest lintDebug assembleDebug`; its result and downloadable APK are under Actions for the fixing commit.

On-device confirmation is still needed: point at an upright printed square or circle, rotate through portrait and both landscape directions, and check that it stays upright and undistorted in both previews. Recheck calibration and a saved clip. The MP4 orientation remains fixed at recording start.

# Initial build verification — 0.1.0

Verified on 2 October 2026:

- JDK 17, AGP 9.4.0, Gradle 9.6.0, Android platform 37.0 and build tools 36.0.0.
- A clean `testDebugUnitTest lintDebug assembleDebug` run passed, followed by the same checks after the calibration aspect-ratio fix.
- **11 unit tests passed**, none skipped, no failures or errors.
- Strict lint (`warningsAsErrors=true`): **No issues found** locally. The online `AndroidGradlePluginVersion` upgrade-advisory rule is excluded so newer Gradle releases do not invalidate the pinned, tested AGP/Gradle pair; correctness checks remain strict.
- Debug APK built; `apksigner verify` succeeded. Manifest inspection confirms version 0.1.0/code 1, minimum API 29, target API 37, and only CAMERA/RECORD_AUDIO permissions.
- Gradle distribution checksum verified against its official SHA-256 and pinned in the wrapper.
- `git diff --check` passed; staged source was checked for credentials, personal contact information and machine-specific paths. SDK locations, build output, signing files and downloaded tooling are excluded from source control.

Code review covered the following paths. These are structural checks, **not on-device runtime tests**:

| Area | Reviewed behaviour |
| --- | --- |
| Primary source | Shared stream-binding policy assigns both primary preview and encoder to the discovered telephoto ID; every output is explicitly physically bound. No automatic logical-camera substitute. |
| Finder isolation | Finder owns a separate preview output; MediaRecorder receives only its own telephoto camera Surface, with no UI/GL/screen compositing. |
| Mode transitions | Camera mutation uses one worker; close/reopen waits for device closure; generation checks reject stale session callbacks. Known physical focus distances survive mode changes. |
| Pause/resume | Pause stops and attempts to save recording, closes session/device, clears focus and releases readers/surfaces after closure; resume reopens/refocuses. |
| Permissions | Camera denial prevents open and offers retry/settings; microphone denial requires choosing silent video. No storage or network permission. |
| Saved video | Output is pending until successful recorder stop; failed preparation, short/failed stop and recorder errors remove invalid rows and close descriptors/native resources. Own abandoned pending rows are cleaned on launch. |
| Error recovery | Dual PRIVATE → equal YUV → tele-only fallbacks; optical-only retry for rejected EIS; camera errors/frame watchdogs release resources and show an explicit retry route. |
| Calibration | Offset is in normalised full-sensor coordinates, accounting for display rotation and centre crops between 16:9 PRIVATE and 4:3 YUV previews. |

**No Android device or emulator was connected during these initial build checks.** The later user report confirms the dual PRIVATE preview session, but recording stream combinations, actual physical metering/focus propagation, stabilisation effectiveness, sustained FPS, optics, recording playback/audio and thermal behaviour remain unverified. Follow the README's first-Pixel-test checklist and share Diagnostics output. Do not interpret a compiled APK, session-support query or unit-test pass as confirmation of these hardware behaviours.
