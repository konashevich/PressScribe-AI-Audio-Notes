# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['transcriber.py'], # Or your main script name
    pathex=['c:\\Users\\akona\\OneDrive\\Dev\\Smart Recorder Transcriber'], # Or the path to your project
    binaries=[],
    datas=[('icon.ico', '.')],  # <--- ADD THIS LINE (assuming icon.ico is in the same dir as transcriber.py)
                                 # If icon.png, use ('icon.png', '.')
    hiddenimports=['notes_store', 'translate_languages'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas, # This now includes your icon for runtime access
    [],
    name='PressScribe',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False, # For --windowed
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon='icon.ico'  # <--- ENSURE THIS IS PRESENT (path to your .ico file)
)

