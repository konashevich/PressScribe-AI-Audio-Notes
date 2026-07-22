# make-binary

Build a PressScribe desktop binary **on this machine** (current OS and CPU arch). Do not ask the user for target OS, layout, or packaging options unless a hard blocker appears.

## Non-negotiable cross-platform rules

1. App source (`transcriber.py`, helpers, `requirements.txt`) stays **shared** Windows + Linux. Do not rewrite logic, imports, or paths “for this OS.”
2. `PressScribe.spec` is a **shared** packaging recipe. Edits must remain valid when building on Windows too (no hardcoded machine paths, no Linux-only fork of the spec). Prefer one shared `.spec` over separate OS specs.
3. `dist/` and `build/` are **local artifacts only** — already gitignored. Do not commit them. Do not publish a release unless the user explicitly asks.
4. A Windows `.exe` and a Linux binary are separate freeze outputs, not competing versions of the codebase.

## Current packaging defaults (keep unless broken)

- Recipe: `PressScribe.spec` (onedir via `COLLECT`, `upx=False`, Gemini-only excludes: `faster_whisper`, `numpy`, `ctranslate2`).
- Entry: `transcriber.py` with `datas=[('icon.ico', '.')]`, `hiddenimports=['notes_store', 'translate_languages']`, `pathex=[]`.
- Build from repo root with the project venv:

```bash
./venv/bin/python -m PyInstaller --noconfirm PressScribe.spec
```

On Windows (PowerShell), use the equivalent venv PyInstaller invocation against the same `PressScribe.spec`.

## What to do

1. Confirm venv + PyInstaller exist; install into the **existing project venv** only if missing. Do not invent a second environment.
2. Fact-check `PressScribe.spec` before editing: do not re-add excludes/datas/hiddenimports that are already correct; only change the shared recipe if the build fails or the layout is wrong.
3. Build the onedir binary for **this host**.
4. Ensure a launcher exists for this OS:
   - Linux: `run_binary.sh` → `./dist/PressScribe/PressScribe` (prefer `QT_QPA_PLATFORM=xcb` on Wayland sessions).
   - Leave `run_app.sh` / `python transcriber.py` as the portable Python/dev path.
5. Smoke-test: launch the frozen app and confirm the UI process starts without crash (on Linux use `DISPLAY` if a graphical session is available).
6. If this machine already has a desktop app-drawer entry for PressScribe (e.g. `~/.local/share/applications/pressscribe.desktop`), point it at `run_binary.sh` / the frozen binary instead of the venv Python script. Do not invent a new menu name; refresh the existing launcher.
7. Reply briefly with: artifact path, host OS/arch, and how to launch. Do not claim the project became Linux-only or Windows-only.

## Out of scope (unless the user asks)

- Removing dead Local/Qwen code from `transcriber.py`
- Migrating off `google.generativeai`
- Cross-compiling for another OS/arch from this machine
- GitHub Releases / uploading the binary
- Splitting into separate Windows/Linux `.spec` files
