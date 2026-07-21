package com.konashevich.pressscribe.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedNote(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val origin: String,
)

class NotesRepository(context: Context) {
    private val notesFile = File(context.filesDir, "saved_notes.json")

    suspend fun loadNotes(): List<SavedNote> = withContext(Dispatchers.IO) {
        if (!notesFile.exists()) {
            return@withContext emptyList()
        }

        val content = notesFile.readText()
        if (content.isBlank()) {
            return@withContext emptyList()
        }

        val root = JSONArray(content)
        buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val note = item.toSavedNote() ?: continue
                add(note)
            }
        }.sortedByDescending { it.createdAt }
    }

    suspend fun saveNotes(notes: List<SavedNote>) = withContext(Dispatchers.IO) {
        notesFile.parentFile?.mkdirs()
        val root = JSONArray()
        notes.sortedByDescending { it.createdAt }.forEach { note ->
            root.put(note.toJson())
        }
        notesFile.writeText(root.toString(2))
    }

    fun newNote(content: String, origin: String = ORIGIN_POLISHED_TEXT): SavedNote {
        val now = System.currentTimeMillis()
        return SavedNote(
            id = UUID.randomUUID().toString(),
            title = titleFromContent(content),
            content = content,
            createdAt = now,
            updatedAt = now,
            origin = origin,
        )
    }

    fun updateNote(note: SavedNote, content: String): SavedNote {
        return note.copy(
            title = titleFromContent(content),
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        const val ORIGIN_POLISHED_TEXT = "polished_text"
        const val ORIGIN_RAW_TEXT = "raw_text"

        fun titleFromContent(content: String): String {
            return content
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .take(8)
                .joinToString(" ")
                .ifBlank { "Untitled note" }
        }
    }
}

private fun JSONObject.toSavedNote(): SavedNote? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    val content = optString("content")
    val createdAt = optLong("createdAt", 0L).takeIf { it > 0L } ?: return null
    val updatedAt = optLong("updatedAt", createdAt)
    return SavedNote(
        id = id,
        title = optString("title").ifBlank { NotesRepository.titleFromContent(content) },
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        origin = optString("origin").ifBlank { NotesRepository.ORIGIN_POLISHED_TEXT },
    )
}

private fun SavedNote.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("content", content)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("origin", origin)
}
