# PressScribe Android

Android app based on the desktop `PressScribe` workflow.

## Included

- Raw and polished text editors with the same split workflow
- Microphone recording inside the app
- Gemini audio transcription
- Gemini text polishing
- Self-hosted ASR transcription via configurable scheme, host/IP, port, and path
- Session save/open as JSON through Android's document picker
- `audio/*` share target so other apps can send audio files here for transcription
- Manual audio import from inside the app
- Light/dark theme, font size, and listen-mode settings

## Intentionally omitted

- Local on-device transcription
- Local on-device AI polishing

That matches the requested Android scope: Google/Gemini plus self-hosted IP/port setup only.

## Build

From this folder:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
.\gradlew.bat :app:assembleDebug
```

Debug APK output:

`app\build\outputs\apk\debug\app-debug.apk`

Release APK (sideload / GitHub):

```powershell
.\gradlew.bat :app:assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`

### Upload to GitHub Releases

```powershell
.\scripts\upload-github-apk.ps1 -Tag android-v1.2.0 -Title "Your title"
```

Or ask the Cursor agent: `/release-apk` (uses `.cursor/skills/upload-android-apk`).

CI: GitHub Actions workflow **Android Release APK** (`workflow_dispatch` or push tag `android-v*`).

## Share flow

1. In another Android app, use `Share`.
2. Choose `PressScribe`.
3. The shared audio is imported into the app and auto-transcribed with the currently selected transcription service.

## Notes

- Gemini requires an API key in Settings.
- Self-hosted ASR expects a multipart `audio` upload. The Android client accepts responses shaped like the desktop app:
  - `{"transcription":{"parsed_text":"..."}}`
  - or `{"text":"..."}`
