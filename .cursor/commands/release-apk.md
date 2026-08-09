# release-apk

Alias for `/make-apk` — same workflow.

Build the Android release APK and upload it to GitHub Releases for this repo.

Follow `.cursor/commands/make-apk.md` and the project skill `upload-android-apk` (`.cursor/skills/upload-android-apk/SKILL.md`).

**Do not ask the user for tag, title, or notes.** Choose them yourself from recent commits / changes (e.g. tag `android-YYYYMMDD-HHmm` or a short kebab slug from the main change; title = short human summary). Prefer JDK 17. Build, upload, and reply with only the release URL when finished.
