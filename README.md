# OpenRecord

A small, open-source screen recorder for Android 10+. Minimal dark interface in English, native Android controls, no ads, watermarks, analytics, or network permission. MIT licensed.

## Download

[Download OpenRecord.apk](https://github.com/helixoff/OpenRecord/raw/refs/heads/main/dist/OpenRecord.apk)

Open the APK on your phone and allow installation from your file manager. This is a test build signed with a local development key, not a Play Store release.

## Use

1. Set **Audio** and **Quality** in the app. Settings are saved automatically.
2. Tap **Add to Quick Settings** and confirm, or add **Screen recording** from the Quick Settings editor.
3. Tap the tile. Android asks which screen or app to capture. Recording starts after **3 seconds**, with the countdown in the live notification. There is no separate countdown screen. You can cancel from the notification or tile.
4. Stop from the live notification / Samsung Now Bar, the tile, or the app.

The tile opens the system capture picker directly, using your saved settings. The first run also requests audio and notification permissions. MP4 videos appear in your gallery under `Movies/OpenRecord`.

## Features

- Device audio, microphone, both, or no audio.
- H.264 video and AAC audio in MP4, 1080p or 720p at 30 fps.
- Quick Settings start/stop tile.
- Live notification with an elapsed timer and Stop action.
- Android 16 Live Updates request for the status chip / Samsung Now Bar.
- A cancellable 3-second countdown in the live notification, without a separate screen.

## Platform limitations

Android only captures internal audio from apps that allow AudioPlaybackCapture. Protected content, calls, or apps that disallow capture can produce silence or a black picture. OpenRecord does not bypass these restrictions. Use headphones when mixing microphone and device audio to avoid recording speaker output twice.

Live Updates and Now Bar placement depend on the Android / One UI version and notification settings. The app uses one foreground-service notification for both the notification shade and eligible live surfaces. Compact chips show status; expand them for actions. Older devices retain the standard notification and tile.

Video dimensions are fixed at recording start. Rotation and app-window resizing can introduce letterboxing. Stopping system projection finalizes the recording. Force-stopping the app or killing its process may lose an unfinished MP4.

## Build

Requirements: JDK 17+, Android SDK Platform 36. The Gradle wrapper uses Gradle 8.9 and Android Gradle Plugin 8.7.3. There are no app library dependencies.

```sh
./gradlew assembleDebug lintDebug
```

On Windows, use `gradlew.bat`. Open the project in Android Studio or set `ANDROID_HOME` / `local.properties` to your SDK. Gradle produces `app/build/outputs/apk/debug/app-debug.apk`.

For a standalone Windows build without Gradle/Maven, install SDK Build Tools 36.0.0 and run:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\build-apk.ps1
```

This runs aapt2, javac, D8, zipalign and apksigner and produces `dist/OpenRecord.apk`. Its local debug key lives in `.tools/debug.keystore` and is excluded from Git. For distribution, use a persistent release signing key. Gradle and standalone development builds can use different keys; Android only permits updates signed with the same key.

## Source

- `MainActivity` — minimal settings and tile setup.
- `CaptureActivity` — transparent system-picker entry point.
- `RecordingService` — foreground notification, projection and MediaStore lifecycle.
- `RecorderEngine` — surface H.264 encoder, PCM audio capture/mixing, AAC encoder and MP4 muxing. Samples stream to disk rather than accumulating the recording in memory.
- `RecordTileService` — direct start/stop from Quick Settings.

## Validation

The initial capture and live-chip stop flow was tested on a Samsung Galaxy A15 running Android 16 / One UI 8.5. A vendor-specific shutdown issue was fixed so revoking projection drains buffered media and finalizes the MP4. The Quick Settings flow, simplified interface and countdown need a final device check after installation.

Suggested checks: cancel screen consent; cancel countdown; record each audio mode; stop from Now Bar, tile, app and system projection control; reopen the saved MP4; start a second recording; check rotation and lock-screen behavior.

Platform documentation: [MediaProjection](https://developer.android.com/media/grow/media-projection), [audio capture](https://developer.android.com/media/platform/av-capture), [Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update).
