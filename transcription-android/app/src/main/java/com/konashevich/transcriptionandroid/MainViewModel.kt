package com.konashevich.pressscribe

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.konashevich.pressscribe.audio.AudioRecorder
import com.konashevich.pressscribe.data.AppSettings
import com.konashevich.pressscribe.data.FontSizeOption
import com.konashevich.pressscribe.data.GeminiApiClient
import com.konashevich.pressscribe.data.ImportedAudio
import com.konashevich.pressscribe.data.ListenMode
import com.konashevich.pressscribe.data.NotesRepository
import com.konashevich.pressscribe.data.normalizeTranslateLanguageCode
import com.konashevich.pressscribe.data.parseDesktopSettingsImport
import com.konashevich.pressscribe.data.SavedNote
import com.konashevich.pressscribe.data.isConfiguredTranslateLanguage
import com.konashevich.pressscribe.data.SelfHostedAsrClient
import com.konashevich.pressscribe.data.ServerScheme
import com.konashevich.pressscribe.data.SettingsRepository
import com.konashevich.pressscribe.data.ThemeMode
import com.konashevich.pressscribe.data.TranscriptionService
import com.konashevich.pressscribe.data.VolumeButtonMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val rawTextValue: TextFieldValue = TextFieldValue(""),
    val polishedTextValue: TextFieldValue = TextFieldValue(""),
    val importedAudio: ImportedAudio? = null,
    val isRecording: Boolean = false,
    val listeningLevel: Float = 0f,
    val isImportingAudio: Boolean = false,
    val isTranscribing: Boolean = false,
    val isPolishing: Boolean = false,
    val isTranslating: Boolean = false,
    val savedNotes: List<SavedNote> = emptyList(),
    val activeNoteId: String? = null,
    val openedNoteId: String? = null,
    val selectedNoteIds: Set<String> = emptySet(),
) {
    val isAudioBusy: Boolean
        get() = isRecording || isImportingAudio || isTranscribing

    val isTextOpBusy: Boolean
        get() = isPolishing || isTranslating
}

sealed interface UiEvent {
    data class Snackbar(val message: String) : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val geminiApiClient = GeminiApiClient()
    private val selfHostedAsrClient = SelfHostedAsrClient()
    private val audioRecorder = AudioRecorder(application)
    private val notesRepository = NotesRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()
    private val pendingSharedImports = ArrayDeque<PendingImportRequest>()
    private var levelMeterJob: Job? = null
    private var transcriptionJob: Job? = null
    private var transcriptionGeneration: Int = 0
    private var transcriptionTargetPath: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        loadSavedNotes()
    }

    fun updateRawText(value: TextFieldValue) {
        _uiState.update { it.copy(rawTextValue = value) }
    }

    fun updatePolishedText(value: TextFieldValue) {
        _uiState.update { it.copy(polishedTextValue = value) }
        val state = _uiState.value
        if (state.activeNoteId != null && (state.settings.autoSaveNotes || state.openedNoteId != null)) {
            saveCurrentPolishedNote(createIfMissing = false, showMessage = false)
        }
    }

    fun clearRawText() {
        _uiState.update { it.copy(rawTextValue = TextFieldValue("")) }
    }

    fun clearPolishedText() {
        _uiState.update {
            it.copy(
                polishedTextValue = TextFieldValue(""),
                activeNoteId = null,
                openedNoteId = null,
            )
        }
    }

    fun clearAllText() {
        _uiState.update {
            it.copy(
                rawTextValue = TextFieldValue(""),
                polishedTextValue = TextFieldValue(""),
                activeNoteId = null,
                openedNoteId = null,
            )
        }
    }

    fun clearImportedAudio() {
        if (isAudioOperationInProgress()) {
            emitMessage("Wait for the current audio operation to finish before clearing the audio.")
            return
        }
        val audio = _uiState.value.importedAudio
        audio?.file?.delete()
        _uiState.update { it.copy(importedAudio = null) }
        processPendingSharedImportIfIdle()
    }

    fun handleSharedAudioUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }

        if (uris.size > 1) {
            emitMessage("Received ${uris.size} audio files. Using the first one.")
        }

        enqueueOrStartImport(
            PendingImportRequest(
                uri = uris.first(),
                sourceLabel = "Shared from another app",
                autoTranscribe = true,
            ),
        )
    }

    fun importAudioFromUri(
        uri: Uri,
        sourceLabel: String,
        autoTranscribe: Boolean,
    ) {
        enqueueOrStartImport(
            PendingImportRequest(
                uri = uri,
                sourceLabel = sourceLabel,
                autoTranscribe = autoTranscribe,
            ),
        )
    }

    fun startRecording() {
        val state = _uiState.value
        if (state.isRecording) {
            return
        }
        if (state.isTranscribing || state.isImportingAudio) {
            emitMessage("Wait for the current audio operation to finish.")
            return
        }

        runCatching {
            val file = newRecordingFile()
            audioRecorder.start(file)
        }.onSuccess {
            _uiState.update { it.copy(isRecording = true, listeningLevel = 0f) }
            startLevelMeter()
        }.onFailure { error ->
            emitMessage("Failed to start recording: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun stopRecording() {
        if (!_uiState.value.isRecording) {
            return
        }

        levelMeterJob?.cancel()
        levelMeterJob = null

        // Stop and promote synchronously before clearing isRecording so a quick re-Listen
        // cannot discard this capture via AudioRecorder.start() -> stopAndDiscard().
        val recordingFile = runCatching { audioRecorder.stop() }
            .getOrNull()
            ?.takeIf { it.exists() && it.length() > 0L }

        _uiState.update { it.copy(isRecording = false, listeningLevel = 0f) }

        if (recordingFile == null) {
            emitMessage("No usable recording was captured.")
            return
        }

        // Prefer the explicit Listen capture over any share/open queued while recording.
        discardPendingImportsPreferringRecording()

        // Always park the recording in the imported-audio slot before transcription.
        // Transcription outcome must never clear this; only a new Listen/Share/import may replace it.
        // Invalidate any in-flight transcription for a previous parked file.
        invalidateTranscription()
        replaceImportedAudio(
            ImportedAudio(
                file = recordingFile,
                displayName = recordingFile.name,
                mimeType = "audio/mp4",
                sourceLabel = "Recorded in app",
            ),
        )
        transcribeImportedAudio()
    }

    fun transcribeImportedAudio() {
        val importedAudio = _uiState.value.importedAudio
        if (importedAudio == null) {
            emitMessage("No audio file is loaded.")
            return
        }

        if (_uiState.value.isRecording) {
            emitMessage("Wait for recording to finish before transcribing.")
            return
        }

        if (_uiState.value.isImportingAudio) {
            emitMessage("Wait for the selected audio to finish importing.")
            return
        }

        val targetPath = importedAudio.file.absolutePath
        if (_uiState.value.isTranscribing && transcriptionTargetPath == targetPath) {
            return
        }

        invalidateTranscription()
        val generation = ++transcriptionGeneration
        transcriptionTargetPath = targetPath
        _uiState.update { it.copy(isTranscribing = true) }
        transcriptionJob = viewModelScope.launch {
            try {
                val settings = _uiState.value.settings
                val transcript = when (settings.transcriptionService) {
                    TranscriptionService.GEMINI ->
                        geminiApiClient.transcribeAudio(importedAudio.file, importedAudio.mimeType, settings)

                    TranscriptionService.SELF_HOSTED ->
                        selfHostedAsrClient.transcribeAudio(importedAudio.file, importedAudio.mimeType, settings)
                }

                ensureActive()
                if (generation != transcriptionGeneration) {
                    return@launch
                }
                if (_uiState.value.importedAudio?.file?.absolutePath != targetPath) {
                    return@launch
                }

                val trimmed = transcript.trim()
                if (trimmed.isEmpty()) {
                    emitMessage(
                        "Transcription returned no text. Audio kept — tap Transcribe to retry.",
                    )
                    return@launch
                }

                _uiState.update { state ->
                    state.copy(
                        rawTextValue = insertIntoField(
                            current = state.rawTextValue,
                            insertion = buildTranscriptInsertion(
                                currentText = state.rawTextValue.text,
                                transcript = trimmed,
                                sourceLabel = importedAudio.sourceLabel,
                            ),
                        ),
                    )
                }
                emitMessage("Transcription added to Raw Transcription.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == transcriptionGeneration) {
                    // Keep importedAudio as-is so the user can retry via Transcribe.
                    emitMessage(
                        "Transcription failed: ${error.message ?: error.javaClass.simpleName}. " +
                            "Audio kept — tap Transcribe to retry.",
                    )
                }
            } finally {
                if (generation == transcriptionGeneration) {
                    transcriptionJob = null
                    transcriptionTargetPath = null
                    _uiState.update { it.copy(isTranscribing = false) }
                    processPendingSharedImportIfIdle()
                }
            }
        }
    }

    fun polishText() {
        if (_uiState.value.isTextOpBusy) {
            if (!_uiState.value.isPolishing) {
                emitMessage("Wait for the current text operation to finish.")
            }
            return
        }

        val rawText = _uiState.value.rawTextValue
        val textToPolish = selectedText(rawText).ifBlank { rawText.text.trim() }
        if (textToPolish.isBlank()) {
            emitMessage("Nothing to polish.")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isPolishing = true) }
            try {
                val polished = geminiApiClient.polishText(textToPolish, _uiState.value.settings)
                _uiState.update { state ->
                    state.copy(
                        polishedTextValue = insertIntoField(
                            current = state.polishedTextValue,
                            insertion = polished.trim(),
                        ),
                    )
                }
                if (_uiState.value.settings.autoSaveNotes) {
                    saveCurrentPolishedNote(createIfMissing = true, showMessage = false)
                }
                emitMessage("Polished text added.")
            } catch (error: Exception) {
                emitMessage("Polish failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                _uiState.update { it.copy(isPolishing = false) }
            }
        }
    }

    fun polishAndTranslateText() {
        if (_uiState.value.isTextOpBusy) {
            if (!_uiState.value.isTranslating) {
                emitMessage("Wait for the current text operation to finish.")
            }
            return
        }

        val languageCode = _uiState.value.settings.translateLanguageCode.trim()
        if (!isConfiguredTranslateLanguage(languageCode)) {
            emitMessage("Choose a translation language first.")
            return
        }

        val rawText = _uiState.value.rawTextValue
        val textToProcess = selectedText(rawText).ifBlank { rawText.text.trim() }
        if (textToProcess.isBlank()) {
            emitMessage("Nothing to translate.")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }
            try {
                val translated = geminiApiClient.polishAndTranslateText(
                    text = textToProcess,
                    settings = _uiState.value.settings,
                )
                _uiState.update { state ->
                    state.copy(
                        polishedTextValue = insertIntoField(
                            current = state.polishedTextValue,
                            insertion = translated.trim(),
                        ),
                    )
                }
                if (_uiState.value.settings.autoSaveNotes) {
                    saveCurrentPolishedNote(createIfMissing = true, showMessage = false)
                }
                emitMessage("Translated text added.")
            } catch (error: Exception) {
                emitMessage("Translate failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                _uiState.update { it.copy(isTranslating = false) }
            }
        }
    }

    fun exportSessionTo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(buildSessionJson())
                    } ?: error("Could not open the session file for writing.")
                }
            }.onSuccess {
                emitMessage("Session saved.")
            }.onFailure { error ->
                emitMessage("Failed to save session: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun importSessionFrom(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    } ?: error("Could not open the session file.")
                }
            }.onSuccess { content ->
                loadSessionJson(content)
                emitMessage("Session loaded.")
            }.onFailure { error ->
                emitMessage("Failed to load session: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun exportImportedAudioTo(uri: Uri) {
        if (isAudioOperationInProgress()) {
            emitMessage("Wait for the current audio operation to finish before saving the audio.")
            return
        }

        val importedAudio = _uiState.value.importedAudio
        if (importedAudio == null) {
            emitMessage("No imported audio is loaded.")
            return
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                        importedAudio.file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("Could not open the audio file for writing.")
                }
            }.onSuccess {
                emitMessage("Audio saved.")
            }.onFailure { error ->
                emitMessage("Failed to save audio: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun importSettingsFrom(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val content = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    } ?: error("Could not open the settings file.")
                }

                val patch = parseDesktopSettingsImport(content)
                if (!patch.hasAnyValue()) {
                    error("The selected file did not contain any supported settings.")
                }

                settingsRepository.importSettings(patch)
            }.onSuccess {
                emitMessage("Settings imported.")
            }.onFailure { error ->
                emitMessage("Failed to import settings: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun suggestedSessionFileName(): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
        val snippet = _uiState.value.rawTextValue.text
            .trim()
            .split(Regex("\\s+"))
            .take(3)
            .joinToString("_")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { "transcription" }
        return "${now}_${snippet}.json"
    }

    fun updateThemeMode(value: ThemeMode) = persist { settingsRepository.updateThemeMode(value) }

    fun updateFontSize(value: FontSizeOption) = persist { settingsRepository.updateFontSize(value) }

    fun updateListenMode(value: ListenMode) = persist { settingsRepository.updateListenMode(value) }

    fun updateVolumeButtonMode(value: VolumeButtonMode) =
        persist { settingsRepository.updateVolumeButtonMode(value) }

    fun updateTranscriptionService(value: TranscriptionService) =
        persist { settingsRepository.updateTranscriptionService(value) }

    fun updateGeminiApiKey(value: String) = persist { settingsRepository.updateGeminiApiKey(value) }

    fun updateGeminiModel(value: String) = persist { settingsRepository.updateGeminiModel(value) }

    fun updatePolishPrompt(value: String) = persist { settingsRepository.updatePolishPrompt(value) }

    fun updateTranslatePolishPrompt(value: String) =
        persist { settingsRepository.updateTranslatePolishPrompt(value) }

    fun updateTranslateLanguageCode(value: String) {
        val normalized = normalizeTranslateLanguageCode(value)
        _uiState.update {
            it.copy(settings = it.settings.copy(translateLanguageCode = normalized))
        }
        persist { settingsRepository.updateTranslateLanguageCode(normalized) }
    }

    fun updateServerScheme(value: ServerScheme) = persist { settingsRepository.updateServerScheme(value) }

    fun updateServerHost(value: String) = persist { settingsRepository.updateServerHost(value) }

    fun updateServerPort(value: String) = persist { settingsRepository.updateServerPort(value) }

    fun updateServerPath(value: String) = persist { settingsRepository.updateServerPath(value) }

    fun updateServerTimeoutSeconds(value: String) {
        val parsed = value.toIntOrNull()
        if (parsed == null) {
            emitMessage("Timeout must be a whole number of seconds.")
            return
        }
        persist { settingsRepository.updateServerTimeoutSeconds(parsed) }
    }

    fun updateVibrationDurationMs(value: String) {
        val parsed = value.toIntOrNull()
        if (parsed == null) {
            emitMessage("Vibration duration must be a whole number of milliseconds.")
            return
        }
        persist { settingsRepository.updateVibrationDurationMs(parsed) }
    }

    fun updateAutoSaveNotes(value: Boolean) = persist { settingsRepository.updateAutoSaveNotes(value) }

    fun manualSaveCurrentNote() {
        saveNoteFromText(
            content = _uiState.value.polishedTextValue.text,
            origin = NotesRepository.ORIGIN_POLISHED_TEXT,
            createIfMissing = true,
            showMessage = true,
        )
    }

    fun manualSaveRawNote() {
        val trimmed = _uiState.value.rawTextValue.text.trim()
        if (trimmed.isBlank()) {
            emitMessage("Nothing to save.")
            return
        }

        val nextNote = notesRepository.newNote(
            content = trimmed,
            origin = NotesRepository.ORIGIN_RAW_TEXT,
        )
        val nextNotes = (_uiState.value.savedNotes + nextNote)
            .sortedByDescending { it.createdAt }

        _uiState.update { it.copy(savedNotes = nextNotes) }
        persistSavedNotes(nextNotes)
        emitMessage("Note saved.")
    }

    fun openSavedNote(noteId: String) {
        val note = _uiState.value.savedNotes.firstOrNull { it.id == noteId } ?: return
        _uiState.update {
            it.copy(
                polishedTextValue = TextFieldValue(note.content, selection = TextRange(note.content.length)),
                activeNoteId = note.id,
                openedNoteId = note.id,
                selectedNoteIds = emptySet(),
            )
        }
    }

    fun closeSavedNote() {
        _uiState.update { it.copy(openedNoteId = null) }
    }

    fun toggleNoteSelection(noteId: String) {
        _uiState.update { state ->
            val selected = state.selectedNoteIds.toMutableSet()
            if (!selected.add(noteId)) {
                selected.remove(noteId)
            }
            state.copy(selectedNoteIds = selected)
        }
    }

    fun clearNoteSelection() {
        _uiState.update { it.copy(selectedNoteIds = emptySet()) }
    }

    fun deleteSavedNote(noteId: String) {
        val nextNotes = _uiState.value.savedNotes.filterNot { it.id == noteId }
        _uiState.update { state ->
            state.copy(
                savedNotes = nextNotes,
                activeNoteId = state.activeNoteId.takeUnless { it == noteId },
                openedNoteId = state.openedNoteId.takeUnless { it == noteId },
                selectedNoteIds = state.selectedNoteIds - noteId,
            )
        }
        persistSavedNotes(nextNotes)
        emitMessage("Note deleted.")
    }

    fun deleteSelectedNotes() {
        val selected = _uiState.value.selectedNoteIds
        if (selected.isEmpty()) {
            return
        }
        val nextNotes = _uiState.value.savedNotes.filterNot { it.id in selected }
        _uiState.update { state ->
            state.copy(
                savedNotes = nextNotes,
                activeNoteId = state.activeNoteId.takeUnless { it in selected },
                openedNoteId = state.openedNoteId.takeUnless { it in selected },
                selectedNoteIds = emptySet(),
            )
        }
        persistSavedNotes(nextNotes)
        emitMessage("Selected notes deleted.")
    }

    fun deleteAllSavedNotes() {
        _uiState.update {
            it.copy(
                savedNotes = emptyList(),
                activeNoteId = null,
                openedNoteId = null,
                selectedNoteIds = emptySet(),
            )
        }
        persistSavedNotes(emptyList())
        emitMessage("All notes deleted.")
    }

    override fun onCleared() {
        levelMeterJob?.cancel()
        invalidateTranscription()
        audioRecorder.stopAndDiscard()
        _uiState.value.importedAudio?.file?.delete()
        super.onCleared()
    }

    private fun loadSavedNotes() {
        viewModelScope.launch {
            runCatching { notesRepository.loadNotes() }
                .onSuccess { notes ->
                    _uiState.update { it.copy(savedNotes = notes) }
                }
                .onFailure { error ->
                    emitMessage("Failed to load saved notes: ${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    private fun saveCurrentPolishedNote(
        createIfMissing: Boolean,
        showMessage: Boolean,
    ) {
        saveNoteFromText(
            content = _uiState.value.polishedTextValue.text,
            origin = NotesRepository.ORIGIN_POLISHED_TEXT,
            createIfMissing = createIfMissing,
            showMessage = showMessage,
        )
    }

    private fun saveNoteFromText(
        content: String,
        origin: String,
        createIfMissing: Boolean,
        showMessage: Boolean,
    ) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            if (showMessage) {
                emitMessage("Nothing to save.")
            }
            return
        }

        val state = _uiState.value
        val activeNote = state.activeNoteId?.let { activeId ->
            state.savedNotes.firstOrNull { it.id == activeId }
        }

        if (activeNote == null && !createIfMissing) {
            return
        }

        val nextNote = if (activeNote == null) {
            notesRepository.newNote(trimmed, origin = origin)
        } else {
            notesRepository.updateNote(activeNote, trimmed, origin = origin)
        }

        val nextNotes = (state.savedNotes.filterNot { it.id == nextNote.id } + nextNote)
            .sortedByDescending { it.createdAt }

        _uiState.update {
            it.copy(
                savedNotes = nextNotes,
                activeNoteId = nextNote.id,
            )
        }
        persistSavedNotes(nextNotes)
        if (showMessage) {
            emitMessage("Note saved.")
        }
    }

    private fun persistSavedNotes(notes: List<SavedNote>) {
        viewModelScope.launch {
            runCatching { notesRepository.saveNotes(notes) }
                .onFailure { error ->
                    emitMessage("Failed to save notes: ${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    private fun startLevelMeter() {
        levelMeterJob?.cancel()
        levelMeterJob = viewModelScope.launch {
            while (_uiState.value.isRecording) {
                val level = audioRecorder.currentLevel()
                _uiState.update { current ->
                    val smoothed = (current.listeningLevel * 0.58f) + (level * 0.42f)
                    current.copy(listeningLevel = smoothed.coerceIn(0f, 1f))
                }
                delay(70)
            }
        }
    }

    private fun startImport(request: PendingImportRequest) {
        _uiState.update { it.copy(isImportingAudio = true) }

        viewModelScope.launch {
            runCatching {
                copyUriToImportedAudio(request.uri, request.sourceLabel)
            }.onSuccess { importedAudio ->
                replaceImportedAudio(importedAudio)
                emitMessage("${importedAudio.displayName} is ready.")
                _uiState.update { it.copy(isImportingAudio = false) }
                if (request.autoTranscribe) {
                    transcribeImportedAudio()
                } else {
                    processPendingSharedImportIfIdle()
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isImportingAudio = false) }
                emitMessage("Failed to import audio: ${error.message ?: error.javaClass.simpleName}")
                processPendingSharedImportIfIdle()
            }
        }
    }

    private fun enqueueOrStartImport(request: PendingImportRequest) {
        if (isAudioOperationInProgress()) {
            // Keep-latest queue for both Open Audio and Share while busy.
            pendingSharedImports.clear()
            pendingSharedImports.addLast(request)
            emitMessage(
                if (_uiState.value.importedAudio != null || _uiState.value.isRecording) {
                    "Queued audio. Clear parked audio when ready to import it."
                } else {
                    "Queued audio. It will import when the current audio operation finishes."
                },
            )
            return
        }

        startImport(request)
    }

    private fun discardPendingImportsPreferringRecording() {
        if (pendingSharedImports.isEmpty()) {
            return
        }
        pendingSharedImports.clear()
        emitMessage("Kept your recording; discarded queued audio.")
    }

    private fun invalidateTranscription() {
        transcriptionGeneration += 1
        transcriptionJob?.cancel()
        transcriptionJob = null
        transcriptionTargetPath = null
        if (_uiState.value.isTranscribing) {
            _uiState.update { it.copy(isTranscribing = false) }
        }
    }

    private fun processPendingSharedImportIfIdle() {
        if (isAudioOperationInProgress()) {
            return
        }

        if (pendingSharedImports.isEmpty()) {
            return
        }

        // Keep parked Listen/import audio available for Transcribe retry until the user clears it.
        if (_uiState.value.importedAudio != null) {
            return
        }

        val nextRequest = pendingSharedImports.removeFirst()
        startImport(nextRequest)
    }

    private fun isAudioOperationInProgress(): Boolean {
        return _uiState.value.isAudioBusy
    }

    private suspend fun copyUriToImportedAudio(uri: Uri, sourceLabel: String): ImportedAudio {
        val resolver = getApplication<Application>().contentResolver
        val displayName = queryDisplayName(uri) ?: "audio_${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { guessMimeType(displayName) }
        val cacheDir = File(getApplication<Application>().cacheDir, "imports").apply { mkdirs() }
        val safeName = displayName.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
        val targetFile = File(cacheDir, "${System.currentTimeMillis()}_$safeName")

        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("The selected audio file could not be opened.")
        }

        return ImportedAudio(
            file = targetFile,
            displayName = displayName,
            mimeType = mimeType.ifBlank { "audio/*" },
            sourceLabel = sourceLabel,
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
    }

    private fun newRecordingFile(): File {
        val dir = File(getApplication<Application>().cacheDir, "recordings").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()
        return File(dir, "recording_$timestamp.m4a")
    }

    private fun replaceImportedAudio(newAudio: ImportedAudio) {
        val existing = _uiState.value.importedAudio
        if (existing?.file?.absolutePath != newAudio.file.absolutePath) {
            existing?.file?.delete()
        }
        _uiState.update { it.copy(importedAudio = newAudio) }
    }

    private fun buildSessionJson(): String {
        return JSONObject()
            .put("raw_text", _uiState.value.rawTextValue.text)
            .put("polished_text", _uiState.value.polishedTextValue.text)
            .toString(2)
    }

    private fun loadSessionJson(content: String) {
        val root = JSONObject(content)
        val rawText = root.optString("raw_text")
        val polishedText = root.optString("polished_text")
        _uiState.update {
            it.copy(
                rawTextValue = TextFieldValue(rawText, selection = TextRange(rawText.length)),
                polishedTextValue = TextFieldValue(
                    polishedText,
                    selection = TextRange(polishedText.length),
                ),
                activeNoteId = null,
                openedNoteId = null,
                selectedNoteIds = emptySet(),
            )
        }
    }

    private fun emitMessage(message: String) {
        viewModelScope.launch {
            _events.emit(UiEvent.Snackbar(message))
        }
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { emitMessage("Failed to save setting: ${it.message ?: it.javaClass.simpleName}") }
        }
    }
}

private fun selectedText(value: TextFieldValue): String {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    if (start == end) {
        return ""
    }
    return value.text.substring(start, end).trim()
}

private fun insertIntoField(current: TextFieldValue, insertion: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val newText = buildString {
        append(current.text.substring(0, start))
        append(insertion)
        append(current.text.substring(end))
    }
    val newCursor = start + insertion.length
    return current.copy(text = newText, selection = TextRange(newCursor))
}

private fun buildTranscriptInsertion(
    currentText: String,
    transcript: String,
    sourceLabel: String,
): String {
    val trimmed = transcript.trim()
    if (trimmed.isEmpty()) {
        return ""
    }

    return if (sourceLabel == "Recorded in app") {
        if (currentText.isBlank()) {
            trimmed
        } else {
            " $trimmed"
        }
    } else {
        if (currentText.isBlank()) {
            trimmed
        } else {
            "\n\n$trimmed"
        }
    }
}

private data class PendingImportRequest(
    val uri: Uri,
    val sourceLabel: String,
    val autoTranscribe: Boolean,
)
