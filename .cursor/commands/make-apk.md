# make-apk

Build the PressScribe **Android release APK** and **publish it to GitHub Releases** for this repo. Do everything needed end-to-end. Do not ask the user for tag, title, or release notes unless a hard blocker appears.

## Follow first

Read and follow the project skill `upload-android-apk` (`.cursor/skills/upload-android-apk/SKILL.md`).

## Non-negotiable rules

1. **Do not ask** for tag, title, or notes. Pick them from recent commits / diff (e.g. tag `android-YYYYMMDD-HHmm` or unused `android-v*` slug; title = one short human summary of what changed).
2. Published asset name is always **`PressScribe-release.apk`**.
3. App module: `transcription-android/`. Do not commit APKs, keystores, API keys, or `local.properties`.
4. Prefer **JDK 17** for local builds.
5. Reply with the **GitHub release URL** when finished (asset name optional). No permission prompts.

## What to do

1. **Choose release metadata** — verify tag is unused (`gh release view <tag>` must fail unless user explicitly asked to replace that release).
2. **Build release APK** — one of:
   - **Windows:** `transcription-android/scripts/upload-github-apk.ps1` (preferred; builds + uploads).
   - **Linux/macOS:** `./gradlew :app:assembleRelease` in `transcription-android/`, stage `PressScribe-release.apk`, then `gh release create` (see skill manual steps).
   - **No local JDK / build fails:** trigger CI via `gh workflow run android-release.yml` with chosen tag + title, or push tag `android-v*` if appropriate; wait and report release URL.
3. **Upload** — create or update GitHub Release with `PressScribe-release.apk` (`gh release create` / `gh release upload --clobber` only when updating an existing release the user asked to refresh).
4. **Verify** — `gh release view <tag>` shows the APK asset.

## Out of scope (unless the user asks)

- Play Store upload / signing with a production keystore
- Building desktop binaries (use `/make-binary`)
- Committing unrelated Android or desktop changes just to ship an APK
