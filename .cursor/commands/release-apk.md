# release-apk
Build the Android release APK and upload it to GitHub Releases for this repo.

Follow the project skill `upload-android-apk` (`.cursor/skills/upload-android-apk/SKILL.md`) and run `transcription-android/scripts/upload-github-apk.ps1`.

**Do not ask the user for tag, title, or notes.** Choose them yourself from recent commits / changes (e.g. tag `android-YYYYMMDD-HHmm` or a short kebab slug from the main change; title = short human summary). Prefer JDK 17. Build, upload, and reply with only the release URL when finished.
