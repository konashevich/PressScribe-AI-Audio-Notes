# -*- mode: python ; coding: utf-8 -*-
# Cross-platform PyInstaller recipe (Windows + Linux). Build on the target OS/arch:
#   python -m PyInstaller PressScribe.spec
# Produces an onedir layout under dist/PressScribe/ (folder + executable).


a = Analysis(
    ['transcriber.py'],
    pathex=[],
    binaries=[],
    datas=[('icon.ico', '.')],
    hiddenimports=['notes_store', 'translate_languages'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # Local ASR stack is UI-disabled (GEMINI_ONLY_UI); keep it out of the freeze.
    excludes=['faster_whisper', 'numpy', 'ctranslate2'],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='PressScribe',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon='icon.ico',
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='PressScribe',
)
