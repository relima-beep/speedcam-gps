# SpeedCam GPS v7 — Android Studio source (experimental)

## Implemented in source
- CameraX rear camera MP4 with microphone audio when permitted.
- Attempts simultaneous front and rear camera capture as **two separate MP4 files** on supported devices; falls back to rear-only.
- Live GPS km/h overlay **in preview only**, and CSV telemetry.
- Requests camera electronic stabilization if supported.
- No intentional audio focus request to pause Spotify. Concurrent music and microphone behavior varies by device and music app.
- Effect Studio preview, named saved profiles, and Android MP4 sharing.
- **New in v7:** 3 random, safe goals per recording; distance, recording duration and dual-camera challenges; persistent XP and post-ride summary. Distance is an approximate GPS estimate and may drift.

## Not yet implemented
- GPS speed, frames, stickers, and filters **burned into exported video**.
- One combined picture-in-picture MP4; the two camera files are separate.
- Actual RTMPS live streaming (the live settings dialog is only a placeholder).
- Screen-off recording, robust background operation, and full device compatibility testing.
- Signed APK. This archive is **source code, not an APK**. It has not been compiled or tested on a device in this environment.

## Build
Open in Android Studio, install Android SDK 35 and JDK 17, sync Gradle, then Build > Build APK(s). Dependencies require internet. CameraX concurrent recording may require device-specific fixes. Minimum Android 10 (API 29). For Android 15+ permission and audio behavior varies by device.

## v7 changes
- `ChallengeEngine.kt`: offline random goals and XP persistence.
- `MainActivity.kt`: GPS distance accumulation and ride-end XP dialog.

## v8: Build APK on GitHub Actions
This version adds `.github/workflows/build-apk.yml` to attempt a debug APK build on GitHub's hosted Android build environment. It does **not** mean the app has compiled successfully. Upload the **contents** of this project folder to the root of a GitHub repository, open **Actions → Build SpeedCam GPS APK → Run workflow**, and after a successful run download the `SpeedCamGPS-debug-APK` artifact. Extract it to get `app-debug.apk` for testing. A build failure means code fixes are required before an APK exists.

The GitHub debug APK is for testing, not Play Store release. Do not install APKs from untrusted sources. Permissions and dual-camera functionality vary by device. GPS speed is only shown in preview, not burned into MP4; RTMPS streaming and combined PiP MP4 remain unimplemented.
