import sys
import threading
import time
import json
import os
import io
import shutil
import tempfile
import mimetypes
import pyaudio # Added explicit import for device listing
from datetime import datetime

# --- Qt Imports ---
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QTextEdit, QPushButton, QSplitter, QFileDialog,
    QMessageBox, QInputDialog, QLabel, QDialog, QDialogButtonBox,
    QStackedWidget, QListWidget, QListWidgetItem, QAbstractItemView,
    QFrame, QMenu, QSizePolicy, QRadioButton, QButtonGroup, QLineEdit,
    QScrollArea, QCheckBox,
)
from PySide6.QtCore import Qt, Signal, QObject, QEvent, QTimer, QSize, QUrl
from PySide6.QtGui import (
    QAction, QFont, QActionGroup, QIcon, QColor, QTextCharFormat, QTextCursor,
    QTextOption, QDesktopServices, QGuiApplication,
)

# --- Core Logic Imports ---
import speech_recognition as sr

from notes_store import (
    NotesStore,
    NotesLoadError,
    ORIGIN_POLISHED_TEXT,
    ORIGIN_RAW_TEXT,
)
from translate_languages import (
    DEFAULT_TRANSLATE_POLISH_PROMPT,
    TRANSLATE_LANGUAGES,
    code_from_picker_label,
    is_configured_translate_language,
    language_labels_for_picker,
    normalize_translate_language_code,
    resolve_stored_polish_prompt,
    resolve_stored_translate_polish_prompt,
    resolve_translate_prompt,
    translate_button_code,
)

_genai = None
_numpy = None
_whisper_model_cls = None


def get_genai():
    global _genai
    if _genai is None:
        import google.generativeai as genai_module
        _genai = genai_module
    return _genai


def get_numpy():
    global _numpy
    if _numpy is None:
        import numpy as numpy_module
        _numpy = numpy_module
    return _numpy


def get_whisper_model_cls():
    global _whisper_model_cls
    if _whisper_model_cls is None:
        from faster_whisper import WhisperModel
        _whisper_model_cls = WhisperModel
    return _whisper_model_cls


def default_qwen_asr_url():
    configured_url = os.environ.get("QWEN_ASR_SERVER_URL", "").strip()
    return configured_url


def load_env_file(env_path):
    env_values = {}
    try:
        with open(env_path, "r", encoding="utf-8") as env_file:
            for raw_line in env_file:
                line = raw_line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                env_values[key.strip()] = value.strip().strip('"').strip("'")
    except FileNotFoundError:
        return {}
    return env_values

# When True, non-Gemini backends stay in the codebase but are hidden from UI
# and never selected at runtime (desktop Gemini-only product surface).
GEMINI_ONLY_UI = True

# --- Default Settings ---
DEFAULT_SETTINGS = {
    "api_key": "",
    "gemini_model": "gemini-flash-lite-latest",
    "ai_service": "Gemini",
    "theme": "dark",
    "font_size": 11,
    "local_model_url": "http://localhost:1234/v1/chat/completions",
    "system_prompt": (
        "Your task is to turn rough spoken or typed notes into clear, well-structured writing. "
        "You will receive a user's text. Rewrite it into polished prose: fix grammar, remove filler "
        "(um, uh, ah, er, hmm, and equivalents in any language such as э, а-а, ну), drop repeated "
        "false starts, improve clarity, and reorganize ideas when that helps. "
        "You may reorder or rephrase freely as long as you preserve the author's intent and meaning. "
        "Do not invent facts that were not implied. Your sole output must be the rewritten text only — "
        "no greetings, comments, questions, labels, or explanations. Do not answer questions that appear "
        "in the user's text; treat everything as material to rewrite."
    ),
    "translate_system_prompt": DEFAULT_TRANSLATE_POLISH_PROMPT,
    "translate_language": "",
    "auto_save_notes": True,
    "listen_mode": None,  # Must be chosen in welcome or Settings
    "microphone_index": None, # None means default
    "transcription_service": "Gemini", # "Gemini", "Google", "Local", or "Qwen 3 ASR Server"
    "whisper_model": "base", # "tiny", "base", "small", etc.
    "qwen_asr_url": default_qwen_asr_url(),
    "qwen_asr_timeout_seconds": 360,
    # First-run welcome must be completed before using the app.
    "welcome_completed": False,
}

GEMINI_API_KEYS_URL = "https://aistudio.google.com/api-keys"

# --- Communication signals for thread-safe UI updates ---
class Communicate(QObject):
    text_ready = Signal(str)
    import_text_ready = Signal(str)
    error = Signal(str)
    status = Signal(str)
    polish_ready = Signal(str)
    transcription_finished = Signal()
    import_finished = Signal()
    polish_finished = Signal()
    translate_finished = Signal()
    microphone_ready = Signal(object)  # device_index
    microphone_got_audio = Signal()
    microphone_failed = Signal(str)
    microphone_capture_lost = Signal(str)

def copy_text_to_clipboard(text):
    """Copy via Qt clipboard. Avoid pyperclip on Linux — it can hang the UI thread."""
    clipboard = QGuiApplication.clipboard()
    if clipboard is None:
        return
    clipboard.setText(text or "")


def safe_debug(message):
    """Print debug text without crashing on Windows console encodings."""
    text = str(message)
    try:
        print(text)
    except UnicodeEncodeError:
        encoding = getattr(sys.stdout, "encoding", None) or "utf-8"
        print(text.encode(encoding, errors="replace").decode(encoding, errors="replace"))


def safe_error_text(exc):
    try:
        return str(exc)
    except Exception:
        return repr(exc)


def configure_stdio_encoding():
    """Avoid Windows charmap crashes when logging non-ASCII transcripts."""
    if not sys.platform.startswith("win"):
        return
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def resource_path(relative_path):
    """ Get absolute path to resource, works for dev and for PyInstaller """
    try:
        # PyInstaller creates a temp folder and stores path in _MEIPASS
        base_path = sys._MEIPASS
    except Exception:
        base_path = os.path.abspath(".")
    return os.path.join(base_path, relative_path)


VALID_LISTEN_MODES = ("Click and Hold", "Click and Stick")

# PortAudio overflow/underflow; skip the chunk instead of ending the take.
_PA_OVERFLOW_ERRNOS = {-9981, -9988, "-9981", "-9988"}
MIN_LISTEN_SECONDS = 1.0
# ~21ms at 48 kHz. Small enough that stop_event is noticed quickly when
# the device is still delivering audio, without the PipeWire callback
# truncation we saw with stream callbacks on this host.
_MIC_FRAMES_PER_BUFFER = 1024
MIC_STALL_SECONDS = 1.5
MIC_FIRST_AUDIO_STALL_SECONDS = 4.0
MIC_REOPEN_COOLDOWN_SECONDS = 1.5
MIC_MAX_REOPENS = 8
MIC_MAX_EMPTY_REOPENS = 1
GEMINI_TRANSCRIBE_PROMPT = (
    "Transcribe this entire audio from beginning to end. "
    "Return only the spoken words as plain text."
)

_portaudio_lock = threading.Lock()
_portaudio_host = None


def get_portaudio_host():
    """One process-wide PortAudio host. Never terminate it on the Listen path."""
    global _portaudio_host
    with _portaudio_lock:
        if _portaudio_host is None:
            _portaudio_host = pyaudio.PyAudio()
        return _portaudio_host


class MicrophoneCaptureSession:
    """Capture PCM on one owner thread. Other threads may only set stop_event.

    The UI thread must never join this thread or call PortAudio. Blocking
    stream.read / stop_stream / terminate can hang on ALSA/PipeWire; waiting
    for that from Qt is what surfaces the desktop "not responding" dialog
    and loses the take on force-quit.
    """

    def __init__(self, device_index, on_first_audio=None):
        self.device_index = device_index
        self.stop_event = threading.Event()
        self._opened = threading.Event()
        self._thread = None
        self._lock = threading.Lock()
        self.frames = []
        self.sample_rate = None
        self.sample_width = None
        self.error = None
        self.lost_error = None
        self.opened_at = None
        self.last_append_at = None
        self.captured_bytes = 0
        self._got_audio = False
        self.on_first_audio = on_first_audio

    def start(self):
        self._thread = threading.Thread(
            target=self._run,
            name="PressScribeMic",
            daemon=True,
        )
        self._thread.start()

    def wait_opened(self, timeout=8.0):
        return self._opened.wait(timeout)

    def is_running(self):
        return self._thread is not None and self._thread.is_alive()

    def stop(self, wait=False, timeout=0.0):
        self.stop_event.set()
        if wait:
            self.wait_until_finished(timeout=timeout)

    def wait_until_finished(self, timeout=None):
        if self._thread is not None:
            self._thread.join(timeout=timeout)

    def snapshot(self):
        with self._lock:
            frames = list(self.frames)
            rate = self.sample_rate
            width = self.sample_width
        return b"".join(frames), rate, width

    def captured_duration_s(self):
        stats = self.capture_stats()
        nbytes = stats["captured_bytes"]
        rate = stats["sample_rate"]
        width = stats["sample_width"]
        if not nbytes or not rate or not width:
            return 0.0
        return nbytes / float(rate * width)

    def capture_stats(self):
        with self._lock:
            return {
                "captured_bytes": self.captured_bytes,
                "sample_rate": self.sample_rate,
                "sample_width": self.sample_width,
                "opened_at": self.opened_at,
                "last_append_at": self.last_append_at,
                "got_audio": self._got_audio,
            }

    def adopt_pcm(self, raw_audio, sample_rate, sample_width):
        """Keep PCM from a stalled session when opening a replacement stream.

        Do not stamp last_append_at here. The heartbeat must treat a
        replacement stream that never appends as stalled, not as live audio.
        """
        with self._lock:
            if raw_audio:
                self.frames = [raw_audio]
                self.captured_bytes = len(raw_audio)
                self._got_audio = True
            if sample_rate:
                self.sample_rate = sample_rate
            if sample_width:
                self.sample_width = sample_width

    def _append(self, data):
        if not data:
            return
        chunk = bytes(data)
        first = False
        with self._lock:
            self.frames.append(chunk)
            self.captured_bytes += len(chunk)
            self.last_append_at = time.monotonic()
            if not self._got_audio:
                self._got_audio = True
                first = True
        if first and self.on_first_audio is not None:
            try:
                self.on_first_audio()
            except Exception as callback_error:
                print(f"DEBUG: First-audio callback failed: {callback_error}")

    def _run(self):
        last_error = None
        pa = get_portaudio_host()
        for delay_s in (0.0, 0.12, 0.25, 0.45, 0.80):
            if self.stop_event.is_set():
                self._opened.set()
                return
            if delay_s:
                time.sleep(delay_s)
            if self.stop_event.is_set():
                self._opened.set()
                return
            stream = None
            try:
                info = pa.get_device_info_by_index(self.device_index)
                rate = self.sample_rate or int(info.get("defaultSampleRate") or 16000)
                width = self.sample_width or pa.get_sample_size(pyaudio.paInt16)
                stream = pa.open(
                    format=pyaudio.paInt16,
                    channels=1,
                    rate=rate,
                    input=True,
                    input_device_index=self.device_index,
                    frames_per_buffer=_MIC_FRAMES_PER_BUFFER,
                )
                self.sample_rate = rate
                self.sample_width = width
                self.opened_at = time.monotonic()
                self._opened.set()
                print(f"DEBUG: Microphone capture started at {rate} Hz.")
                self._read_available(stream)
                return
            except Exception as e:
                last_error = e
                print(f"DEBUG: Microphone open failed (will retry): {e}")
            finally:
                # Close after the read loop so the device can be reused on
                # reconnect. Skip close when the user already stopped: close()
                # can hold the GIL and freeze Qt, and the take is already snapshotted.
                if not self.stop_event.is_set():
                    self._close_stream_quietly(stream)
        self.error = last_error or RuntimeError("device unavailable")
        self._opened.set()

    def _close_stream_quietly(self, stream):
        if stream is None:
            return
        try:
            stream.close()
        except Exception:
            pass

    def _read_available(self, stream):
        """Read only bytes already queued. Never block in stream.read()."""
        chunk = _MIC_FRAMES_PER_BUFFER
        last_append = time.monotonic()
        got_any = self._got_audio
        while not self.stop_event.is_set():
            now = time.monotonic()
            stall_limit = MIC_STALL_SECONDS if got_any else MIC_FIRST_AUDIO_STALL_SECONDS
            if (now - last_append) >= stall_limit:
                print("DEBUG: Capture loop stall-exit.")
                break
            try:
                available = int(stream.get_read_available() or 0)
            except Exception as avail_error:
                print(f"DEBUG: Microphone available-check failed: {avail_error}")
                self.lost_error = str(avail_error)
                break
            if available <= 0:
                if self.stop_event.wait(0.02):
                    break
                continue
            try:
                data = stream.read(min(available, chunk), exception_on_overflow=False)
            except Exception as read_error:
                if self.stop_event.is_set():
                    break
                errno = getattr(read_error, "errno", None)
                if errno in _PA_OVERFLOW_ERRNOS:
                    continue
                print(f"DEBUG: Microphone read failed: {read_error}")
                self.lost_error = str(read_error)
                break
            if data:
                self._append(data)
                last_append = time.monotonic()
                got_any = True


def last_audio_dir():
    """Single parked recording slot that survives app restarts until replaced or cleared."""
    path = os.path.join(app_config_dir(), "last_audio")
    os.makedirs(path, exist_ok=True)
    return path


def app_config_dir():
    """Stable per-user config directory (separate from source / install folder)."""
    if sys.platform.startswith("win"):
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        return os.path.join(base, "PressScribe")
    if sys.platform == "darwin":
        return os.path.expanduser("~/Library/Application Support/PressScribe")
    xdg = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(xdg, "PressScribe")


def ensure_app_data_files():
    """
    Create the config dir and migrate legacy cwd settings/notes once if needed.
    Returns (settings_path, notes_path).
    """
    config_dir = app_config_dir()
    os.makedirs(config_dir, exist_ok=True)
    settings_path = os.path.join(config_dir, "settings.json")
    notes_path = os.path.join(config_dir, "saved_notes.json")

    cwd_settings = os.path.abspath("settings.json")
    cwd_notes = os.path.abspath("saved_notes.json")
    if not os.path.exists(settings_path) and os.path.isfile(cwd_settings):
        shutil.copy2(cwd_settings, settings_path)
    if not os.path.exists(notes_path) and os.path.isfile(cwd_notes):
        shutil.copy2(cwd_notes, notes_path)
    return settings_path, notes_path


def is_plausible_gemini_api_key(key):
    text = (key or "").strip()
    if len(text) < 20:
        return False
    if any(ch.isspace() for ch in text):
        return False
    return True


# --- Modern Dark Theme Stylesheet (QSS) ---
DARK_STYLESHEET = """
QWidget {
    background-color: #2b2b2b;
    color: #f0f0f0;
    /* font-family: 'Segoe UI'; Removed for programmatic control */
    /* font-size: 11pt; Removed for programmatic control */
}
QMainWindow {
    background-color: #2b2b2b;
}
QMenuBar {
    background-color: #3c3c3c;
    color: #f0f0f0;
}
QMenuBar::item {
    background-color: #3c3c3c;
    color: #f0f0f0;
    padding: 4px 10px;
}
QMenuBar::item:selected {
    background-color: #555;
}
QMenu {
    background-color: #3c3c3c;
    border: 1px solid #555;
}
QMenu::item {
    color: #f0f0f0;
    padding: 4px 20px;
}
QMenu::item:selected {
    background-color: #0078d7;
}
QTextEdit {
    background-color: #252526;
    color: #f0f0f0;
    border: 1px solid #3c3c3c;
    border-radius: 4px;
    padding: 5px;
}
QPushButton {
    background-color: #3c3c3c;
    border: 1px solid #555;
    padding: 8px;
    border-radius: 4px;
}
QPushButton:hover {
    background-color: #4f4f4f;
}
QPushButton:pressed {
    background-color: #0078d7;
}
QLabel {
    /* font-size: 10pt; Let specific labels or global app font handle this if needed */
    font-weight: bold;
}
QRadioButton {
    color: #f0f0f0;
    spacing: 8px;
}
QRadioButton::indicator {
    width: 16px;
    height: 16px;
    border: 2px solid #c8c8c8;
    border-radius: 10px;
    background-color: #1e1e1e;
}
QRadioButton::indicator:hover {
    border-color: #ffffff;
}
QRadioButton::indicator:checked {
    background-color: #0078d7;
    border-color: #4da3ff;
}
QCheckBox {
    color: #f0f0f0;
    spacing: 8px;
}
QCheckBox::indicator {
    width: 16px;
    height: 16px;
    border: 2px solid #c8c8c8;
    border-radius: 3px;
    background-color: #1e1e1e;
}
QCheckBox::indicator:hover {
    border-color: #ffffff;
}
QCheckBox::indicator:checked {
    background-color: #0078d7;
    border-color: #4da3ff;
}
QSplitter::handle {
    background-color: #3c3c3c;
}
QSplitter::handle:hover {
    background-color: #555;
}
QSplitter::handle:horizontal {
    width: 2px;
}
QScrollBar:vertical {
    border: none;
    background: #252526;
    width: 12px;
    margin: 15px 0 15px 0;
    border-radius: 0px;
}
QScrollBar::handle:vertical {
    background-color: #4f4f4f;
    min-height: 30px;
    border-radius: 6px;
}
QScrollBar::handle:vertical:hover {
    background-color: #5f5f5f;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0px;
}
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {
    background: none;
}
"""

# --- Modern Light Theme Stylesheet (QSS) ---
LIGHT_STYLESHEET = """
QWidget {
    background-color: #f0f0f0; /* Light gray background */
    color: #000000; /* Black text */
    /* font-family: 'Segoe UI'; Removed for programmatic control */
    /* font-size: 11pt; Removed for programmatic control */
}
QMainWindow {
    background-color: #f0f0f0;
}
QMenuBar {
    background-color: #e0e0e0; /* Lighter menubar */
    color: #000000;
}
QMenuBar::item {
    background-color: #e0e0e0;
    color: #000000;
    padding: 4px 10px;
}
QMenuBar::item:selected {
    background-color: #c0c0c0; /* Slightly darker gray for selection */
}
QMenu {
    background-color: #e8e8e8; /* Light menu background */
    border: 1px solid #b0b0b0; /* Lighter border */
}
QMenu::item {
    color: #000000;
    padding: 4px 20px;
}
QMenu::item:selected {
    background-color: #0078d7; /* Blue accent for selection */
    color: #ffffff; /* White text on selection */
}
QTextEdit {
    background-color: #ffffff; /* White background for text areas */
    color: #000000; /* Black text */
    border: 1px solid #c0c0c0; /* Gray border */
    border-radius: 4px;
    padding: 5px;
}
QPushButton {
    background-color: #e0e0e0; /* Light gray buttons */
    border: 1px solid #b0b0b0;
    color: #000000;
    padding: 8px;
    border-radius: 4px;
}
QPushButton:hover {
    background-color: #d0d0d0; /* Slightly darker on hover */
}
QPushButton:pressed {
    background-color: #0078d7; /* Blue accent when pressed */
    color: #ffffff;
}
QLabel {
    /* font-size: 10pt; */
    font-weight: bold;
    color: #000000;
}
QRadioButton {
    color: #000000;
    spacing: 8px;
}
QRadioButton::indicator {
    width: 16px;
    height: 16px;
    border: 2px solid #555555;
    border-radius: 10px;
    background-color: #ffffff;
}
QRadioButton::indicator:hover {
    border-color: #0078d7;
}
QRadioButton::indicator:checked {
    background-color: #0078d7;
    border-color: #005a9e;
}
QCheckBox {
    color: #000000;
    spacing: 8px;
}
QCheckBox::indicator {
    width: 16px;
    height: 16px;
    border: 2px solid #555555;
    border-radius: 3px;
    background-color: #ffffff;
}
QCheckBox::indicator:hover {
    border-color: #0078d7;
}
QCheckBox::indicator:checked {
    background-color: #0078d7;
    border-color: #005a9e;
}
QSplitter::handle {
    background-color: #c0c0c0; /* Gray splitter handle */
}
QSplitter::handle:hover {
    background-color: #b0b0b0;
}
QSplitter::handle:horizontal {
    width: 2px;
}
QScrollBar:vertical {
    border: none;
    background: #ffffff; /* White scrollbar track */
    width: 12px;
    margin: 15px 0 15px 0;
    border-radius: 0px;
}
QScrollBar::handle:vertical {
    background-color: #c0c0c0; /* Gray scrollbar handle */
    min-height: 30px;
    border-radius: 6px;
}
QScrollBar::handle:vertical:hover {
    background-color: #b0b0b0;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0px;
}
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {
    background: none;
}
"""

class EditPromptDialog(QDialog):
    def __init__(self, parent=None, current_prompt=""):
        super().__init__(parent)
        self.setWindowTitle("Edit AI Prompt")
        self.setMinimumSize(500, 350) # Make the dialog larger

        layout = QVBoxLayout(self)

        self.prompt_label = QLabel("System Prompt:")
        layout.addWidget(self.prompt_label)

        self.prompt_text_edit = QTextEdit()
        self.prompt_text_edit.setWordWrapMode(QTextOption.WordWrap) # Enable word wrap
        self.prompt_text_edit.setPlainText(current_prompt)
        layout.addWidget(self.prompt_text_edit)

        # Standard buttons
        button_box = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        button_box.accepted.connect(self.accept)
        button_box.rejected.connect(self.reject)
        layout.addWidget(button_box)

    def get_prompt_text(self):
        return self.prompt_text_edit.toPlainText()

class RecordButton(QPushButton):
    """Press/release for Click and Hold. Does not shadow QPushButton.pressed/released."""
    listenPressed = Signal()
    listenReleased = Signal()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.press_hold_enabled = False

    def mousePressEvent(self, event):
        super().mousePressEvent(event)
        if self.press_hold_enabled and event.button() == Qt.LeftButton:
            self.listenPressed.emit()

    def mouseReleaseEvent(self, event):
        super().mouseReleaseEvent(event)
        if self.press_hold_enabled and event.button() == Qt.LeftButton:
            self.listenReleased.emit()


class EditorSplitPane(QWidget):
    """Splitter child with equal preferred size; ignores toolbar-driven min widths."""

    def sizeHint(self):
        return QSize(400, 400)

    def minimumSizeHint(self):
        return QSize(0, 0)


class WelcomeSetupDialog(QDialog):
    """Blocking first-run setup: Gemini API key instructions + explicit Listen mode."""

    def __init__(self, parent=None, existing_api_key="", theme="dark"):
        super().__init__(parent)
        self.setWindowTitle("Welcome to PressScribe")
        self.setModal(True)
        self.setMinimumWidth(580)
        self._listen_mode = None
        self._setup_completed = False
        self._quit_requested = False
        # Dialog has no parent; apply theme so radio/checkbox indicators stay visible.
        self.setStyleSheet(DARK_STYLESHEET if theme == "dark" else LIGHT_STYLESHEET)

        outer = QVBoxLayout(self)
        outer.setSpacing(10)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        body = QWidget()
        layout = QVBoxLayout(body)
        layout.setSpacing(12)

        title = QLabel("Welcome to PressScribe")
        title_font = QFont(title.font())
        title_font.setPointSize(max(title_font.pointSize() + 4, 14))
        title_font.setBold(True)
        title.setFont(title_font)
        layout.addWidget(title)

        intro = QLabel(
            "Before you start, set up Gemini transcription and choose how the Listen button works. "
            "You cannot continue until you pick a Listen mode and enter a valid API key."
        )
        intro.setWordWrap(True)
        layout.addWidget(intro)

        api_heading = QLabel("1. Gemini API key")
        api_heading_font = QFont(api_heading.font())
        api_heading_font.setBold(True)
        api_heading.setFont(api_heading_font)
        layout.addWidget(api_heading)

        api_help = QLabel(
            "PressScribe uses Google Gemini to transcribe and polish audio. "
            "Create or copy an API key from your Google account on the Google AI Studio API keys page, "
            "then paste it below."
        )
        api_help.setWordWrap(True)
        layout.addWidget(api_help)

        link_row = QHBoxLayout()
        open_keys_button = QPushButton("Open API keys page")
        open_keys_button.clicked.connect(self._open_api_keys_page)
        link_row.addWidget(open_keys_button)
        link_label = QLabel(
            f'<a href="{GEMINI_API_KEYS_URL}">{GEMINI_API_KEYS_URL}</a>'
        )
        link_label.setOpenExternalLinks(True)
        link_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextBrowserInteraction)
        link_row.addWidget(link_label, stretch=1)
        layout.addLayout(link_row)

        self.api_key_edit = QLineEdit()
        self.api_key_edit.setEchoMode(QLineEdit.EchoMode.Password)
        self.api_key_edit.setPlaceholderText("Paste your Gemini API key here")
        if existing_api_key:
            self.api_key_edit.setText(existing_api_key)
        self.api_key_edit.textChanged.connect(self._update_continue_enabled)
        layout.addWidget(self.api_key_edit)

        key_tools = QHBoxLayout()
        self.show_key_checkbox = QCheckBox("Show API key")
        self.show_key_checkbox.toggled.connect(self._toggle_key_visibility)
        key_tools.addWidget(self.show_key_checkbox)
        test_key_button = QPushButton("Test key")
        test_key_button.clicked.connect(self._test_api_key)
        key_tools.addWidget(test_key_button)
        key_tools.addStretch(1)
        layout.addLayout(key_tools)

        listen_heading = QLabel("2. Listen button mode (required)")
        listen_heading_font = QFont(listen_heading.font())
        listen_heading_font.setBold(True)
        listen_heading.setFont(listen_heading_font)
        layout.addWidget(listen_heading)

        listen_intro = QLabel(
            "Choose exactly one mode. Nothing is pre-selected — you must pick how recording should work."
        )
        listen_intro.setWordWrap(True)
        layout.addWidget(listen_intro)

        self.listen_group = QButtonGroup(self)
        self.listen_group.setExclusive(True)

        self.hold_radio = QRadioButton("Click and Hold")
        hold_help = QLabel(
            "Press and hold Listen to record. Release the button to stop recording and start transcription."
        )
        hold_help.setWordWrap(True)
        hold_help.setStyleSheet("margin-left: 22px; margin-bottom: 8px;")

        self.stick_radio = QRadioButton("Click and Stick")
        stick_help = QLabel(
            "Click Listen once to start recording (it stays on). Click Listen again to stop and transcribe."
        )
        stick_help.setWordWrap(True)
        stick_help.setStyleSheet("margin-left: 22px; margin-bottom: 8px;")

        self.listen_group.addButton(self.hold_radio)
        self.listen_group.addButton(self.stick_radio)
        self.hold_radio.toggled.connect(self._on_listen_toggled)
        self.stick_radio.toggled.connect(self._on_listen_toggled)

        layout.addWidget(self.hold_radio)
        layout.addWidget(hold_help)
        layout.addWidget(self.stick_radio)
        layout.addWidget(stick_help)

        # Ensure no mode is checked on first show.
        self.hold_radio.setAutoExclusive(False)
        self.stick_radio.setAutoExclusive(False)
        self.hold_radio.setChecked(False)
        self.stick_radio.setChecked(False)
        self.hold_radio.setAutoExclusive(True)
        self.stick_radio.setAutoExclusive(True)

        layout.addStretch(1)
        scroll.setWidget(body)
        outer.addWidget(scroll, stretch=1)

        button_row = QHBoxLayout()
        quit_button = QPushButton("Quit")
        quit_button.clicked.connect(self._quit_app)
        button_row.addWidget(quit_button)
        button_row.addStretch(1)
        self.continue_button = QPushButton("Continue")
        self.continue_button.setDefault(True)
        self.continue_button.setEnabled(False)
        self.continue_button.clicked.connect(self._accept_setup)
        button_row.addWidget(self.continue_button)
        outer.addLayout(button_row)

        screen = QApplication.primaryScreen()
        if screen is not None:
            available = screen.availableGeometry()
            self.resize(min(640, available.width() - 40), min(720, int(available.height() * 0.85)))

        self._update_continue_enabled()

    def _open_api_keys_page(self):
        QDesktopServices.openUrl(QUrl(GEMINI_API_KEYS_URL))

    def _toggle_key_visibility(self, checked):
        mode = QLineEdit.EchoMode.Normal if checked else QLineEdit.EchoMode.Password
        self.api_key_edit.setEchoMode(mode)

    def _on_listen_toggled(self, checked):
        if not checked:
            return
        if self.hold_radio.isChecked():
            self._listen_mode = "Click and Hold"
        elif self.stick_radio.isChecked():
            self._listen_mode = "Click and Stick"
        self._update_continue_enabled()

    def _update_continue_enabled(self):
        has_key = is_plausible_gemini_api_key(self.api_key_edit.text())
        has_mode = self._listen_mode in VALID_LISTEN_MODES
        self.continue_button.setEnabled(has_key and has_mode)

    def api_key(self):
        return self.api_key_edit.text().strip()

    def listen_mode(self):
        return self._listen_mode

    def quit_requested(self):
        return self._quit_requested

    def _test_api_key(self):
        key = self.api_key()
        if not is_plausible_gemini_api_key(key):
            QMessageBox.warning(
                self,
                "API key",
                "Enter a complete Gemini API key first (no spaces, at least 20 characters).",
            )
            return
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            genai = get_genai()
            genai.configure(api_key=key)
            models = list(genai.list_models())
            if not models:
                raise RuntimeError("No models were returned for this key.")
            QMessageBox.information(self, "API key OK", "This Gemini API key works.")
        except Exception as exc:
            QMessageBox.warning(
                self,
                "API key test failed",
                f"Could not verify this key with Google Gemini:\n\n{safe_error_text(exc)}",
            )
        finally:
            QApplication.restoreOverrideCursor()

    def _accept_setup(self):
        if not self.continue_button.isEnabled():
            return
        if not is_plausible_gemini_api_key(self.api_key()):
            QMessageBox.warning(
                self,
                "API key",
                "Enter a complete Gemini API key (no spaces, at least 20 characters).",
            )
            return
        if self._listen_mode not in VALID_LISTEN_MODES:
            QMessageBox.warning(self, "Listen mode", "Please choose Click and Hold or Click and Stick.")
            return
        self._setup_completed = True
        self.accept()

    def _quit_app(self):
        self._quit_requested = True
        self._setup_completed = True
        self.reject()

    def reject(self):
        if self._setup_completed or self._quit_requested:
            super().reject()
            return
        QMessageBox.information(
            self,
            "Setup required",
            "Please choose a Listen mode, enter your Gemini API key, and click Continue.\n"
            "Or click Quit to exit PressScribe.",
        )

    def closeEvent(self, event):
        if self._setup_completed or self._quit_requested:
            event.accept()
            return
        event.ignore()
        self.reject()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("PressScribe")
        self.setGeometry(100, 100, 1080, 720)

        # --- Set Window Icon ---
        # Make sure 'icon.ico' or 'icon.png' is in the same directory as your script,
        # or provide the full path to the icon file.
        self.setWindowIcon(QIcon(resource_path("icon.ico"))) # Or resource_path("icon.png")

        self.settings_file, self.notes_file = ensure_app_data_files()
        self.env_file = ".env"
        self.savings_dir = "savings"
        self.imports_dir = os.path.join(tempfile.gettempdir(), "pressscribe_imports")
        if not os.path.exists(self.savings_dir):
            os.makedirs(self.savings_dir)
        os.makedirs(self.imports_dir, exist_ok=True)
        self._cleanup_orphan_imports()

        self.settings = {}
        self.env_settings = load_env_file(self.env_file)
        self.load_settings()
        self.notes_store = NotesStore(self.notes_file)
        self.saved_notes = []
        self.notes_load_failed = False
        self.active_note_id = None
        self.opened_note_id = None
        self.selected_note_ids = set()
        self.imported_audio_path = None
        self.imported_audio_name = None
        self.is_import_transcribing = False
        self._pending_import_delete = None
        self._suppress_polished_autosave = False
        self._notes_persist_timer = QTimer(self)
        self._notes_persist_timer.setSingleShot(True)
        self._notes_persist_timer.setInterval(400)
        self._notes_persist_timer.timeout.connect(self._flush_saved_notes)

        self.comm = Communicate()
        self.comm.text_ready.connect(self.insert_transcribed_text)
        self.comm.import_text_ready.connect(self.insert_imported_transcript)
        self.comm.error.connect(self.show_error_message)
        self.comm.status.connect(self.show_status_message)
        self.comm.polish_ready.connect(self.display_polished_text)
        self.comm.transcription_finished.connect(self.finish_record_processing)
        self.comm.import_finished.connect(self.finish_import_processing)
        self.comm.polish_finished.connect(self.finish_polish_processing)
        self.comm.translate_finished.connect(self.finish_translate_processing)
        self.comm.microphone_ready.connect(self._on_microphone_ready)
        self.comm.microphone_got_audio.connect(self._on_microphone_got_audio)
        self.comm.microphone_failed.connect(self._on_microphone_failed)
        self.comm.microphone_capture_lost.connect(self._on_microphone_capture_lost)

        self.is_recording = False
        self._mic_start_generation = 0
        self.recognizer = sr.Recognizer()
        self.recognizer.energy_threshold = 4000
        self.recognizer.dynamic_energy_threshold = True
        
        # For new "transcribe on release" logic
        self.audio_frames = []
        self.current_sample_rate = None
        self.current_sample_width = None
        self._mic_session = None
        self._mic_lingering_session = None
        self._stopping_recording = False
        self._listen_started_at = None
        self._heard_listen_audio = False
        self._mic_reopen_count = 0
        self._mic_empty_reopen_count = 0
        self._last_mic_reopen_at = 0.0
        self._mic_heartbeat_timer = QTimer(self)
        self._mic_heartbeat_timer.setInterval(250)
        self._mic_heartbeat_timer.timeout.connect(self._on_mic_heartbeat)

        self.whisper_model = None # Lazy load
        self.spinner_frames = ["◐", "◓", "◑", "◒"]
        self.button_spinner_states = {}
        
        # For ghost cursor
        self.cursor_positions = {
            "raw_text_area": 0,
            "polished_text_area": 0
        }

        self.init_ui()
        self.load_saved_notes()
        self.apply_settings() # This will also call _refresh_all_ghost_cursors
        self._restore_parked_audio()
        self._preload_heavy_deps_async()

    def init_ui(self):
        self.create_menu()
        self.statusBar().showMessage("Ready")

        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        main_layout = QVBoxLayout(central_widget)

        view_row = QHBoxLayout()
        self.editor_view_button = QPushButton("Editor")
        self.editor_view_button.setCheckable(True)
        self.editor_view_button.setChecked(True)
        self.editor_view_button.clicked.connect(lambda: self.show_main_view(0))
        self.notes_view_button = QPushButton("Saved Notes")
        self.notes_view_button.setCheckable(True)
        self.notes_view_button.clicked.connect(lambda: self.show_main_view(1))
        view_row.addWidget(self.editor_view_button)
        view_row.addWidget(self.notes_view_button)
        view_row.addStretch(1)
        main_layout.addLayout(view_row)

        self.main_stack = QStackedWidget()
        main_layout.addWidget(self.main_stack)

        # --- Editor page ---
        editor_page = QWidget()
        editor_layout = QVBoxLayout(editor_page)
        editor_layout.setContentsMargins(0, 0, 0, 0)

        self.import_strip = QFrame()
        self.import_strip.setObjectName("import_strip")
        self.import_strip.setFrameShape(QFrame.StyledPanel)
        self.import_strip.setVisible(False)
        self.import_strip.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.import_strip.setMaximumHeight(40)
        import_layout = QHBoxLayout(self.import_strip)
        import_layout.setContentsMargins(8, 4, 8, 4)
        import_layout.setSpacing(8)
        self.import_label = QLabel("No audio imported")
        self.import_label.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.import_label.setWordWrap(False)
        self.import_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.transcribe_import_button = QPushButton("▶ Transcribe")
        self.transcribe_import_button.clicked.connect(self.transcribe_imported_audio)
        self.clear_import_button = QPushButton("Clear")
        self.clear_import_button.clicked.connect(self.clear_imported_audio)
        for import_button in (self.transcribe_import_button, self.clear_import_button):
            self._configure_editor_toolbar_button(import_button)
        import_layout.addWidget(self.import_label, stretch=1)
        import_layout.addWidget(self.transcribe_import_button)
        import_layout.addWidget(self.clear_import_button)
        editor_layout.addWidget(self.import_strip, 0)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        editor_layout.addWidget(splitter, 1)

        # Raw Transcription Panel
        raw_panel = EditorSplitPane()
        raw_layout = QVBoxLayout(raw_panel)
        raw_layout.addWidget(QLabel("Raw Transcription"))
        self.raw_text_area = QTextEdit()
        self.raw_text_area.setObjectName("raw_text_area") # For ghost cursor
        raw_layout.addWidget(self.raw_text_area)

        raw_buttons_layout = QHBoxLayout()
        raw_buttons_layout.setSpacing(4)
        self.record_button = RecordButton("🔴 Listen")

        self.polish_button = QPushButton("✨ Polish")
        self.polish_button.clicked.connect(self.polish_text)
        self.translate_button = QPushButton("🌐 Translate")
        self.translate_button.clicked.connect(self.polish_and_translate_text)
        self.copy_raw_button = QPushButton("📋 Copy")
        self.copy_raw_button.clicked.connect(lambda: copy_text_to_clipboard(self.raw_text_area.toPlainText()))
        self.save_raw_note_button = QPushButton("💾 Save")
        self.save_raw_note_button.clicked.connect(self.manual_save_raw_note)
        self.delete_raw_button = QPushButton("🗑️ Clear")
        self.delete_raw_button.clicked.connect(self.clear_raw_text_area_content)
        for button in (
            self.record_button,
            self.polish_button,
            self.translate_button,
            self.copy_raw_button,
            self.save_raw_note_button,
            self.delete_raw_button,
        ):
            self._configure_editor_toolbar_button(button)
            raw_buttons_layout.addWidget(button)
        raw_layout.addLayout(raw_buttons_layout)

        # Polished Text Panel
        polished_panel = EditorSplitPane()
        polished_layout = QVBoxLayout(polished_panel)
        polished_layout.addWidget(QLabel("Polished Text"))
        self.polished_text_area = QTextEdit()
        self.polished_text_area.setObjectName("polished_text_area") # For ghost cursor
        polished_layout.addWidget(self.polished_text_area)

        polished_buttons_layout = QHBoxLayout()
        polished_buttons_layout.setSpacing(4)
        self.copy_polished_button = QPushButton("📋 Copy")
        self.copy_polished_button.clicked.connect(lambda: copy_text_to_clipboard(self.polished_text_area.toPlainText()))
        self.save_polished_note_button = QPushButton("💾 Save")
        self.save_polished_note_button.clicked.connect(self.manual_save_polished_note)
        self.delete_polished_button = QPushButton("🗑️ Clear")
        self.delete_polished_button.clicked.connect(self.clear_polished_text_area_content)
        self.delete_all_button = QPushButton("🗑️ Clear All")
        self.delete_all_button.clicked.connect(self.clear_all_text)
        for button in (
            self.copy_polished_button,
            self.save_polished_note_button,
            self.delete_polished_button,
            self.delete_all_button,
        ):
            self._configure_editor_toolbar_button(button)
            polished_buttons_layout.addWidget(button)
        polished_layout.addLayout(polished_buttons_layout)

        # Equal columns: left toolbar is wider, so override pane size hints (EditorSplitPane)
        # and force a 50/50 split after the window is shown.
        for panel in (raw_panel, polished_panel):
            panel.setMinimumWidth(0)
            panel.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        splitter.addWidget(raw_panel)
        splitter.addWidget(polished_panel)
        splitter.setChildrenCollapsible(False)
        splitter.setStretchFactor(0, 1)
        splitter.setStretchFactor(1, 1)
        splitter.setSizes([1, 1])
        self.editor_splitter = splitter
        self._editor_splitter_balanced = False
        self.main_stack.addWidget(editor_page)

        # --- Saved Notes page ---
        notes_page = QWidget()
        notes_layout = QVBoxLayout(notes_page)
        notes_layout.setContentsMargins(0, 0, 0, 0)
        self.notes_stack = QStackedWidget()
        notes_layout.addWidget(self.notes_stack)

        notes_list_page = QWidget()
        notes_list_layout = QVBoxLayout(notes_list_page)
        notes_header = QHBoxLayout()
        notes_header.addWidget(QLabel("Saved Notes"))
        notes_header.addStretch(1)
        self.clear_note_selection_button = QPushButton("Clear selection")
        self.clear_note_selection_button.clicked.connect(self.clear_note_selection)
        self.delete_selected_notes_button = QPushButton("Delete selected")
        self.delete_selected_notes_button.clicked.connect(self.delete_selected_notes)
        self.delete_all_notes_button = QPushButton("Delete all")
        self.delete_all_notes_button.clicked.connect(self.delete_all_saved_notes)
        notes_header.addWidget(self.clear_note_selection_button)
        notes_header.addWidget(self.delete_selected_notes_button)
        notes_header.addWidget(self.delete_all_notes_button)
        notes_list_layout.addLayout(notes_header)

        self.notes_list = QListWidget()
        self.notes_list.setSelectionMode(QAbstractItemView.NoSelection)
        self.notes_list.itemClicked.connect(self._on_note_item_activated)
        self.notes_list.itemChanged.connect(self._on_note_item_changed)
        self.notes_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.notes_list.customContextMenuRequested.connect(self._show_notes_context_menu)
        notes_list_layout.addWidget(self.notes_list)
        self.notes_empty_label = QLabel("No saved notes")
        self.notes_empty_label.setAlignment(Qt.AlignCenter)
        notes_list_layout.addWidget(self.notes_empty_label)
        self.notes_stack.addWidget(notes_list_page)

        note_detail_page = QWidget()
        note_detail_layout = QVBoxLayout(note_detail_page)
        detail_header = QHBoxLayout()
        self.note_back_button = QPushButton("← Back")
        self.note_back_button.clicked.connect(self.close_saved_note)
        self.note_origin_label = QLabel("")
        self.note_copy_button = QPushButton("📋 Copy")
        self.note_copy_button.clicked.connect(self.copy_opened_note)
        detail_header.addWidget(self.note_back_button)
        detail_header.addWidget(self.note_origin_label)
        detail_header.addStretch(1)
        detail_header.addWidget(self.note_copy_button)
        note_detail_layout.addLayout(detail_header)
        self.note_detail_edit = QTextEdit()
        self.note_detail_edit.textChanged.connect(self._on_note_detail_changed)
        note_detail_layout.addWidget(self.note_detail_edit)
        self.notes_stack.addWidget(note_detail_page)

        self.main_stack.addWidget(notes_page)

        # Ghost cursor setup
        self.raw_text_area.installEventFilter(self)
        self.polished_text_area.installEventFilter(self)
        self.raw_text_area.cursorPositionChanged.connect(self._handle_cursor_position_changed)
        self.polished_text_area.cursorPositionChanged.connect(self._handle_cursor_position_changed)
        self.polished_text_area.textChanged.connect(self._on_polished_text_changed)

    def create_menu(self):
        menu_bar = self.menuBar()

        # File Menu
        file_menu = menu_bar.addMenu("File")
        open_action = QAction("📂 Open...", self)
        open_action.triggered.connect(self.open_file)
        file_menu.addAction(open_action)
        open_audio_action = QAction("🎧 Open Audio...", self)
        open_audio_action.triggered.connect(self.open_audio_file)
        file_menu.addAction(open_audio_action)
        save_new_action = QAction("💾 Save & New", self)
        save_new_action.triggered.connect(self.save_and_new)
        file_menu.addAction(save_new_action)
        file_menu.addSeparator()
        exit_action = QAction("Exit", self)
        exit_action.triggered.connect(self.close)
        file_menu.addAction(exit_action)

        # View Menu
        view_menu = menu_bar.addMenu("View")
        editor_action = QAction("Editor", self)
        editor_action.triggered.connect(lambda: self.show_main_view(0))
        notes_action = QAction("Saved Notes", self)
        notes_action.triggered.connect(lambda: self.show_main_view(1))
        view_menu.addAction(editor_action)
        view_menu.addAction(notes_action)

        # Settings Menu
        settings_menu = menu_bar.addMenu("Settings")

        if not GEMINI_ONLY_UI:
            # --- Transcription Service Menu ---
            trans_service_menu = settings_menu.addMenu("Transcription Service")
            self.trans_service_group = QActionGroup(self)
            google_trans_action = QAction("Google Speech (free)", self, checkable=True)
            google_trans_action.setData("Google")
            google_trans_action.setToolTip(
                "Free Google speech recognition. Falls back to Gemini when an API key is configured."
            )
            google_trans_action.triggered.connect(lambda: self.set_transcription_service("Google"))
            local_trans_action = QAction("Local (Faster-Whisper)", self, checkable=True)
            local_trans_action.setData("Local")
            local_trans_action.triggered.connect(lambda: self.set_transcription_service("Local"))
            qwen_trans_action = QAction("Qwen 3 ASR Server", self, checkable=True)
            qwen_trans_action.setData("Qwen 3 ASR Server")
            qwen_trans_action.triggered.connect(lambda: self.set_transcription_service("Qwen 3 ASR Server"))
            self.trans_service_group.addAction(google_trans_action)
            self.trans_service_group.addAction(local_trans_action)
            self.trans_service_group.addAction(qwen_trans_action)
            trans_service_menu.addAction(google_trans_action)
            trans_service_menu.addAction(local_trans_action)
            trans_service_menu.addAction(qwen_trans_action)

            trans_service_menu.addSeparator()
            trans_service_menu.addAction("Set Qwen ASR Server URL...", self.set_qwen_asr_url)
            trans_service_menu.addAction("Set Qwen ASR Timeout...", self.set_qwen_asr_timeout)

            # --- Whisper Model Menu ---
            whisper_model_menu = trans_service_menu.addMenu("Faster-Whisper Model")
            self.whisper_model_group = QActionGroup(self)
            for model_name in ["tiny", "base", "small", "medium"]:
                action = QAction(model_name.capitalize(), self, checkable=True)
                action.setData(model_name)
                action.triggered.connect(lambda checked, m=model_name: self.set_whisper_model(m))
                self.whisper_model_group.addAction(action)
                whisper_model_menu.addAction(action)

            # --- AI Service Menu ---
            ai_service_menu = settings_menu.addMenu("AI Service")
            self.ai_service_group = QActionGroup(self)
            gemini_action = QAction("Gemini", self, checkable=True)
            gemini_action.triggered.connect(lambda: self.set_ai_service("Gemini"))
            local_action = QAction("Local AI", self, checkable=True)
            local_action.triggered.connect(lambda: self.set_ai_service("Local"))
            self.ai_service_group.addAction(gemini_action)
            self.ai_service_group.addAction(local_action)
            ai_service_menu.addAction(gemini_action)
            ai_service_menu.addAction(local_action)
        
        theme_menu = settings_menu.addMenu("Theme")
        dark_action = QAction("Dark", self, checkable=True)
        dark_action.triggered.connect(lambda: self.set_theme("dark"))
        light_action = QAction("Light", self, checkable=True)
        light_action.triggered.connect(lambda: self.set_theme("light"))
        theme_menu.addAction(dark_action)
        theme_menu.addAction(light_action)
        self.theme_group = QActionGroup(self)
        self.theme_group.addAction(dark_action)
        self.theme_group.addAction(light_action)


        font_menu = settings_menu.addMenu("Font Size")
        s_font = QAction("Small (10pt)", self, checkable=True)
        s_font.triggered.connect(lambda: self.set_font_size(10))
        m_font = QAction("Medium (11pt)", self, checkable=True)
        m_font.triggered.connect(lambda: self.set_font_size(11))
        l_font = QAction("Large (13pt)", self, checkable=True)
        l_font.triggered.connect(lambda: self.set_font_size(13))
        self.font_group = QActionGroup(self)
        self.font_group.addAction(s_font)
        self.font_group.addAction(m_font)
        self.font_group.addAction(l_font)
        font_menu.addAction(s_font)
        font_menu.addAction(m_font)
        font_menu.addAction(l_font)

        # --- Listen Mode Menu ---
        listen_mode_menu = settings_menu.addMenu("Listen Mode")
        self.listen_mode_group = QActionGroup(self)
        
        hold_action = QAction("Click and Hold", self, checkable=True)
        hold_action.triggered.connect(lambda: self.set_listen_mode("Click and Hold"))
        
        stick_action = QAction("Click and Stick", self, checkable=True)
        stick_action.triggered.connect(lambda: self.set_listen_mode("Click and Stick"))
        
        self.listen_mode_group.addAction(hold_action)
        self.listen_mode_group.addAction(stick_action)
        listen_mode_menu.addAction(hold_action)
        listen_mode_menu.addAction(stick_action)

        # --- Microphone Menu ---
        mic_menu = settings_menu.addMenu("Microphone")
        self.mic_group = QActionGroup(self)
        
        # Meta-devices/virtual devices to EXCLUDE (they cause crashes on Linux)
        excluded_keywords = ['default', 'sysdefault', 'pipewire', 'dmix', 'pulse', 'jack', 'hdmi', 'rockchip']
        
        # List devices using pyaudio global device indices to catch ALL devices including USB
        first_valid_index = None
        try:
            p = get_portaudio_host()
            for i in range(p.get_device_count()):
                device_info = p.get_device_info_by_index(i)
                if device_info.get('maxInputChannels') > 0:
                    name = device_info.get('name')
                    
                    # Skip problematic meta-devices and internal Rockchip HDMI inputs
                    if any(kw in name.lower() for kw in excluded_keywords):
                        continue
                    
                    idx = i
                    action = QAction(f"{name}", self, checkable=True)
                    action.setData(idx)
                    action.triggered.connect(lambda checked, index=idx: self.set_microphone(index))
                    self.mic_group.addAction(action)
                    mic_menu.addAction(action)
                    
                    # Track the first valid device for auto-selection
                    if first_valid_index is None:
                        first_valid_index = idx
            
            # Auto-select using heuristics if nothing is set (or saved index is invalid)
            if self.settings.get("microphone_index") is None:
                best = self.get_best_microphone_index()
                if best is not None:
                    self.settings["microphone_index"] = best
                    print(f"DEBUG: Auto-selected best microphone index {best}")
                elif first_valid_index is not None:
                    self.settings["microphone_index"] = first_valid_index
                    print(f"DEBUG: Auto-selected first valid microphone index {first_valid_index}")
                self.save_settings()
                
        except Exception as e:
            print(f"Error listing microphones: {e}")


        settings_menu.addSeparator()
        self.auto_save_notes_action = QAction("Auto-save polished text", self, checkable=True)
        self.auto_save_notes_action.triggered.connect(self.toggle_auto_save_notes)
        settings_menu.addAction(self.auto_save_notes_action)
        settings_menu.addAction("Choose Translate Language...", self.choose_translate_language)
        settings_menu.addAction("Edit AI Prompt...", self.edit_prompt)
        settings_menu.addAction("Edit Translate Prompt...", self.edit_translate_prompt)
        settings_menu.addAction("Set Gemini API Key...", self.set_api_key)
        settings_menu.addAction("Set Gemini Model...", self.set_gemini_model)
        if not GEMINI_ONLY_UI:
            settings_menu.addAction("Set Local AI URL...", self.set_local_model_url)
        
        # Help Menu
        help_menu = menu_bar.addMenu("Help")
        about_action = QAction("About", self)
        about_action.triggered.connect(self.show_about_dialog)
        help_menu.addAction(about_action)
        
    def set_ai_service(self, service_name):
        self.settings["ai_service"] = service_name
        self.save_settings()
        self.apply_settings()

    def set_transcription_service(self, service_name):
        if service_name == "Qwen 3 ASR Server" and not self.get_qwen_asr_url():
            self.set_qwen_asr_url()
            if not self.get_qwen_asr_url():
                return
        self.settings["transcription_service"] = service_name
        self.save_settings()
        self.apply_settings()

    def set_whisper_model(self, model_name):
        self.settings["whisper_model"] = model_name
        self.save_settings()
        self.apply_settings()
        # Reset whisper model to force reload
        self.whisper_model = None

    def _configure_editor_toolbar_button(self, button):
        """Let editor toolbars shrink so they cannot force an unequal splitter ratio."""
        button.setMinimumWidth(0)
        button.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)

    def _balance_editor_splitter(self):
        """Force a 50/50 Raw vs Polished split after the first real layout pass."""
        splitter = getattr(self, "editor_splitter", None)
        if splitter is None:
            return
        total = sum(splitter.sizes())
        if total <= 0:
            QTimer.singleShot(50, self._balance_editor_splitter)
            return
        half = total // 2
        splitter.setSizes([half, total - half])
        self._editor_splitter_balanced = True

    def showEvent(self, event):
        super().showEvent(event)
        if not getattr(self, "_editor_splitter_balanced", False):
            QTimer.singleShot(0, self._balance_editor_splitter)

    def set_theme(self, theme_name):
        self.settings["theme"] = theme_name
        self.save_settings()
        self.apply_settings()

    def set_font_size(self, size):
        self.settings["font_size"] = size
        self.save_settings()
        self.apply_settings()

    def set_listen_mode(self, mode_name):
        if mode_name not in VALID_LISTEN_MODES:
            return
        self.settings["listen_mode"] = mode_name
        self.save_settings()
        self.apply_settings()

    def set_microphone(self, index):
        self.settings["microphone_index"] = index
        self.save_settings()
        self.apply_settings()
    
    def apply_settings(self):
        # Apply theme
        if self.settings.get("theme", "dark") == "dark":
            self.setStyleSheet(DARK_STYLESHEET)
            if hasattr(self, 'theme_group'): self.theme_group.actions()[0].setChecked(True)
        else:
            self.setStyleSheet(LIGHT_STYLESHEET) # Apply new LIGHT_STYLESHEET
            if hasattr(self, 'theme_group'): self.theme_group.actions()[1].setChecked(True)

        # Apply font size
        font_size = self.settings.get("font_size", 11)
        # We use a default font family here, Segoe UI, but it can be changed.
        # The key is that the QSS does not override the size.
        font = QFont("Segoe UI", font_size) 
        if hasattr(self, 'raw_text_area'): self.raw_text_area.setFont(font)
        if hasattr(self, 'polished_text_area'): self.polished_text_area.setFont(font)
        
        # Update font for all labels for consistency if desired, or handle them individually
        # For simplicity, let's also update the font of existing labels.
        # This is a general approach; more targeted styling might be needed for complex UIs.
        if hasattr(self, 'centralWidget') and self.centralWidget():
            for label in self.centralWidget().findChildren(QLabel):
                label_font = label.font()
                label_font.setPointSize(font_size -1) # Example: make labels slightly smaller
                label.setFont(label_font)
        
        if hasattr(self, 'font_group'):
            if font_size == 10: self.font_group.actions()[0].setChecked(True)
            elif font_size == 11: self.font_group.actions()[1].setChecked(True)
            elif font_size == 13: self.font_group.actions()[2].setChecked(True)
        
        # Apply Transcription Service
        trans_service = self.settings.get("transcription_service", "Google")
        if hasattr(self, 'trans_service_group'):
            for action in self.trans_service_group.actions():
                action.setChecked(action.data() == trans_service)
        
        # Apply Whisper Model
        whisper_model = self.settings.get("whisper_model", "base")
        if hasattr(self, 'whisper_model_group'):
            for action in self.whisper_model_group.actions():
                if action.data() == whisper_model:
                    action.setChecked(True)
                    break

        # Apply AI Service
        service = self.settings.get("ai_service", "Gemini")
        if hasattr(self, 'ai_service_group'):
            if service == "Gemini":
                self.ai_service_group.actions()[0].setChecked(True)
            else:
                if len(self.ai_service_group.actions()) > 1: self.ai_service_group.actions()[1].setChecked(True)
        
        # Apply Listen Mode
        listen_mode = self.settings.get("listen_mode")
        if listen_mode not in VALID_LISTEN_MODES:
            listen_mode = None
            self.settings["listen_mode"] = None
        if hasattr(self, 'listen_mode_group') and self.listen_mode_group:
            actions = self.listen_mode_group.actions()
            was_exclusive = self.listen_mode_group.isExclusive()
            self.listen_mode_group.setExclusive(False)
            for action in actions:
                action.setChecked(False)
            if listen_mode == "Click and Hold" and actions:
                actions[0].setChecked(True)
            elif listen_mode == "Click and Stick" and len(actions) > 1:
                actions[1].setChecked(True)
            self.listen_mode_group.setExclusive(was_exclusive)
        
        # Configure record_button behavior based on listen_mode
        if hasattr(self, 'record_button') and self.record_button:
            listen_busy = self.is_recording or self._stopping_recording
            # Never disconnect Listen while a take is in progress: a theme
            # or settings refresh would swallow the stop click.
            if not listen_busy:
                prev_mode = getattr(self, "_listen_wired_mode", None)
                if prev_mode != listen_mode:
                    if prev_mode == "Click and Hold":
                        try:
                            self.record_button.listenPressed.disconnect(self.start_recording)
                        except (RuntimeError, TypeError):
                            pass
                        try:
                            self.record_button.listenReleased.disconnect(self.stop_recording)
                        except (RuntimeError, TypeError):
                            pass
                    elif prev_mode == "Click and Stick":
                        try:
                            self.record_button.clicked.disconnect(self.toggle_recording_stick_mode)
                        except (RuntimeError, TypeError):
                            pass

                    self.record_button.press_hold_enabled = listen_mode == "Click and Hold"
                    unique = Qt.ConnectionType.UniqueConnection
                    try:
                        if listen_mode == "Click and Hold":
                            self.record_button.listenPressed.connect(self.start_recording, unique)
                            self.record_button.listenReleased.connect(self.stop_recording, unique)
                        elif listen_mode == "Click and Stick":
                            self.record_button.clicked.connect(self.toggle_recording_stick_mode, unique)
                    except RuntimeError:
                        pass
                    self._listen_wired_mode = listen_mode

            # Update microphone check state
            current_mic = self.settings.get("microphone_index")
            if hasattr(self, 'mic_group'):
                for action in self.mic_group.actions():
                    if action.data() == current_mic:
                        action.setChecked(True)
                        break

            # Update microphone check state
            current_mic = self.settings.get("microphone_index")
            if hasattr(self, 'mic_group'):
                for action in self.mic_group.actions():
                    if action.data() == current_mic:
                        action.setChecked(True)
                        break

        if hasattr(self, "auto_save_notes_action"):
            self.auto_save_notes_action.setChecked(bool(self.settings.get("auto_save_notes", True)))
        self.update_translate_button_label()
        if hasattr(self, "translate_button") and not self._is_button_spinning("translate"):
            self.translate_button.setEnabled(self.get_effective_ai_service() == "Gemini")
        
        # Refresh ghost cursors after settings are applied and UI elements exist
        if hasattr(self, 'raw_text_area') and self.raw_text_area: # Ensure UI is initialized
             QTimer.singleShot(0, self._refresh_all_ghost_cursors)

    def load_settings(self):
        loaded_settings = None
        try:
            with open(self.settings_file, 'r') as f:
                loaded_settings = json.load(f)
            self.settings = DEFAULT_SETTINGS.copy()
            self.settings.update(loaded_settings)
        except (FileNotFoundError, json.JSONDecodeError):
            self.settings = DEFAULT_SETTINGS.copy()

        timeout_value = self.settings.get("qwen_asr_timeout_seconds", DEFAULT_SETTINGS["qwen_asr_timeout_seconds"])
        try:
            timeout_seconds = int(timeout_value)
            if timeout_seconds <= 0:
                raise ValueError
        except (TypeError, ValueError):
            timeout_seconds = DEFAULT_SETTINGS["qwen_asr_timeout_seconds"]
        self.settings["qwen_asr_timeout_seconds"] = timeout_seconds

        if GEMINI_ONLY_UI:
            self.settings["transcription_service"] = "Gemini"
            self.settings["ai_service"] = "Gemini"
        elif self.settings.get("transcription_service") == "Gemini":
            self.settings["transcription_service"] = "Google"

        self.settings["translate_language"] = normalize_translate_language_code(
            self.settings.get("translate_language", "")
        )
        if "auto_save_notes" not in self.settings:
            self.settings["auto_save_notes"] = True
        previous_polish = self.settings.get("system_prompt")
        previous_translate = self.settings.get("translate_system_prompt")
        self.settings["system_prompt"] = resolve_stored_polish_prompt(
            previous_polish,
            DEFAULT_SETTINGS["system_prompt"],
        )
        self.settings["translate_system_prompt"] = resolve_stored_translate_polish_prompt(
            previous_translate,
        )
        self.settings["auto_save_notes"] = bool(self.settings.get("auto_save_notes", True))
        self.settings["welcome_completed"] = bool(self.settings.get("welcome_completed", False))
        if self.settings.get("listen_mode") not in VALID_LISTEN_MODES:
            self.settings["listen_mode"] = None
        if (
            self.settings.get("system_prompt") != previous_polish
            or self.settings.get("translate_system_prompt") != previous_translate
        ):
            self.save_settings()

    def ensure_welcome_setup(self):
        """
        Run first-run setup before the main window is shown.
        Returns True if the app may continue, False if the user quit setup.
        """
        if self.settings.get("welcome_completed") and self.settings.get("listen_mode") in VALID_LISTEN_MODES:
            return True
        # Incomplete prior setup must be finished before use.
        self.settings["welcome_completed"] = False
        dialog = WelcomeSetupDialog(
            None,
            existing_api_key=self.settings.get("api_key", ""),
            theme=self.settings.get("theme", "dark"),
        )
        result = dialog.exec()
        if result != QDialog.DialogCode.Accepted or dialog.quit_requested():
            return False
        self.settings["api_key"] = dialog.api_key()
        self.settings["listen_mode"] = dialog.listen_mode()
        self.settings["welcome_completed"] = True
        self.save_settings()
        self.apply_settings()
        return True

    def get_qwen_asr_url(self):
        env_url = self.env_settings.get("QWEN_ASR_SERVER_URL", "").strip()
        if env_url:
            return env_url
        return self.settings.get("qwen_asr_url", DEFAULT_SETTINGS["qwen_asr_url"]).strip()

    def get_qwen_asr_timeout(self):
        timeout_value = self.settings.get(
            "qwen_asr_timeout_seconds",
            DEFAULT_SETTINGS["qwen_asr_timeout_seconds"],
        )
        try:
            timeout_seconds = int(timeout_value)
            if timeout_seconds <= 0:
                raise ValueError
            return timeout_seconds
        except (TypeError, ValueError):
            return DEFAULT_SETTINGS["qwen_asr_timeout_seconds"]

    def save_settings(self):
        with open(self.settings_file, 'w') as f:
            json.dump(self.settings, f, indent=4)

    def get_best_microphone_index(self):
        """Heuristics to find the best available microphone (e.g., USB) if default fails."""
        try:
            p = get_portaudio_host()
            best_index = None
            best_score = -500 # Set low starting score
            
            print("DEBUG: Scanning for microphones...")
            for i in range(p.get_device_count()):
                info = p.get_device_info_by_index(i)
                if info.get('maxInputChannels') > 0:
                    name = info.get('name', '')
                    name_lower = name.lower()
                    score = 0
                    
                    # Heuristics
                    if "usb" in name_lower: score += 100
                    if "streamcam" in name_lower: score += 200
                    if "webcam" in name_lower: score += 50
                    if "logitech" in name_lower: score += 50
                    
                    # Penalties for meta-devices and known silent inputs
                    if "default" in name_lower: score -= 1000
                    if "sysdefault" in name_lower: score -= 1000
                    if "dmix" in name_lower: score -= 500
                    if "pipewire" in name_lower: score -= 500
                    if "hdmi" in name_lower: score -= 500
                    if "rockchip" in name_lower and "hdmi" in name_lower: score -= 2000
                    
                    print(f"DEBUG: Scanned Device {i}: '{name}' | Score: {score}")
                    
                    if score > best_score:
                        best_score = score
                        best_index = i
            
            if best_index is not None and best_score > -500:
                print(f"DEBUG: Result - Auto-selected index {best_index} (Score {best_score})")
                return best_index
            else:
                print("DEBUG: Result - No suitable candidates found.")
        except Exception as e:
             print(f"DEBUG: Error in smart mic selection: {e}")
        return None

    def _is_button_spinning(self, button_name):
        return button_name in self.button_spinner_states

    def _advance_button_spinner(self, button_name):
        state = self.button_spinner_states.get(button_name)
        if not state:
            return

        frame = self.spinner_frames[state["frame_index"] % len(self.spinner_frames)]
        state["button"].setText(frame)
        state["frame_index"] += 1

    def _start_button_spinner(self, button_name, button):
        if self._is_button_spinning(button_name):
            return

        timer = QTimer(self)
        timer.setInterval(120)
        timer.timeout.connect(lambda name=button_name: self._advance_button_spinner(name))
        self.button_spinner_states[button_name] = {
            "button": button,
            "frame_index": 0,
            "timer": timer,
        }

        button.setEnabled(False)
        self._advance_button_spinner(button_name)
        timer.start()

    def _stop_button_spinner(self, button_name, idle_text):
        state = self.button_spinner_states.pop(button_name, None)
        if not state:
            return

        state["timer"].stop()
        state["timer"].deleteLater()
        state["button"].setEnabled(True)
        state["button"].setText(idle_text)

    def start_record_processing(self):
        self._start_button_spinner("record", self.record_button)

    def finish_record_processing(self):
        self._stop_button_spinner("record", "🔴 Listen")
        self._refresh_all_ghost_cursors()

    def start_import_processing(self):
        self.is_import_transcribing = True
        self._set_import_controls_enabled(False)
        self.transcribe_import_button.setText("…")

    def finish_import_processing(self):
        self.is_import_transcribing = False
        pending = getattr(self, "_pending_import_delete", None)
        self._pending_import_delete = None
        if pending and os.path.exists(pending) and pending != self.imported_audio_path:
            try:
                os.remove(pending)
            except OSError:
                pass
            if self.imported_audio_path is None:
                self._clear_parked_audio_files()
        self._set_import_controls_enabled(True)
        if hasattr(self, "transcribe_import_button"):
            self.transcribe_import_button.setText("▶ Transcribe")
        if self._is_button_spinning("record"):
            self.finish_record_processing()
        self._refresh_all_ghost_cursors()

    def closeEvent(self, event):
        self._stop_mic_heartbeat()
        if self._mic_session is not None:
            self._mic_session.stop(wait=False)
            self._mic_session = None
        if self._mic_lingering_session is not None:
            self._mic_lingering_session.stop(wait=False)
            self._mic_lingering_session = None
        self.save_settings()
        if self._notes_persist_timer.isActive():
            self._flush_saved_notes()
        super().closeEvent(event)

    def _set_import_controls_enabled(self, enabled):
        if hasattr(self, "transcribe_import_button"):
            self.transcribe_import_button.setEnabled(enabled)
        if hasattr(self, "clear_import_button"):
            self.clear_import_button.setEnabled(enabled)

    def _is_audio_busy(self):
        return (
            self.is_recording
            or self._stopping_recording
            or self.is_import_transcribing
            or self._is_button_spinning("record")
        )

    def _is_text_ai_busy(self):
        return self._is_button_spinning("polish") or self._is_button_spinning("translate")

    def start_polish_processing(self):
        if hasattr(self, "translate_button"):
            self.translate_button.setEnabled(False)
        self._start_button_spinner("polish", self.polish_button)

    def finish_polish_processing(self):
        self._stop_button_spinner("polish", "✨ Polish")
        self.update_translate_button_label()
        if hasattr(self, "translate_button") and not self._is_button_spinning("translate"):
            self.translate_button.setEnabled(self.get_effective_ai_service() == "Gemini")

    def start_translate_processing(self):
        if hasattr(self, "polish_button"):
            self.polish_button.setEnabled(False)
        self._start_button_spinner("translate", self.translate_button)

    def finish_translate_processing(self):
        self._stop_button_spinner("translate", self._translate_button_idle_text())
        if not self._is_button_spinning("polish"):
            self.polish_button.setEnabled(True)

    def update_translate_button_label(self):
        if hasattr(self, "translate_button") and not self._is_button_spinning("translate"):
            self.translate_button.setText(self._translate_button_idle_text())

    def _translate_button_idle_text(self):
        code = self.settings.get("translate_language", "")
        if is_configured_translate_language(code):
            return f"🌐 {translate_button_code(code)}"
        return "🌐 Translate"

    def start_recording(self):
        if self.settings.get("listen_mode") not in VALID_LISTEN_MODES:
            self.show_error_message(
                "Choose a Listen mode in Settings (Click and Hold or Click and Stick) before recording."
            )
            return
        if (
            self.is_recording
            or self._stopping_recording
            or self._is_button_spinning("record")
            or self.is_import_transcribing
        ):
            if self.is_import_transcribing:
                self.show_status_message("Wait for the imported audio transcription to finish.")
            return
        lingering = self._mic_lingering_session
        if lingering is not None:
            lingering.stop(wait=False)
            self._mic_lingering_session = None
        self.is_recording = True
        self._mic_start_generation += 1
        generation = self._mic_start_generation
        self._listen_started_at = time.monotonic()
        self._heard_listen_audio = False
        self._mic_reopen_count = 0
        self._mic_empty_reopen_count = 0
        self._last_mic_reopen_at = 0.0
        self.record_button.setText("Starting...")
        self.statusBar().showMessage("Waiting for microphone…")
        
        self.audio_frames = [] # Clear previous frames
        self.current_sample_rate = None
        self.current_sample_width = None

        device_index = self.settings.get("microphone_index")
        session = self._new_capture_session(device_index)
        self._mic_session = session
        print(f"DEBUG: Starting background listener for audio accumulation. Device Index Setting: {device_index}")
        self._mic_heartbeat_timer.start()
        threading.Thread(
            target=self._start_microphone_worker,
            args=(session, device_index, generation),
            daemon=True,
        ).start()

    def _new_capture_session(self, device_index):
        return MicrophoneCaptureSession(
            device_index,
            on_first_audio=self.comm.microphone_got_audio.emit,
        )

    def _start_microphone_worker(self, session, device_index, generation):
        """Open the mic off the UI thread and keep one capture session for the whole Listen take."""
        try:
            if session.stop_event.is_set() or generation != self._mic_start_generation or not self.is_recording:
                return
            if device_index is None:
                device_index = self.get_best_microphone_index()
                session.device_index = device_index
            if device_index is None:
                if (
                    generation == self._mic_start_generation
                    and self.is_recording
                    and not session.stop_event.is_set()
                ):
                    self.comm.microphone_failed.emit(
                        "No microphone selected. Please select one in Settings > Microphone."
                    )
                return
            if session.stop_event.is_set() or generation != self._mic_start_generation or not self.is_recording:
                return

            session.start()
            opened = session.wait_opened(timeout=8.0)
            if session.stop_event.is_set() or generation != self._mic_start_generation or not self.is_recording:
                session.stop(wait=False)
                return
            if not opened or session.error is not None:
                detail = session.error or "device unavailable"
                keep_audio = session.captured_bytes > 0
                session.stop(wait=False)
                if keep_audio:
                    print(
                        "DEBUG: Replacement microphone stream failed; keeping audio already captured."
                    )
                    return
                if generation == self._mic_start_generation and self.is_recording:
                    self.comm.microphone_failed.emit(
                        "Microphone is busy. Close other apps using the mic, then tap Listen again."
                        f"\n\n({detail})"
                    )
                return

            self.comm.microphone_ready.emit(device_index)
            # Stay on this worker until the owner thread ends. Never join it
            # from Qt. No short timeout: a long take is still one session.
            session.wait_until_finished()
            if (
                session.lost_error
                and generation == self._mic_start_generation
                and self.is_recording
                and not session.stop_event.is_set()
            ):
                self.comm.microphone_capture_lost.emit(session.lost_error)
        except Exception as e:
            print(f"DEBUG: Exception starting microphone: {e}")
            keep_audio = session.captured_bytes > 0
            session.stop(wait=False)
            if keep_audio:
                return
            if generation == self._mic_start_generation and self.is_recording:
                self.comm.microphone_failed.emit(
                    "Microphone is busy. Close other apps using the mic, then tap Listen again."
                    f"\n\n({e})"
                )

    def _on_microphone_ready(self, device_index):
        if not self.is_recording or self._stopping_recording:
            session = self._mic_session
            if not self.is_recording:
                self._mic_session = None
                if session is not None:
                    session.stop(wait=False)
            return
        if self.settings.get("microphone_index") is None and device_index is not None:
            self.settings["microphone_index"] = device_index
            self.save_settings()
            print(f"DEBUG: Fallback auto-selected microphone index {device_index}")
        session = self._mic_session
        already_has_audio = self._heard_listen_audio or (
            session is not None and session.captured_bytes > 0
        )
        if already_has_audio:
            self._heard_listen_audio = True
            self._show_listening_button()
        elif not self._is_button_spinning("record"):
            self.record_button.setText("Starting...")

    def _on_microphone_got_audio(self):
        if not self.is_recording or self._stopping_recording:
            return
        self._heard_listen_audio = True
        self._show_listening_button()

    def _show_listening_button(self):
        if not self._is_button_spinning("record"):
            self.record_button.setText("Listening...")

    def _listen_wall_s(self):
        if self._listen_started_at is None:
            return 0.0
        return time.monotonic() - self._listen_started_at

    def _stop_mic_heartbeat(self):
        if self._mic_heartbeat_timer.isActive():
            self._mic_heartbeat_timer.stop()

    def _on_mic_heartbeat(self):
        if not self.is_recording or self._stopping_recording:
            self._stop_mic_heartbeat()
            return
        session = self._mic_session
        if session is None:
            return
        duration_s = session.captured_duration_s()
        if duration_s > 0:
            self._heard_listen_audio = True
            self._show_listening_button()
            self.statusBar().showMessage(f"Listening… {duration_s:.1f}s")
        else:
            self.statusBar().showMessage("Waiting for microphone…")
        self._maybe_reopen_stalled_capture(session)

    def _maybe_reopen_stalled_capture(self, session):
        now = time.monotonic()
        if now - self._last_mic_reopen_at < MIC_REOPEN_COOLDOWN_SECONDS:
            return
        if self._mic_reopen_count >= MIC_MAX_REOPENS:
            return
        if session.is_running():
            # A live reader may still hold the device. Opening another stream
            # on top of it is what produced empty long takes on this host.
            return
        stats = session.capture_stats()
        captured_bytes = stats["captured_bytes"]
        if captured_bytes == 0 and self._mic_empty_reopen_count >= MIC_MAX_EMPTY_REOPENS:
            return
        opened_at = stats["opened_at"]
        last_append = stats["last_append_at"]
        stream_dead = session.error is not None or session.stop_event.is_set()
        if not stream_dead:
            if opened_at is None:
                return
            if captured_bytes == 0:
                stalled = (now - opened_at) >= MIC_FIRST_AUDIO_STALL_SECONDS
            elif last_append is None:
                # Adopted PCM from a prior stream; this replacement has not
                # appended yet. Do not wait on a stamp from adopt time.
                stalled = (now - opened_at) >= MIC_STALL_SECONDS
            else:
                stalled = (now - last_append) >= MIC_STALL_SECONDS
            if not stalled:
                return
        print("DEBUG: Capture stalled; reopening microphone stream.")
        self.statusBar().showMessage("Microphone stalled, reconnecting…")
        self._reopen_stalled_capture()

    def _reopen_stalled_capture(self):
        if not self.is_recording or self._stopping_recording:
            return
        old = self._mic_session
        if old is None:
            return
        raw_audio, sample_rate, sample_width = old.snapshot()
        old.stop(wait=False)
        if self._mic_lingering_session is not None:
            self._mic_lingering_session.stop(wait=False)
        self._mic_lingering_session = old
        device_index = old.device_index
        session = self._new_capture_session(device_index)
        session.adopt_pcm(raw_audio, sample_rate, sample_width)
        self._mic_session = session
        self._mic_reopen_count += 1
        if not raw_audio:
            self._mic_empty_reopen_count += 1
        self._last_mic_reopen_at = time.monotonic()
        generation = self._mic_start_generation
        threading.Thread(
            target=self._start_microphone_worker,
            args=(session, device_index, generation),
            daemon=True,
        ).start()

    def _on_microphone_failed(self, message):
        if not self.is_recording or self._stopping_recording:
            return
        session = self._mic_session
        if session is not None and session.captured_bytes > 0:
            print("DEBUG: Ignoring microphone error; audio was already captured.")
            self.statusBar().showMessage(
                "Microphone reconnect failed. Stop Listen to transcribe what was captured."
            )
            return
        self.is_recording = False
        self._stop_mic_heartbeat()
        self.record_button.setText("🔴 Listen")
        self._mic_session = None
        if session is not None:
            session.stop(wait=False)
        self.show_error_message(message)

    def _on_microphone_capture_lost(self, message):
        if not self.is_recording or self._stopping_recording:
            return
        print(f"DEBUG: Microphone capture lost: {message}")
        session = self._mic_session
        has_audio = session is not None and session.captured_bytes > 0
        can_retry_empty = self._mic_empty_reopen_count < MIC_MAX_EMPTY_REOPENS
        if (has_audio or can_retry_empty) and self._mic_reopen_count < MIC_MAX_REOPENS:
            self.statusBar().showMessage("Microphone stalled, reconnecting…")
            self._reopen_stalled_capture()
            return
        self.show_status_message("Microphone stopped unexpectedly. Transcribing what was captured.")
        self.stop_recording()

    def _pcm_duration_s(self, raw_audio, sample_rate, sample_width):
        if not raw_audio or not sample_rate or not sample_width:
            return 0.0
        return len(raw_audio) / float(sample_rate * sample_width)

    def _merge_listen_pcm(self, primary, lingering):
        """Keep the longer consistent PCM when a stalled reader was abandoned."""
        raw_audio, sample_rate, sample_width = primary
        if lingering is None:
            return primary
        old_raw, old_rate, old_width = lingering.snapshot()
        if not old_raw:
            return primary
        if not raw_audio:
            return (old_raw, old_rate or sample_rate, old_width or sample_width)
        if old_rate and sample_rate and old_rate != sample_rate:
            if len(old_raw) > len(raw_audio):
                return (old_raw, old_rate, old_width)
            return primary
        if raw_audio.startswith(old_raw):
            return primary
        if old_raw.startswith(raw_audio):
            return (old_raw, old_rate or sample_rate, old_width or sample_width)
        if len(old_raw) > len(raw_audio):
            return (old_raw, old_rate or sample_rate, old_width or sample_width)
        return primary

    def stop_recording(self):
        if not self.is_recording or self._stopping_recording:
            return
        self._stopping_recording = True
        self.is_recording = False
        self._mic_start_generation += 1
        self._stop_mic_heartbeat()
        wall_s = self._listen_wall_s()
        try:
            session = self._mic_session
            self._mic_session = None
            if session is None:
                self.record_button.setText("🔴 Listen")
                if wall_s < MIN_LISTEN_SECONDS:
                    self.show_status_message("Recording too short. Ignored.")
                else:
                    self.show_status_message(
                        "Microphone delivered no audio. Check the selected mic and try Listen again."
                    )
                return

            print("DEBUG: Stopping microphone capture.")
            # Copy PCM first, then ask the audio thread to stop. Never join
            # PortAudio from Qt: stream.read/stop_stream/terminate can hang
            # on this host, which is the desktop "not responding" freeze.
            raw_audio, sample_rate, sample_width = session.snapshot()
            lingering = self._mic_lingering_session
            try:
                session.stop(wait=False)
            except Exception as e:
                print(f"DEBUG: Error signalling microphone stop: {e}")
            raw_audio, sample_rate, sample_width = self._merge_listen_pcm(
                (raw_audio, sample_rate, sample_width), lingering
            )
            raw_audio, sample_rate, sample_width = self._merge_listen_pcm(
                (raw_audio, sample_rate, sample_width), session
            )
            self._mic_lingering_session = session

            self.audio_frames = []
            self.current_sample_rate = sample_rate
            self.current_sample_width = sample_width
            duration_s = self._pcm_duration_s(raw_audio, sample_rate, sample_width)
            print(
                f"DEBUG: Stop listen wall={wall_s:.1f}s pcm={duration_s:.1f}s "
                f"bytes={len(raw_audio) if raw_audio else 0}."
            )

            if duration_s <= 0:
                self.record_button.setText("🔴 Listen")
                if wall_s < MIN_LISTEN_SECONDS:
                    self.show_status_message("Recording too short. Ignored.")
                else:
                    self.show_status_message(
                        "Microphone delivered no audio. Check the selected mic and try Listen again."
                    )
                return

            if duration_s < MIN_LISTEN_SECONDS and wall_s < MIN_LISTEN_SECONDS:
                print("DEBUG: Recording shorter than 1s; treating as a failed attempt.")
                self.record_button.setText("🔴 Listen")
                self.show_status_message("Recording too short. Ignored.")
                return

            try:
                wav_bytes = sr.AudioData(raw_audio, sample_rate, sample_width).get_wav_data()
                display_name = f"Last recording ({duration_s:.1f}s)"
                self._park_audio_bytes(wav_bytes, "last_recording.wav", display_name)
            except Exception as park_error:
                print(f"DEBUG: Failed to park last recording: {park_error}")
                self.record_button.setText("🔴 Listen")
                self.show_error_message(f"Could not save the last recording: {park_error}")
                return

            transcription_service = self.get_effective_transcription_service()
            if transcription_service == "Gemini" and not self.settings.get("api_key"):
                self.set_api_key()
                if not self.settings.get("api_key"):
                    self.record_button.setText("🔴 Listen")
                    return
            self.show_status_message(f"Transcribing {duration_s:.1f}s of audio...")
            self.transcribe_imported_audio(from_listen=True)
        finally:
            self._stopping_recording = False

    def get_effective_transcription_service(self):
        if GEMINI_ONLY_UI:
            return "Gemini"
        return self.settings.get("transcription_service", DEFAULT_SETTINGS["transcription_service"])

    def get_effective_ai_service(self):
        if GEMINI_ONLY_UI:
            return "Gemini"
        return self.settings.get("ai_service", "Gemini")

    def get_transcription_fallback_order(self, primary_service):
        if GEMINI_ONLY_UI:
            return ["Gemini"]
        selectable_services = {"Google", "Local", "Qwen 3 ASR Server"}
        if primary_service == "Gemini":
            primary_service = "Google"
        normalized_primary = primary_service if primary_service in selectable_services else "Google"

        fallback_order = [normalized_primary]
        for service_name in ["Google", "Gemini", "Local", "Qwen 3 ASR Server"]:
            if service_name not in fallback_order:
                fallback_order.append(service_name)
        return fallback_order

    def _transcribe_with_google(self, audio_data_to_recognize):
        try:
            text = self.recognizer.recognize_google(audio_data_to_recognize).strip()
        except sr.UnknownValueError as e:
            raise RuntimeError("Google could not understand the audio.") from e
        except sr.RequestError as e:
            raise RuntimeError(f"Google request failed: {e}") from e
        if not text:
            raise RuntimeError("Google returned an empty transcription.")
        return text

    def _preload_heavy_deps_async(self):
        """Load slow optional deps in the background after the UI is shown."""
        def preload():
            service = self.get_effective_transcription_service()
            ai_service = self.get_effective_ai_service()
            if service == "Gemini" or ai_service == "Gemini":
                get_genai()
            if not GEMINI_ONLY_UI and service == "Local":
                get_numpy()
                get_whisper_model_cls()
            if not GEMINI_ONLY_UI and (service == "Qwen 3 ASR Server" or ai_service == "Local"):
                import requests  # noqa: F401
        threading.Thread(target=preload, daemon=True).start()

    def _transcribe_locally(self, audio_data_to_recognize):
        np = get_numpy()
        if not self.whisper_model:
            model_name = self.settings.get("whisper_model", "base")
            print(f"DEBUG: Loading Whisper model: {model_name}")
            WhisperModel = get_whisper_model_cls()
            self.whisper_model = WhisperModel(model_name, device="cpu", compute_type="int8")

        raw_data = audio_data_to_recognize.get_raw_data()
        audio_np = np.frombuffer(raw_data, dtype=np.int16).astype(np.float32) / 32768.0

        original_sr = audio_data_to_recognize.sample_rate
        target_sr = 16000
        if original_sr != target_sr:
            print(f"DEBUG: Resampling from {original_sr}Hz to {target_sr}Hz")
            duration = len(audio_np) / original_sr
            num_target_samples = int(duration * target_sr)
            audio_np = np.interp(
                np.linspace(0, duration, num_target_samples, endpoint=False),
                np.linspace(0, duration, len(audio_np), endpoint=False),
                audio_np,
            ).astype(np.float32)

        segments, info = self.whisper_model.transcribe(audio_np, beam_size=5)

        text = "".join(segment.text for segment in segments).strip()
        if not text:
            raise RuntimeError("Local transcription returned empty text.")
        return text

    def _transcribe_with_qwen_server(self, audio_data_to_recognize):
        qwen_asr_url = self.get_qwen_asr_url()
        if not qwen_asr_url:
            raise RuntimeError("Qwen 3 ASR server URL is not configured.")

        import requests

        wav_data = audio_data_to_recognize.get_wav_data(convert_rate=16000, convert_width=2)
        response = requests.post(
            qwen_asr_url,
            files={"audio": ("recording.wav", wav_data, "audio/wav")},
            timeout=self.get_qwen_asr_timeout(),
        )
        response.raise_for_status()

        payload = response.json()
        text = payload.get("transcription", {}).get("parsed_text", "").strip()
        if not text:
            raise RuntimeError("Qwen 3 ASR server returned an empty transcription.")
        return text

    def _gemini_file_state_name(self, audio_file):
        state = getattr(audio_file, "state", None)
        if state is None:
            return ""
        if isinstance(state, int):
            return {0: "UNSPECIFIED", 1: "PROCESSING", 2: "ACTIVE", 10: "FAILED"}.get(
                state, str(state)
            )
        name = getattr(state, "name", None) or str(state)
        text = str(name).upper().rsplit(".", 1)[-1].replace("STATE_", "")
        if text in ("FAILED", "PROCESSING", "ACTIVE", "UNSPECIFIED"):
            return text
        if "FAILED" in text:
            return "FAILED"
        if "PROCESSING" in text:
            return "PROCESSING"
        if text.endswith("ACTIVE"):
            return "ACTIVE"
        return text

    def _gemini_response_text(self, response):
        finish = ""
        try:
            candidate = (getattr(response, "candidates", None) or [None])[0]
            reason = getattr(candidate, "finish_reason", None)
            finish = str(getattr(reason, "name", reason) or "").upper()
        except Exception:
            finish = ""
        if "MAX_TOKEN" in finish:
            raise RuntimeError(
                "Gemini stopped before finishing the transcript. Tap Transcribe to retry."
            )
        try:
            text = getattr(response, "text", "").strip()
        except Exception as response_error:
            raise RuntimeError(safe_error_text(response_error)) from response_error
        if not text:
            raise RuntimeError("Gemini returned an empty transcription.")
        return text

    def _wait_for_gemini_file_active(self, genai, audio_file, timeout_s=180.0):
        current = audio_file
        deadline = time.time() + timeout_s
        notified = False
        while True:
            state = self._gemini_file_state_name(current)
            print(f"DEBUG: Gemini uploaded file state={state or 'unknown'}")
            if state == "FAILED":
                raise RuntimeError("Gemini failed to process the uploaded audio.")
            if state == "ACTIVE":
                return current
            if state == "PROCESSING" and not notified:
                self.comm.status.emit("Waiting for Gemini to finish processing the audio...")
                notified = True
            if time.time() >= deadline:
                raise RuntimeError(
                    "Gemini is still processing the uploaded audio. Tap Transcribe to retry."
                )
            time.sleep(0.5)
            name = getattr(current, "name", None)
            if not name:
                continue
            getter = getattr(genai, "get_file", None)
            if getter is None:
                continue
            try:
                current = getter(name)
            except Exception as refresh_error:
                print(f"DEBUG: Gemini get_file failed: {refresh_error}")
                if time.time() >= deadline:
                    raise RuntimeError(
                        "Gemini is still processing the uploaded audio. Tap Transcribe to retry."
                    ) from refresh_error

    def _transcribe_with_gemini(self, audio_data_to_recognize):
        api_key = self.settings.get("api_key", "").strip()
        if not api_key:
            raise RuntimeError("Gemini API key is not configured.")

        genai = get_genai()
        genai.configure(api_key=api_key)
        gemini_model_name = self.settings["gemini_model"]
        model = genai.GenerativeModel(gemini_model_name)

        wav_data = audio_data_to_recognize.get_wav_data(convert_rate=16000, convert_width=2)
        audio_file = None
        try:
            audio_buffer = io.BytesIO(wav_data)
            audio_buffer.name = "recording.wav"
            audio_file = genai.upload_file(audio_buffer, mime_type="audio/wav", display_name="recording.wav")
            audio_file = self._wait_for_gemini_file_active(genai, audio_file)
            response = model.generate_content(
                [GEMINI_TRANSCRIBE_PROMPT, audio_file],
                generation_config={"max_output_tokens": 8192, "temperature": 0},
            )
            return self._gemini_response_text(response)
        finally:
            if audio_file is not None:
                try:
                    resource_name = getattr(audio_file, "name", audio_file)
                    genai.delete_file(resource_name)
                except Exception as delete_error:
                    print(f"DEBUG: Failed to delete Gemini uploaded audio file: {delete_error}")

    def _transcribe_with_service(self, audio_data_to_recognize, service_name):
        if service_name == "Gemini":
            return self._transcribe_with_gemini(audio_data_to_recognize)
        if service_name == "Local":
            return self._transcribe_locally(audio_data_to_recognize)
        if service_name == "Qwen 3 ASR Server":
            return self._transcribe_with_qwen_server(audio_data_to_recognize)
        return self._transcribe_with_google(audio_data_to_recognize)

    def _process_audio_single_service(self, audio_data_to_recognize, service_name):
        safe_debug(f"DEBUG: Starting {service_name} transcription of entire audio.")
        try:
            text = self._transcribe_with_service(audio_data_to_recognize, service_name)
            self.comm.text_ready.emit(text + " ")
            safe_debug(f"DEBUG: {service_name} transcription successful: '{text}'")
        except Exception as e:
            safe_debug(f"DEBUG: {service_name} transcription error: {safe_error_text(e)}")
            self.comm.error.emit(f"{service_name} transcription error: {safe_error_text(e)}")
        finally:
            self.comm.transcription_finished.emit()

    def process_audio_with_fallbacks(self, audio_data_to_recognize, primary_service):
        attempt_order = self.get_transcription_fallback_order(primary_service)
        safe_debug(f"DEBUG: Transcription fallback order: {attempt_order}")
        failures = []

        try:
            for attempt_number, service_name in enumerate(attempt_order, start=1):
                try:
                    safe_debug(f"DEBUG: Transcription attempt {attempt_number} using {service_name}")
                    text = self._transcribe_with_service(audio_data_to_recognize, service_name)
                    self.comm.text_ready.emit(text + " ")
                    safe_debug(f"DEBUG: {service_name} transcription successful: '{text}'")
                    return
                except Exception as e:
                    safe_debug(f"DEBUG: {service_name} transcription attempt failed: {safe_error_text(e)}")
                    failures.append(f"{service_name}: {safe_error_text(e)}")

            self.comm.error.emit("All transcription attempts failed:\n" + "\n".join(failures))
        finally:
            self.comm.transcription_finished.emit()

    def process_entire_audio(self, audio_data_to_recognize):
        """Processes the entire accumulated audio data for speech recognition."""
        self._process_audio_single_service(audio_data_to_recognize, "Google")

    def process_audio_locally(self, audio_data_to_recognize):
        """Processes the entire accumulated audio data using faster-whisper locally."""
        self._process_audio_single_service(audio_data_to_recognize, "Local")

    def process_audio_qwen_server(self, audio_data_to_recognize):
        """Processes the entire accumulated audio data via the LAN Qwen 3 ASR server."""
        self._process_audio_single_service(audio_data_to_recognize, "Qwen 3 ASR Server")

    def _selected_raw_text(self):
        cursor = self.raw_text_area.textCursor()
        if not cursor.hasSelection():
            return ""
        start = cursor.selectionStart()
        end = cursor.selectionEnd()
        return self.raw_text_area.toPlainText()[start:end]

    def insert_transcribed_text(self, text):
        self._insert_into_raw(text)

    def insert_imported_transcript(self, text):
        transcript = (text or "").strip()
        if not transcript:
            return
        current = self.raw_text_area.toPlainText()
        if current.strip():
            insertion = "\n\n" + transcript
        else:
            insertion = transcript
        # Place at end for import readability, matching Android non-cursor import feel for successive files
        self.cursor_positions["raw_text_area"] = self.raw_text_area.document().characterCount()
        self._insert_into_raw(insertion)

    def _insert_into_raw(self, text):
        doc = self.raw_text_area.document()
        target_pos = self.cursor_positions.get("raw_text_area", 0)

        if target_pos < 0:
            target_pos = 0
        if target_pos > doc.characterCount():
            target_pos = doc.characterCount()

        text_cursor = self.raw_text_area.textCursor()
        text_cursor.setPosition(target_pos)
        self.raw_text_area.setTextCursor(text_cursor)
        self.raw_text_area.insertPlainText(text)
        QTimer.singleShot(0, self._refresh_all_ghost_cursors)

    def polish_text(self):
        if self._is_text_ai_busy():
            return

        if self.get_effective_ai_service() == "Gemini" and not self.settings.get("api_key"):
            self.set_api_key()
            if not self.settings.get("api_key"):
                return

        text_to_polish = self._selected_raw_text()
        if not text_to_polish:
            text_to_polish = self.raw_text_area.toPlainText().strip()

        if not text_to_polish:
            self.show_status_message("Nothing to polish.")
            return

        self.start_polish_processing()
        threading.Thread(target=self.get_polished_text, args=(text_to_polish,), daemon=True).start()

    def get_polished_text(self, text):
        try:
            service = self.get_effective_ai_service()
            polished_text = ""

            if service == "Gemini":
                genai = get_genai()
                genai.configure(api_key=self.settings['api_key'])
                gemini_model_name = self.settings["gemini_model"]
                system_prompt = self.settings['system_prompt']
                try:
                    model = genai.GenerativeModel(
                        gemini_model_name,
                        system_instruction=system_prompt,
                    )
                    response = model.generate_content(text)
                except TypeError:
                    model = genai.GenerativeModel(gemini_model_name)
                    response = model.generate_content(f"{system_prompt}\n\n{text}")
                polished_text = getattr(response, "text", "") or ""
            else: # Local AI
                import requests
                headers = {"Content-Type": "application/json"}
                data = {
                    "model": "local-model",
                    "messages": [
                        {"role": "system", "content": self.settings['system_prompt']},
                        {"role": "user", "content": text}
                    ],
                    "temperature": 0.7
                }
                response = requests.post(self.settings.get("local_model_url"), headers=headers, data=json.dumps(data))
                response.raise_for_status()
                polished_text = response.json()['choices'][0]['message']['content']

            polished_text = (polished_text or "").strip()
            if not polished_text:
                raise RuntimeError("AI returned an empty polished result.")
            self.comm.polish_ready.emit(polished_text)
            self.comm.status.emit("Polished text added.")

        except Exception as e:
            self.comm.error.emit(f"Failed to polish text: {safe_error_text(e)}")
        finally:
            self.comm.polish_finished.emit()

    def display_polished_text(self, text):
        doc = self.polished_text_area.document()
        target_pos = self.cursor_positions.get("polished_text_area", 0)

        if target_pos < 0:
            target_pos = 0
        if target_pos > doc.characterCount():
            target_pos = doc.characterCount()

        self._suppress_polished_autosave = True
        try:
            text_cursor = self.polished_text_area.textCursor()
            text_cursor.setPosition(target_pos)
            self.polished_text_area.setTextCursor(text_cursor)
            self.polished_text_area.insertPlainText(text)
            copy_text_to_clipboard(self.polished_text_area.toPlainText())
            if self.opened_note_id and hasattr(self, "note_detail_edit"):
                self.note_detail_edit.blockSignals(True)
                self.note_detail_edit.setPlainText(self.polished_text_area.toPlainText())
                self.note_detail_edit.blockSignals(False)
        finally:
            self._suppress_polished_autosave = False

        if self.settings.get("auto_save_notes", True):
            self.save_note_from_text(
                self.polished_text_area.toPlainText(),
                ORIGIN_POLISHED_TEXT,
                create_if_missing=True,
                show_message=False,
                immediate=False,
            )

        QTimer.singleShot(0, self._refresh_all_ghost_cursors)

    def show_error_message(self, message):
        msg_box = QMessageBox(self)
        msg_box.setIcon(QMessageBox.Warning)
        msg_box.setText(message)
        msg_box.setWindowTitle("Error")
        msg_box.exec()

    def show_status_message(self, message):
        self.statusBar().showMessage(message, 5000)

    def edit_prompt(self):
        dialog = EditPromptDialog(self, self.settings['system_prompt'])
        if dialog.exec(): # exec_() for older PySide/PyQt, exec() for PySide6
            new_prompt = dialog.get_prompt_text()
            if new_prompt: # Check if text is not empty, though QDialogButtonBox usually handles this
                self.settings['system_prompt'] = new_prompt
                self.save_settings()

    def edit_translate_prompt(self):
        current = self.settings.get("translate_system_prompt", DEFAULT_TRANSLATE_POLISH_PROMPT)
        dialog = EditPromptDialog(self, current)
        if dialog.exec():
            new_prompt = dialog.get_prompt_text()
            if new_prompt:
                self.settings["translate_system_prompt"] = new_prompt
                self.save_settings()

    def toggle_auto_save_notes(self, checked):
        self.settings["auto_save_notes"] = bool(checked)
        self.save_settings()
        self.show_status_message(
            "Auto-save polished text enabled." if checked else "Auto-save polished text disabled."
        )

    def choose_translate_language(self):
        labels = ["Not set"] + language_labels_for_picker()
        current_code = self.settings.get("translate_language", "")
        current_index = 0
        if current_code:
            for index, (code, _label) in enumerate(TRANSLATE_LANGUAGES):
                if code == current_code:
                    current_index = index + 1
                    break
        choice, ok = QInputDialog.getItem(
            self,
            "Translate Language",
            "Choose a translation language:",
            labels,
            current_index,
            False,
        )
        if not ok:
            return
        if choice == "Not set":
            self.settings["translate_language"] = ""
            self.save_settings()
            self.update_translate_button_label()
            self.show_status_message("Translate language cleared.")
            return
        code = code_from_picker_label(choice)
        self.settings["translate_language"] = normalize_translate_language_code(code)
        self.save_settings()
        self.update_translate_button_label()
        self.show_status_message(f"Translate language set to {choice}.")

    def polish_and_translate_text(self):
        if self._is_text_ai_busy():
            return

        if self.get_effective_ai_service() != "Gemini":
            self.show_status_message("Translate requires Gemini. Switch AI Service to Gemini.")
            return

        language_code = self.settings.get("translate_language", "")
        if not is_configured_translate_language(language_code):
            self.choose_translate_language()
            language_code = self.settings.get("translate_language", "")
            if not is_configured_translate_language(language_code):
                return

        if not self.settings.get("api_key"):
            self.set_api_key()
            if not self.settings.get("api_key"):
                return

        text_to_process = self._selected_raw_text()
        if not text_to_process:
            text_to_process = self.raw_text_area.toPlainText().strip()
        if not text_to_process:
            self.show_status_message("Nothing to translate.")
            return

        self.start_translate_processing()
        threading.Thread(
            target=self.get_translated_text,
            args=(text_to_process, language_code),
            daemon=True,
        ).start()

    def get_translated_text(self, text, language_code):
        try:
            genai = get_genai()
            genai.configure(api_key=self.settings["api_key"])
            system_prompt = resolve_translate_prompt(
                self.settings.get("translate_system_prompt", DEFAULT_TRANSLATE_POLISH_PROMPT),
                language_code,
            )
            model = None
            try:
                model = genai.GenerativeModel(
                    self.settings["gemini_model"],
                    system_instruction=system_prompt,
                )
                response = model.generate_content(text)
            except TypeError:
                model = genai.GenerativeModel(self.settings["gemini_model"])
                response = model.generate_content(f"{system_prompt}\n\n{text}")
            translated = getattr(response, "text", "").strip()
            if not translated:
                raise RuntimeError("Gemini returned an empty translation result.")
            self.comm.polish_ready.emit(translated)
            self.comm.status.emit("Translated text added.")
        except Exception as e:
            self.comm.error.emit(f"Failed to translate text: {e}")
        finally:
            self.comm.translate_finished.emit()

    def set_api_key(self):
        text, ok = QInputDialog.getText(self, "Set API Key", "Enter Gemini API Key:")
        if ok and text:
            self.settings['api_key'] = text
            self.save_settings()
            QMessageBox.information(self, "Success", "API Key saved.")

    def set_gemini_model(self):
        current_model = self.settings["gemini_model"]
        text, ok = QInputDialog.getText(self, "Set Gemini Model", "Enter Gemini Model:", text=current_model)
        if ok and text:
            self.settings['gemini_model'] = text
            self.save_settings()
            QMessageBox.information(self, "Success", f"Gemini Model saved as {text}.")

    def set_local_model_url(self):
        new_url, ok = QInputDialog.getText(self, "Local AI URL", "Enter the URL for your local model:", text=self.settings.get("local_model_url"))
        if ok and new_url:
            self.settings["local_model_url"] = new_url
            self.save_settings()
            QMessageBox.information(self, "Success", "Local AI URL updated.")

    def set_qwen_asr_url(self):
        current_url = self.get_qwen_asr_url()
        new_url, ok = QInputDialog.getText(
            self,
            "Qwen 3 ASR Server URL",
            "Enter the server URL, for example http://192.168.x.x:8711/transcribe:",
            text=current_url,
        )
        if ok:
            self.settings["qwen_asr_url"] = new_url.strip()
            self.save_settings()
            if self.settings["qwen_asr_url"]:
                QMessageBox.information(self, "Success", "Qwen 3 ASR server URL updated.")

    def set_qwen_asr_timeout(self):
        current_timeout = self.get_qwen_asr_timeout()
        timeout_seconds, ok = QInputDialog.getInt(
            self,
            "Qwen 3 ASR Timeout",
            "Enter the request timeout in seconds:",
            value=current_timeout,
            minValue=1,
            maxValue=86400,
        )
        if ok:
            self.settings["qwen_asr_timeout_seconds"] = timeout_seconds
            self.save_settings()
            QMessageBox.information(
                self,
                "Success",
                f"Qwen 3 ASR timeout saved as {timeout_seconds} seconds.",
            )

    def clear_polished_text_area_content(self):
        self._suppress_polished_autosave = True
        try:
            self.polished_text_area.clear()
        finally:
            self._suppress_polished_autosave = False
        self.cursor_positions["polished_text_area"] = 0
        self.reset_note_editing_state()
        self._refresh_all_ghost_cursors()

    def clear_raw_text_area_content(self):
        self.raw_text_area.clear()
        self.cursor_positions["raw_text_area"] = 0
        self._refresh_all_ghost_cursors()

    def clear_all_text(self):
        self._suppress_polished_autosave = True
        try:
            self.raw_text_area.clear()
            self.polished_text_area.clear()
        finally:
            self._suppress_polished_autosave = False
        self.cursor_positions["raw_text_area"] = 0
        self.cursor_positions["polished_text_area"] = 0
        self.reset_note_editing_state()
        self._refresh_all_ghost_cursors()

    def reset_note_editing_state(self):
        self.active_note_id = None
        self.opened_note_id = None
        self.selected_note_ids = set()
        if hasattr(self, "notes_stack"):
            self.notes_stack.setCurrentIndex(0)
        if hasattr(self, "note_detail_edit"):
            self.note_detail_edit.blockSignals(True)
            self.note_detail_edit.clear()
            self.note_detail_edit.blockSignals(False)
        if hasattr(self, "note_origin_label"):
            self.note_origin_label.setText("")
        self.refresh_notes_list()

    def _on_polished_text_changed(self):
        if self._suppress_polished_autosave:
            return
        if self.active_note_id and (
            self.settings.get("auto_save_notes", True) or self.opened_note_id
        ):
            self.save_note_from_text(
                self.polished_text_area.toPlainText(),
                ORIGIN_POLISHED_TEXT,
                create_if_missing=False,
                show_message=False,
                immediate=False,
            )
    
    def save_and_new(self):
        raw_text = self.raw_text_area.toPlainText().strip()
        if not raw_text:
            return
            
        now = datetime.now().strftime('%Y-%m-%d-%H-%M')
        first_words = "_".join(raw_text.split()[:3]).replace("/", "_").replace("\\", "_")
        default_filename = os.path.join(self.savings_dir, f"{now}_{first_words or 'transcription'}.json")

        filename, _ = QFileDialog.getSaveFileName(self, "Save Session", default_filename, "JSON Files (*.json)")
        if not filename:
            return

        data_to_save = {"raw_text": raw_text, "polished_text": self.polished_text_area.toPlainText().strip()}
        
        try:
            with open(filename, 'w', encoding='utf-8') as f:
                json.dump(data_to_save, f, indent=4)
            self.clear_all_text()
        except Exception as e:
            self.show_error_message(f"Could not save file: {e}")

    def open_file(self):
        filepath, _ = QFileDialog.getOpenFileName(self, "Open Transcription", self.savings_dir, "JSON Files (*.json)")
        if not filepath:
            return

        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                data = json.load(f)

            self.reset_note_editing_state()
            self._suppress_polished_autosave = True
            try:
                self.raw_text_area.setPlainText(data.get("raw_text", ""))
                self.polished_text_area.setPlainText(data.get("polished_text", ""))
            finally:
                self._suppress_polished_autosave = False
            
            self.cursor_positions["raw_text_area"] = self.raw_text_area.textCursor().position()
            self.cursor_positions["polished_text_area"] = self.polished_text_area.textCursor().position()
            QTimer.singleShot(0, self._refresh_all_ghost_cursors)
            self.show_main_view(0)
        except Exception as e:
            self.show_error_message(f"Could not open file: {e}")

    # --- View switching ---
    def show_main_view(self, index):
        if not hasattr(self, "main_stack"):
            return
        if index == 0 and self.opened_note_id:
            # Leaving notes detail: polished editor owns the note going forward.
            self.opened_note_id = None
            if hasattr(self, "notes_stack"):
                self.notes_stack.setCurrentIndex(0)
        self.main_stack.setCurrentIndex(index)
        if hasattr(self, "editor_view_button"):
            self.editor_view_button.setChecked(index == 0)
            self.notes_view_button.setChecked(index == 1)
        if index == 1:
            self.refresh_notes_list()

    # --- Saved Notes ---
    def load_saved_notes(self):
        try:
            self.saved_notes = self.notes_store.load_notes()
            self.notes_load_failed = False
        except NotesLoadError as e:
            self.notes_load_failed = True
            self.saved_notes = []
            self.show_error_message(
                f"Failed to load saved notes. Autosave is paused until the notes file is fixed.\n\n{e}"
            )
        self.refresh_notes_list()

    def schedule_persist_saved_notes(self):
        if self.notes_load_failed:
            return
        self._notes_persist_timer.start()

    def _flush_saved_notes(self):
        if self.notes_load_failed:
            self.show_error_message(
                "Cannot save notes because the notes file failed to load. "
                "Rename or repair saved_notes.json, then restart the app."
            )
            return
        try:
            self.notes_store.save_notes(self.saved_notes)
        except Exception as e:
            self.show_error_message(f"Failed to save notes: {e}")

    def persist_saved_notes(self, immediate=True):
        if immediate:
            self._notes_persist_timer.stop()
            self._flush_saved_notes()
        else:
            self.schedule_persist_saved_notes()

    def refresh_notes_list(self):
        if not hasattr(self, "notes_list"):
            return
        self.notes_list.blockSignals(True)
        self.notes_list.clear()
        for note in self.saved_notes:
            origin = NotesStore.origin_label(note.origin)
            title = NotesStore.note_title(note.content)
            preview = NotesStore.note_preview(note.content)
            item = QListWidgetItem(f"[{origin}] {title}\n{preview}")
            item.setData(Qt.UserRole, note.id)
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable | Qt.ItemIsEnabled)
            item.setCheckState(Qt.Checked if note.id in self.selected_note_ids else Qt.Unchecked)
            self.notes_list.addItem(item)
        self.notes_list.blockSignals(False)
        empty = len(self.saved_notes) == 0
        self.notes_empty_label.setVisible(empty)
        self.notes_list.setVisible(not empty)
        self.delete_all_notes_button.setEnabled(not empty and not self.notes_load_failed)
        has_selection = bool(self.selected_note_ids)
        self.clear_note_selection_button.setEnabled(has_selection)
        self.delete_selected_notes_button.setEnabled(has_selection and not self.notes_load_failed)

    def save_note_from_text(self, content, origin, create_if_missing=True, show_message=True, immediate=True):
        if self.notes_load_failed:
            if show_message:
                self.show_status_message("Notes saving is paused until the notes file is repaired.")
            return

        trimmed = (content or "").strip()
        if not trimmed:
            if show_message:
                self.show_status_message("Nothing to save.")
            return

        active_note = None
        if self.active_note_id:
            active_note = next((n for n in self.saved_notes if n.id == self.active_note_id), None)

        if active_note is None and not create_if_missing:
            return

        if active_note is None:
            next_note = self.notes_store.new_note(trimmed, origin=origin)
        else:
            next_note = self.notes_store.update_note(active_note, trimmed, origin=origin)

        self.saved_notes = [n for n in self.saved_notes if n.id != next_note.id] + [next_note]
        self.saved_notes.sort(key=lambda n: n.createdAt, reverse=True)
        self.active_note_id = next_note.id
        self.persist_saved_notes(immediate=immediate)
        self.refresh_notes_list()
        if show_message:
            self.show_status_message("Note saved.")

    def manual_save_polished_note(self):
        self.save_note_from_text(
            self.polished_text_area.toPlainText(),
            ORIGIN_POLISHED_TEXT,
            create_if_missing=True,
            show_message=True,
            immediate=True,
        )

    def manual_save_raw_note(self):
        if self.notes_load_failed:
            self.show_status_message("Notes saving is paused until the notes file is repaired.")
            return
        trimmed = self.raw_text_area.toPlainText().strip()
        if not trimmed:
            self.show_status_message("Nothing to save.")
            return
        next_note = self.notes_store.new_note(trimmed, origin=ORIGIN_RAW_TEXT)
        self.saved_notes = self.saved_notes + [next_note]
        self.saved_notes.sort(key=lambda n: n.createdAt, reverse=True)
        self.persist_saved_notes(immediate=True)
        self.refresh_notes_list()
        self.show_status_message("Note saved.")

    def open_saved_note(self, note_id):
        note = next((n for n in self.saved_notes if n.id == note_id), None)
        if note is None:
            return
        self.active_note_id = note.id
        self.opened_note_id = note.id
        self.selected_note_ids = set()
        self._suppress_polished_autosave = True
        try:
            self.polished_text_area.setPlainText(note.content)
        finally:
            self._suppress_polished_autosave = False
        self.cursor_positions["polished_text_area"] = len(note.content)
        self.note_origin_label.setText(NotesStore.origin_label(note.origin))
        self.note_detail_edit.blockSignals(True)
        self.note_detail_edit.setPlainText(note.content)
        self.note_detail_edit.blockSignals(False)
        self.notes_stack.setCurrentIndex(1)
        self.refresh_notes_list()

    def close_saved_note(self):
        self.opened_note_id = None
        self.notes_stack.setCurrentIndex(0)
        self.note_origin_label.setText("")
        self.refresh_notes_list()

    def copy_opened_note(self):
        copy_text_to_clipboard(self.note_detail_edit.toPlainText())
        self.show_status_message("Note copied.")

    def _on_note_detail_changed(self):
        if not self.opened_note_id:
            return
        content = self.note_detail_edit.toPlainText()
        self._suppress_polished_autosave = True
        try:
            self.polished_text_area.setPlainText(content)
        finally:
            self._suppress_polished_autosave = False
        self.cursor_positions["polished_text_area"] = self.polished_text_area.textCursor().position()
        self.save_note_from_text(
            content,
            ORIGIN_POLISHED_TEXT,
            create_if_missing=False,
            show_message=False,
            immediate=False,
        )

    def _on_note_item_activated(self, item):
        note_id = item.data(Qt.UserRole)
        if note_id:
            self.open_saved_note(note_id)

    def _show_notes_context_menu(self, pos):
        item = self.notes_list.itemAt(pos)
        if item is None:
            return
        note_id = item.data(Qt.UserRole)
        if not note_id:
            return
        note = next((n for n in self.saved_notes if n.id == note_id), None)
        if note is None:
            return
        menu = QMenu(self)
        open_action = menu.addAction("Open")
        copy_action = menu.addAction("Copy")
        delete_action = menu.addAction("Delete")
        chosen = menu.exec(self.notes_list.mapToGlobal(pos))
        if chosen == open_action:
            self.open_saved_note(note_id)
        elif chosen == copy_action:
            copy_text_to_clipboard(note.content)
            self.show_status_message("Note copied.")
        elif chosen == delete_action:
            reply = QMessageBox.question(
                self,
                "Delete note",
                "Delete this note?",
                QMessageBox.Yes | QMessageBox.No,
                QMessageBox.No,
            )
            if reply == QMessageBox.Yes:
                self.delete_saved_note(note_id)

    def _on_note_item_changed(self, item):
        note_id = item.data(Qt.UserRole)
        if not note_id:
            return
        if item.checkState() == Qt.Checked:
            self.selected_note_ids.add(note_id)
        else:
            self.selected_note_ids.discard(note_id)
        has_selection = bool(self.selected_note_ids)
        self.clear_note_selection_button.setEnabled(has_selection)
        self.delete_selected_notes_button.setEnabled(has_selection)

    def clear_note_selection(self):
        self.selected_note_ids = set()
        self.refresh_notes_list()

    def delete_saved_note(self, note_id):
        self.saved_notes = [n for n in self.saved_notes if n.id != note_id]
        if self.active_note_id == note_id:
            self.active_note_id = None
        if self.opened_note_id == note_id:
            self.opened_note_id = None
            self.notes_stack.setCurrentIndex(0)
            if hasattr(self, "note_origin_label"):
                self.note_origin_label.setText("")
        self.selected_note_ids.discard(note_id)
        self.persist_saved_notes(immediate=True)
        self.refresh_notes_list()
        self.show_status_message("Note deleted.")

    def delete_selected_notes(self):
        if not self.selected_note_ids:
            return
        reply = QMessageBox.question(
            self,
            "Delete selected notes",
            f"Delete {len(self.selected_note_ids)} selected note(s)?",
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.No,
        )
        if reply != QMessageBox.Yes:
            return
        selected = set(self.selected_note_ids)
        self.saved_notes = [n for n in self.saved_notes if n.id not in selected]
        if self.active_note_id in selected:
            self.active_note_id = None
        if self.opened_note_id in selected:
            self.opened_note_id = None
            self.notes_stack.setCurrentIndex(0)
            if hasattr(self, "note_origin_label"):
                self.note_origin_label.setText("")
        self.selected_note_ids = set()
        self.persist_saved_notes(immediate=True)
        self.refresh_notes_list()
        self.show_status_message("Selected notes deleted.")

    def delete_all_saved_notes(self):
        if not self.saved_notes:
            return
        reply = QMessageBox.question(
            self,
            "Delete all notes",
            "Delete all saved notes?",
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.No,
        )
        if reply != QMessageBox.Yes:
            return
        self.saved_notes = []
        self.active_note_id = None
        self.opened_note_id = None
        self.selected_note_ids = set()
        self.notes_stack.setCurrentIndex(0)
        if hasattr(self, "note_origin_label"):
            self.note_origin_label.setText("")
        self.persist_saved_notes(immediate=True)
        self.refresh_notes_list()
        self.show_status_message("All notes deleted.")

    # --- Audio import ---
    def _cleanup_orphan_imports(self):
        try:
            now = datetime.now().timestamp()
            for name in os.listdir(self.imports_dir):
                path = os.path.join(self.imports_dir, name)
                if not os.path.isfile(path):
                    continue
                try:
                    age_hours = (now - os.path.getmtime(path)) / 3600.0
                    if age_hours >= 24:
                        os.remove(path)
                except OSError:
                    pass
        except OSError:
            pass

    def _parked_meta_path(self):
        return os.path.join(last_audio_dir(), "meta.json")

    def _clear_parked_audio_files(self, keep_path=None):
        directory = last_audio_dir()
        for name in os.listdir(directory):
            path = os.path.join(directory, name)
            if keep_path and os.path.abspath(path) == os.path.abspath(keep_path):
                continue
            try:
                if os.path.isfile(path):
                    os.remove(path)
            except OSError:
                pass
        previous = getattr(self, "imported_audio_path", None)
        if (
            previous
            and previous != keep_path
            and os.path.exists(previous)
        ):
            parked_root = os.path.abspath(directory)
            if os.path.abspath(previous).startswith(parked_root + os.sep):
                return
            try:
                os.remove(previous)
            except OSError:
                pass

    def _write_parked_meta(self, display_name):
        try:
            with open(self._parked_meta_path(), "w", encoding="utf-8") as handle:
                json.dump({"display_name": display_name}, handle)
        except OSError as e:
            print(f"DEBUG: Failed to write parked audio metadata: {e}")

    def _show_parked_audio(self, target, display_name):
        self.imported_audio_path = target
        self.imported_audio_name = display_name
        if hasattr(self, "import_label"):
            self.import_label.setText(display_name)
            self.import_strip.setVisible(True)

    def _atomic_write_bytes(self, target, data):
        directory = os.path.dirname(target)
        os.makedirs(directory, exist_ok=True)
        tmp = target + ".tmp"
        with open(tmp, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, target)

    def _park_audio_bytes(self, wav_bytes, filename, display_name):
        target = os.path.join(last_audio_dir(), filename)
        self._atomic_write_bytes(target, wav_bytes)
        self._clear_parked_audio_files(keep_path=target)
        self._write_parked_meta(display_name)
        self._show_parked_audio(target, display_name)
        return target

    def _park_audio_file(self, source_path, display_name):
        ext = os.path.splitext(source_path)[1] or ".audio"
        target = os.path.join(last_audio_dir(), f"last_audio{ext}")
        tmp = target + ".tmp"
        shutil.copy2(source_path, tmp)
        os.replace(tmp, target)
        self._clear_parked_audio_files(keep_path=target)
        self._write_parked_meta(display_name)
        self._show_parked_audio(target, display_name)
        return target

    def _restore_parked_audio(self):
        directory = last_audio_dir()
        display_name = "Last audio"
        meta_path = self._parked_meta_path()
        if os.path.isfile(meta_path):
            try:
                with open(meta_path, "r", encoding="utf-8") as handle:
                    meta = json.load(handle)
                display_name = meta.get("display_name") or display_name
            except Exception:
                pass
        audio_path = None
        for name in os.listdir(directory):
            if name == "meta.json" or name.endswith(".tmp"):
                continue
            path = os.path.join(directory, name)
            if os.path.isfile(path) and os.path.getsize(path) > 0:
                audio_path = path
                break
        if audio_path:
            self._show_parked_audio(audio_path, display_name)

    def open_audio_file(self):
        if self._is_audio_busy():
            self.show_status_message("Wait for the current audio operation to finish before importing another file.")
            return
        filepath, _ = QFileDialog.getOpenFileName(
            self,
            "Open Audio",
            "",
            "Audio Files (*.wav *.mp3 *.m4a *.ogg *.flac *.aac *.wma *.webm);;All Files (*.*)",
        )
        if not filepath:
            return
        try:
            display_name = os.path.basename(filepath)
            self._park_audio_file(filepath, display_name)
            self.show_main_view(0)
            self.show_status_message(f"{display_name} is ready.")
            self.transcribe_imported_audio()
        except Exception as e:
            self.show_error_message(f"Failed to import audio: {e}")

    def clear_imported_audio(self, delete_only=False):
        if self.is_import_transcribing and not delete_only:
            self.show_status_message("Wait for the current transcription to finish before clearing.")
            return
        path = self.imported_audio_path
        self.imported_audio_path = None
        self.imported_audio_name = None
        if not delete_only and hasattr(self, "import_strip"):
            self.import_strip.setVisible(False)
            self.import_label.setText("No audio imported")
        if self.is_import_transcribing:
            self._pending_import_delete = path
            return
        self._clear_parked_audio_files()
        if path and os.path.exists(path):
            try:
                os.remove(path)
            except OSError:
                pass

    def transcribe_imported_audio(self, from_listen=False):
        if not self.imported_audio_path or not os.path.exists(self.imported_audio_path):
            self.show_status_message("No audio file is loaded.")
            return
        if self._is_audio_busy() and not from_listen:
            self.show_status_message("Wait for the current audio operation to finish.")
            return

        if from_listen:
            self.start_record_processing()
        self.start_import_processing()
        path = self.imported_audio_path
        primary = self.get_effective_transcription_service()
        if primary == "Gemini" and not self.settings.get("api_key"):
            self.finish_import_processing()
            self.set_api_key()
            if not self.settings.get("api_key"):
                return
            if from_listen:
                self.start_record_processing()
            self.start_import_processing()
        threading.Thread(
            target=self.process_imported_audio_file,
            args=(path, primary, from_listen),
            daemon=True,
        ).start()

    def process_imported_audio_file(self, filepath, primary_service, from_listen=False):
        attempt_order = self.get_transcription_fallback_order(primary_service)
        failures = []
        try:
            for service_name in attempt_order:
                try:
                    if service_name == "Google":
                        audio_data = self._try_load_audio_data_from_file(filepath)
                        if audio_data is None:
                            raise RuntimeError("Google Speech requires WAV/FLAC/AIFF audio.")
                        text = self._transcribe_with_google(audio_data)
                    else:
                        text = self._transcribe_file_with_service(filepath, service_name)
                    if from_listen:
                        self.comm.text_ready.emit(text + " ")
                    else:
                        self.comm.import_text_ready.emit(text)
                    self.comm.status.emit("Transcription added to Raw Transcription.")
                    safe_debug(f"DEBUG: Import transcription via {service_name} succeeded.")
                    return
                except Exception as e:
                    failures.append(f"{service_name}: {safe_error_text(e)}")
                    safe_debug(f"DEBUG: Import transcription via {service_name} failed: {safe_error_text(e)}")
            self.comm.error.emit("All transcription attempts failed:\n" + "\n".join(failures))
        finally:
            self.comm.import_finished.emit()

    def _try_load_audio_data_from_file(self, filepath):
        try:
            with sr.AudioFile(filepath) as source:
                return self.recognizer.record(source)
        except Exception as e:
            print(f"DEBUG: Could not load audio file via SpeechRecognition: {e}")
            return None

    def _guess_audio_mime(self, filepath):
        ext = os.path.splitext(filepath)[1].lower()
        if ext == ".wav":
            return "audio/wav"
        if ext == ".mp3":
            return "audio/mpeg"
        if ext in (".m4a", ".aac"):
            return "audio/mp4"
        mime, _ = mimetypes.guess_type(filepath)
        return mime or "application/octet-stream"

    def _transcribe_file_with_service(self, filepath, service_name):
        if service_name == "Local":
            return self._transcribe_file_locally(filepath)
        if service_name == "Qwen 3 ASR Server":
            return self._transcribe_file_with_qwen(filepath)
        if service_name == "Gemini":
            return self._transcribe_file_with_gemini(filepath)
        audio_data = self._try_load_audio_data_from_file(filepath)
        if audio_data is None:
            raise RuntimeError("Google Speech requires WAV/FLAC/AIFF audio.")
        return self._transcribe_with_google(audio_data)

    def _transcribe_file_locally(self, filepath):
        if not self.whisper_model:
            model_name = self.settings.get("whisper_model", "base")
            WhisperModel = get_whisper_model_cls()
            self.whisper_model = WhisperModel(model_name, device="cpu", compute_type="int8")
        segments, _info = self.whisper_model.transcribe(filepath, beam_size=5)
        text = "".join(segment.text for segment in segments).strip()
        if not text:
            raise RuntimeError("Local transcription returned empty text.")
        return text

    def _transcribe_file_with_qwen(self, filepath):
        qwen_asr_url = self.get_qwen_asr_url()
        if not qwen_asr_url:
            raise RuntimeError("Qwen 3 ASR server URL is not configured.")
        import requests

        mime = self._guess_audio_mime(filepath)
        with open(filepath, "rb") as handle:
            response = requests.post(
                qwen_asr_url,
                files={"audio": (os.path.basename(filepath), handle, mime)},
                timeout=self.get_qwen_asr_timeout(),
            )
        response.raise_for_status()
        payload = response.json()
        text = payload.get("transcription", {}).get("parsed_text", "").strip()
        if not text:
            text = str(payload.get("text") or "").strip()
        if not text:
            raise RuntimeError("Qwen 3 ASR server returned an empty transcription.")
        return text

    def _transcribe_file_with_gemini(self, filepath):
        api_key = self.settings.get("api_key", "").strip()
        if not api_key:
            raise RuntimeError("Gemini API key is not configured.")
        genai = get_genai()
        genai.configure(api_key=api_key)
        model = genai.GenerativeModel(self.settings["gemini_model"])
        mime = self._guess_audio_mime(filepath)
        audio_file = None
        try:
            audio_file = genai.upload_file(filepath, mime_type=mime)
            audio_file = self._wait_for_gemini_file_active(genai, audio_file)
            response = model.generate_content(
                [GEMINI_TRANSCRIBE_PROMPT, audio_file],
                generation_config={"max_output_tokens": 8192, "temperature": 0},
            )
            return self._gemini_response_text(response)
        finally:
            if audio_file is not None:
                try:
                    resource_name = getattr(audio_file, "name", audio_file)
                    genai.delete_file(resource_name)
                except Exception as delete_error:
                    print(f"DEBUG: Failed to delete Gemini uploaded audio file: {delete_error}")

    # --- Ghost Cursor Implementation ---
    def eventFilter(self, watched, event):
        if watched in (self.raw_text_area, self.polished_text_area):
            if event.type() == QEvent.Type.FocusIn or event.type() == QEvent.Type.FocusOut:
                # Schedule the update after the event has been processed and focus has settled
                QTimer.singleShot(0, self._refresh_all_ghost_cursors)
        return super().eventFilter(watched, event)

    def _handle_cursor_position_changed(self):
        editor = self.sender()
        if isinstance(editor, QTextEdit) and editor.objectName() in self.cursor_positions:
            self.cursor_positions[editor.objectName()] = editor.textCursor().position()
            # No need to call _refresh_all_ghost_cursors here,
            # as focus hasn't changed. Stored position is updated.

    def _clear_ghost_cursor(self, text_edit):
        if text_edit:
            text_edit.setExtraSelections([])

    def _show_ghost_cursor(self, text_edit, stored_position):
        if not text_edit:
            return

        doc = text_edit.document()
        
        # Get the most current document length at the time of drawing
        current_doc_length = doc.characterCount()

        # Sanitize the stored_position against the actual current document reality
        position_to_use = stored_position
        if position_to_use < 0:
            position_to_use = 0
        if position_to_use > current_doc_length:
            position_to_use = current_doc_length
        
        if doc.isEmpty():
            text_edit.setExtraSelections([])
            return

        # At this point, doc is NOT empty (current_doc_length >= 1)
        # and 0 <= position_to_use <= current_doc_length.

        selection = QTextEdit.ExtraSelection()
        ghost_cursor_format = QTextCharFormat()
        current_theme = self.settings.get("theme", "dark")
        if current_theme == "dark":
            ghost_cursor_format.setBackground(QColor("#5A5A5A"))
        else:
            ghost_cursor_format.setBackground(QColor("#AAAAAA"))
        selection.format = ghost_cursor_format

        cursor_for_ghost = QTextCursor(doc)
        
        sel_start = -1
        sel_end = -1

        if position_to_use == current_doc_length: 
            # Cursor is at the very end of non-empty text. Highlight the last character.
            sel_start = position_to_use - 1
            sel_end = position_to_use 
        else: 
            # Cursor is on a character (position_to_use < current_doc_length). Highlight that character.
            sel_start = position_to_use
            sel_end = position_to_use + 1

        # Final safety check for selection range before applying
        if not (0 <= sel_start < current_doc_length and 0 < sel_end <= current_doc_length and sel_start < sel_end):
            text_edit.setExtraSelections([])
            return
        
        cursor_for_ghost.setPosition(sel_start)
        cursor_for_ghost.setPosition(sel_end, QTextCursor.MoveMode.KeepAnchor)
        
        selection.cursor = cursor_for_ghost
        text_edit.setExtraSelections([selection])

    def _refresh_all_ghost_cursors(self):
        if not hasattr(self, 'raw_text_area') or not self.raw_text_area: # Ensure UI is ready
            return
            
        focused_widget = QApplication.focusWidget()

        # Update raw_text_area ghost state
        if focused_widget == self.raw_text_area:
            self._clear_ghost_cursor(self.raw_text_area)
        else:
            self._show_ghost_cursor(self.raw_text_area, self.cursor_positions["raw_text_area"])

        # Update polished_text_area ghost state
        if focused_widget == self.polished_text_area:
            self._clear_ghost_cursor(self.polished_text_area)
        else:
            self._show_ghost_cursor(self.polished_text_area, self.cursor_positions["polished_text_area"])

    def toggle_recording_stick_mode(self):
        if self._stopping_recording or self._is_button_spinning("record"):
            return
        if self.is_recording:
            self.stop_recording()
        else:
            self.start_recording()

    def show_about_dialog(self):
        about_text = (
            f"<p><b>PressScribe</b></p>"
            f"<p>Version: 1.06</p>"
            f"<p>Author: Oleksii Konashevych</p>"
            f"<p>GitHub: <a href='https://github.com/konashevich/PressScribe-AI-Audio-Notes'>https://github.com/konashevich/PressScribe-AI-Audio-Notes</a></p>"
            f"<p>License: Open Source (MIT)</p>"
        )
        QMessageBox.about(self, "About PressScribe", about_text)

def check_dependencies():
    """Checks for necessary system dependencies (flac and xclip)."""
    missing = []

    # FLAC is typically installed via apt on Linux; skip the nag on Windows.
    if not sys.platform.startswith("win") and not shutil.which("flac"):
        missing.append("flac")

    # Check for xclip or xsel (clipboard helpers on Linux X11 sessions)
    if sys.platform.startswith("linux"):
        if not shutil.which("xclip") and not shutil.which("xsel"):
            missing.append("xclip (or xsel)")

    if missing:
        msg = (
            "The following system dependencies are missing and are required for the app to function correctly:\n\n"
            f"- {', '.join(missing)}\n\n"
            "Please install them using your package manager.\n"
            "Example for Ubuntu/Debian:\n"
            f"sudo apt-get install {' '.join([m.split()[0] for m in missing])}"
        )
        # We need a dummy app to show the message box if one doesn't exist yet,
        # but since we call this after QApplication creation, we are good.
        QMessageBox.warning(None, "Missing Dependencies", msg)

if __name__ == "__main__":
    configure_stdio_encoding()
    app = QApplication(sys.argv)
    app.setApplicationName("PressScribe")
    if sys.platform.startswith("linux"):
        app.setDesktopFileName("pressscribe.desktop")
    check_dependencies()
    window = MainWindow()
    if not window.ensure_welcome_setup():
        sys.exit(0)
    window.show()
    sys.exit(app.exec())
