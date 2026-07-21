---
name: upload-android-apk
description: >-
  Build the PressScribe Android release APK and upload it to GitHub Releases
  (PressScribe-release.apk). Use when the user asks to upload an APK, publish
  an Android release, create a GitHub release with an APK, run /release-apk,
  or automate APK upload for this repo. Never ask the user for tag/title/notes;
  choose them and upload immediately.
---

# Upload Android APK to GitHub

Repo: `konashevich/PressScribe-AI-Audio-Notes`  
App module: `transcription-android/`  
Published asset name: `PressScribe-release.apk`

## When asked

1. **Do not ask** for tag, title, or notes. Decide them yourself and proceed.
2. Pick a unique tag from git + time, e.g. `android-YYYYMMDD-HHmm`, or a short kebab slug from the main recent change if unused (`gh release view <tag>` must fail).
3. Title: one short line summarizing what changed (from `git log` / diff since last Android release). Notes: omit so the script uses `--generate-notes`.
4. Prefer the upload script over inventing ad-hoc commands.
5. Use **JDK 17** (`JAVA_HOME=C:\Program Files\Java\jdk-17` when present).
6. Do **not** force-push tags or delete existing releases unless the user explicitly asks.
7. Reply with the release URL only (plus asset name if useful). No permission prompts.

## Preferred command (Windows)

From repo root:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
.\transcription-android\scripts\upload-github-apk.ps1 `
  -Tag "<chosen-tag>" `
  -Title "<chosen-title>"
```

- New release: new unused tag + title
- Replacing APK on an existing tag: only if the user explicitly asked to update that release (`--clobber` via script)

## Manual equivalent

```powershell
cd transcription-android
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :app:assembleRelease
copy app\build\outputs\apk\release\app-release.apk app\build\outputs\apk\release\PressScribe-release.apk
gh release create <tag> app\build\outputs\apk\release\PressScribe-release.apk --title "<title>" --generate-notes
```

## CI alternative

Workflow: `.github/workflows/android-release.yml`  
Trigger: `workflow_dispatch` or push tag `android-v*`.

## Notes

- Release builds are signed with the debug keystore for sideload/GitHub installs (see `app/build.gradle.kts`).
- Requires `gh` authenticated with permission to create releases on this repo.
- Do not commit keystores, API keys, or `local.properties`.
