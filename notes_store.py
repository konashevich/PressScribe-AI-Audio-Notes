"""Saved notes persistence matching the Android PressScribe notes format."""

from __future__ import annotations

import json
import os
import tempfile
import uuid
from dataclasses import asdict, dataclass
from typing import List, Optional


ORIGIN_POLISHED_TEXT = "polished_text"
ORIGIN_RAW_TEXT = "raw_text"


class NotesLoadError(Exception):
    """Raised when saved_notes.json exists but cannot be parsed or read."""


@dataclass
class SavedNote:
    id: str
    content: str
    createdAt: int
    updatedAt: int
    origin: str = ORIGIN_POLISHED_TEXT


class NotesStore:
    def __init__(self, notes_path: str):
        self.notes_path = notes_path

    def load_notes(self) -> List[SavedNote]:
        if not os.path.exists(self.notes_path):
            return []
        try:
            with open(self.notes_path, "r", encoding="utf-8") as handle:
                raw = handle.read().strip()
            if not raw:
                return []
            items = json.loads(raw)
            if not isinstance(items, list):
                raise NotesLoadError("Notes file must contain a JSON array.")
            notes = []
            for item in items:
                note = self._from_dict(item)
                if note is not None:
                    notes.append(note)
            return sorted(notes, key=lambda note: note.createdAt, reverse=True)
        except NotesLoadError:
            raise
        except (OSError, json.JSONDecodeError, TypeError, ValueError) as exc:
            raise NotesLoadError(f"Could not read notes file: {exc}") from exc

    def save_notes(self, notes: List[SavedNote]) -> None:
        parent = os.path.dirname(os.path.abspath(self.notes_path)) or "."
        os.makedirs(parent, exist_ok=True)
        payload = [asdict(note) for note in sorted(notes, key=lambda n: n.createdAt, reverse=True)]
        fd, temp_path = tempfile.mkstemp(prefix="saved_notes_", suffix=".json", dir=parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, indent=2)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, self.notes_path)
        except Exception:
            try:
                if os.path.exists(temp_path):
                    os.remove(temp_path)
            except OSError:
                pass
            raise

    @staticmethod
    def new_note(content: str, origin: str = ORIGIN_POLISHED_TEXT) -> SavedNote:
        now = _now_ms()
        return SavedNote(
            id=str(uuid.uuid4()),
            content=content,
            createdAt=now,
            updatedAt=now,
            origin=origin,
        )

    @staticmethod
    def update_note(note: SavedNote, content: str, origin: Optional[str] = None) -> SavedNote:
        return SavedNote(
            id=note.id,
            content=content,
            createdAt=note.createdAt,
            updatedAt=_now_ms(),
            origin=origin if origin is not None else note.origin,
        )

    @staticmethod
    def note_title(content: str, max_words: int = 8) -> str:
        words = content.strip().split()
        if not words:
            return "Untitled note"
        title = " ".join(words[:max_words])
        if len(words) > max_words:
            title += "…"
        return title

    @staticmethod
    def note_preview(content: str, max_chars: int = 120) -> str:
        text = " ".join(content.strip().split())
        if len(text) <= max_chars:
            return text
        return text[: max_chars - 1] + "…"

    @staticmethod
    def origin_label(origin: str) -> str:
        if origin == ORIGIN_RAW_TEXT:
            return "Raw"
        return "Polished"

    @staticmethod
    def _from_dict(item) -> Optional[SavedNote]:
        if not isinstance(item, dict):
            return None
        note_id = str(item.get("id") or "").strip()
        if not note_id:
            return None
        try:
            created_at = int(item.get("createdAt") or 0)
        except (TypeError, ValueError):
            return None
        if created_at <= 0:
            return None
        try:
            updated_at = int(item.get("updatedAt") or created_at)
        except (TypeError, ValueError):
            updated_at = created_at
        origin = str(item.get("origin") or "").strip() or ORIGIN_POLISHED_TEXT
        return SavedNote(
            id=note_id,
            content=str(item.get("content") or ""),
            createdAt=created_at,
            updatedAt=updated_at,
            origin=origin,
        )


def _now_ms() -> int:
    import time

    return int(time.time() * 1000)
