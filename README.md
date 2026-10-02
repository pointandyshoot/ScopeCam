# ScopeCam

A video-first, offline Android camera for a **Pixel 10 (non-Pro)** with a **10× monocular over its 5× telephoto lens**. ScopeCam 0.1.3 is a device-testing build. The 1× finder stays on by default during recording, and stabilisation choices include a separate recording-oriented video mode.

## Build and install

Use Android Studio with JDK 17 and Android SDK 37, or install the SDK command-line tools and run:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0'
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set `ANDROID_HOME` to your SDK location or let Android Studio create the ignored `local.properties`. The wrapper uses Gradle 9.6.0; AGP 9.4.0 includes Kotlin support. Android 10/API 29 or later is required. GitHub Actions runs tests, strict lint and a debug build, and publishes **ScopeCam-debug** (APK) and **ScopeCam-verification** (reports). Download and extract the APK artifact from a successful run under the repository's Actions tab. No release signing or signing credentials are needed.

## Workflow

1. Grant Camera and Microphone permissions. Microphone denial permits an explicitly selected silent recording; camera denial has a retry/settings route.
2. **Target:** the large view is always telephoto; the small **1× finder** uses the physical main rear camera when a simultaneous session is accepted. No object recognition is used.
3. Centre a distant stationary subject in the large view. Choose **Calibrate**, then tap the same subject in the inset. The persisted reticle offset estimates where the monocular is looking. Repeat after remounting. Reset is available in the calibration dialog.
4. **Acquire** hides the finder. **Target** quickly brings it back. The independent finder toggle works while idle.
5. **Refocus** triggers centre AF once, then holds a successful lens position. Focus the monocular manually afterwards. If AF misses or times out, tap Refocus again. Options allows continuous centre AF; Focus / exposure provides an optional infinity request and small exposure adjustment.
6. Choose an advertised, encoder-supported telephoto video mode, then **Record**. **Stop** saves H.264/AAC MP4 in **Movies/ScopeCam**, visible in the gallery. Last video opens the latest saved clip.

Recording keeps the finder on by default. Version 0.1.3 enables this once on upgrade; later explicit opt-outs persist. **Options → Keep 1× finder while recording** can turn it off. Recording with the finder probes a three-stream session at the selected recording size/FPS. If rejected, the controller tries the YUV preview route and then telephoto alone. It does **not lower the selected recording resolution/FPS to retain the finder**. Change this option before recording; changing cameras halfway through a file is deliberately disabled. The inset, reticle and all controls are preview UI only, and never appear in the MP4.

Portrait and landscape previews fit the full sensor buffer without a digital zoom. The 1× inset follows the image aspect ratio within 212 × 150 dp, anchored at the top right, so unused black panels do not cover the telephoto view. Its dimensions update after rotation or switching between PRIVATE and YUV preview streams. PRIVATE previews account for Camera2's existing sensor rotation; raw YUV previews apply sensor rotation themselves. Both use the same aspect-preserving fit and respond to 180° display changes. Calibration is stored in sensor coordinates and rotates with the display. MP4 orientation is fixed when Record is pressed, so hold the phone in that orientation for the clip. Rotating during recording rotates the display but does not change the MP4 orientation tag. Leaving the app or locking the screen stops and attempts to save a valid recording; background recording is not implemented.

## Physical-camera selection and session architecture

Camera2 is used directly because physical output selection and physical request/result metadata are essential. Discovery enumerates rear logical cameras and their physical IDs. Focal length and sensor width determine approximate field of view: the physical lens nearest a conventional main-camera FOV is the finder; a substantially narrower lens is telephoto. There are **no hard-coded Pixel camera IDs**. This is a heuristic on unusual hardware: inspect diagnostics before using another phone. A phone with no exposed main/tele physical pair gets a clear error; the app never substitutes a logical-camera stream.

Every primary preview and encoder `OutputConfiguration` is assigned the discovered telephoto ID with `setPhysicalCameraId`. The finder has its own physical main-camera output. Logical active-camera metadata is shown separately: it must not be confused with the explicit output binding. No recording output ever targets the logical/default camera or the finder. `VideoRecorder` owns a MediaRecorder input Surface; there is no window capture, UI compositing or finder-to-encoder route.

Target first tries two physical PRIVATE/TextureView outputs. If the HAL rejects them, it tries two equal-sized physical YUV_420_888 outputs (largest common size no greater than 640×480), displayed at up to 10 updates/s. This fallback avoids abandoning multi-camera support just because PRIVATE stream combinations fail. It is lower-resolution and uses CPU conversion only for on-screen previews. Recorded frames still go directly to the telephoto encoder Surface. If both dual routes fail, telephoto remains usable and the inset is hidden with an explicit status. Independent-device concurrency sets are reported for diagnosis; opening two standalone devices is not used by this MVP.

Camera mutation is serialised on a worker. Each mode transition closes the old device and waits for `onClosed` before releasing preview surfaces/readers and opening the next session. Generation checks discard late callbacks. Errors and missing-frame watchdogs release resources. Valid recordings publish their pending MediaStore row only after a successful stop; failed or too-short files are deleted. On the next launch, the app removes its own abandoned pending rows. No broad storage permission is requested.

## Focus and exposure

A fixed region covers approximately **20% of the telephoto image width and height**. AF/AE regions use physical sensor coordinates when physical keys are exposed. Otherwise the logical sensor's central region is scaled by the estimated telephoto/logical FOV ratio and left to the HAL's mapping. This mapping, AF propagation and reported physical regions require a Pixel test; the app cannot guarantee how a manufacturer's HAL weights black borders. Unsupported metering controls are omitted and diagnostics expose region support/results rather than pretending they worked.

After `FOCUSED_LOCKED`, a returned **physical telephoto** focus distance is held with AF OFF if independent lens-distance control is exposed. Otherwise AF AUTO remains in its triggered lock; no logical-camera lens distance is mistaken for the telephoto distance. AF miss/time-out is reported without claiming a successful lock. Continuous centre AF is configurable. Infinity is enabled only through an exposed independent physical lens-distance control and is optional: it may not be optimal for a particular monocular. A known independent physical focus distance is carried into mode/recording transitions; a trigger-only lock is re-acquired after restarting a session. Pause/resume clears the lock and refocuses; focus is not persisted across app launches.

## Stabilisation and video modes

Google specifies optical and electronic stabilisation on the Pixel 10 wide and telephoto cameras. That **does not establish which modes third-party Camera2 physical streams expose**. User diagnostics from a Pixel 10/API 37 running 0.1.0 confirm an accepted dual PRIVATE preview session with physical results from the main and telephoto cameras, and returned preview stabilisation mode 2. Preview orientation/distortion was reported and addressed in 0.1.1. Subsequent user feedback reports successful recording and good focus; it also identified black panels around the finder, addressed in 0.1.2. Further feedback confirms useful finder operation during recording. Effective AF/AE mapping, repeatable 4K/60 recording acceptance and thermal behaviour remain **unverified**. Diagnostics are the source of truth for the installed phone and OS version.

The implementation queries physical OIS and video-stabilisation capabilities. Its automatic policy is:

| Recording mode | Requested stabilisation, if exposed |
| --- | --- |
| 1080p 30 | PREVIEW_STABILIZATION (HAL coordinates OIS/EIS), otherwise video EIS, otherwise OIS |
| 4K 30 / 1080p 60 | OIS; EIS at these modes is not assumed |
| Video stabilisation preference | Ordinary recording EIS at ≤1080p30 if exposed; otherwise OIS/off |
| Optical-only preference | OIS, otherwise off |

It does not force OIS ON alongside ordinary EIS ON; Android warns that they may interact poorly. Preview stabilisation permits the HAL to coordinate optical stabilisation. Stabilisation is supplied before session creation and returned OIS/EIS values are shown in diagnostics. If an EIS session fails after stream fallbacks, the controller retries with optical-only settings. **Stabilisation** selects Automatic (prefers preview+video mode 2), Video stabilisation (recording mode 1), or Optical only. The video comparison choice keeps the same conservative OIS fallback at 4K/60. Diagnostics show logical and telephoto returned modes separately, whether independent physical stabilisation keys exist, and retain the last recording results after Stop. Returned mode values confirm metadata, not the amount of shake correction. Compare modes at 1080p30 before deciding which works best with the monocular. No custom post-processing or gyro recording is included. ScopeCam cannot eliminate all shake at this magnification, and monocular movement relative to the phone is not corrected by phone OIS.

Candidate 1080p30, 4K30 and 1080p60 modes are intersected with the **physical telephoto** recorder sizes, minimum frame duration, physical/logical FPS ranges and H.264 encoder limits. Only those candidates are offered. This is not a promise that a particular multi-output session will be accepted or that variable AE FPS ranges sustain the selected rate in low light. Failed recording sessions give an error; the app does not silently change cameras or recording formats. There are no high-speed, HDR or proprietary Google Camera processing paths.

## Diagnostics and first Pixel test

Open **Diagnostics → Copy**. It contains app version, model/API, logical and physical IDs, focal lengths/FOV, selected lenses, stream candidates, AF/AE/physical-key support, session probe/fallback results, requested and returned stabilisation, physical-result IDs, lens distance, crop and FPS/region metadata, plus each preview's buffer/view dimensions and sensor/display rotation. It excludes account details, device serial/IMEI, location, filenames and other personal information. Diagnostics are local and copied only on request.

Please test:

- Cover the 1× lens: large preview and recording must stay telephoto. Cover telephoto: the large view goes dark while a working finder stays visible. Check the output bindings/physical results.
- Try Target → Acquire → Target, calibration, repeated Refocus, successful/missed AF, and manual monocular focus while phone focus is held.
- Record short clips in each offered mode; verify audio, gallery visibility, correct orientation, absence of inset/UI and actual telephoto image.
- Compare Automatic, Video stabilisation and Optical only at 1080p30. Inspect returned stabilisation and AF/AE regions with black monocular borders.
- Try Keep finder while recording and check accepted/fallback session results, video continuity and heat during a longer clip. Session acceptance alone cannot establish thermal reliability.
- Deny/regrant camera or microphone, background/resume, screen lock, rotate, rapidly switch modes and stop a very short clip. Inspect saved files and absence of abandoned pending videos.

Automated unit tests cover FOV selection, encoder binding invariants, mode-duration/FPS limits, stabilisation policies, centre regions, calibration rotation, and preview geometry across all four sensor/display orientations, portrait/landscape/square/inset viewports and PRIVATE/YUV buffer sizes. Lint and APK compilation catch static Android/API issues. These do not validate the Pixel HAL, optical alignment, audio/video quality or real stabilisation; those need the physical device.

## Privacy and scope

Only Camera and Microphone permissions. No Internet permission, analytics, advertisements, accounts, cloud calls or runtime network dependency. Settings/calibration are local. No automatic subject detection, generic camera features, still-photo implementation or shake indicators. MediaRecorder is isolated so a future encoder/gyro-assisted stabilisation implementation can replace it without adding the targeting overlay to the video path. Source is MIT-licensed; the Gradle wrapper has its upstream licence.

## Research references

- [Pixel 10 official specifications](https://fi.google.com/about/phones/pixel-10-specs)
- [Android Camera2 multi-camera API](https://developer.android.com/media/camera/camera2/multi-camera): physical outputs; guaranteed YUV/RAW replacements versus device-specific extra streams.
- [OutputConfiguration](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration): explicit physical-camera output selection.
- [CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest): metering, focus and stabilisation controls; ordinary EIS guarantees at ≤1080p/30 and OIS interactions.
- [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes): Gradle 9.6, JDK 17, SDK build tools 36.
