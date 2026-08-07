package com.konashevich.pressscribe.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.konashevich.pressscribe.MainUiState
import com.konashevich.pressscribe.MainViewModel
import com.konashevich.pressscribe.UiEvent
import com.konashevich.pressscribe.data.ImportedAudio
import com.konashevich.pressscribe.data.ListenMode
import com.konashevich.pressscribe.data.NotesRepository
import com.konashevich.pressscribe.data.PRIVACY_POLICY_URL
import com.konashevich.pressscribe.data.SavedNote
import com.konashevich.pressscribe.data.TERMS_OF_SERVICE_URL
import com.konashevich.pressscribe.data.findTranslateLanguage
import com.konashevich.pressscribe.data.isConfiguredTranslateLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TranscriptionApp(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appHaptics = remember(context) { AppHaptics(context) }
    val uiScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showWelcome by rememberSaveable { mutableStateOf(false) }
    var welcomeSessionId by rememberSaveable { mutableIntStateOf(0) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showTranslateLanguagePicker by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var importedExpanded by rememberSaveable(state.importedAudio?.file?.absolutePath) { mutableStateOf(false) }
    var previousRecordingState by remember { mutableStateOf(state.isRecording) }
    var wideScreen by rememberSaveable { mutableStateOf(EditorScreen.EDITOR) }
    var pendingDelete by remember { mutableStateOf<DeleteRequest?>(null) }
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted && state.settings.listenMode == ListenMode.TOGGLE) {
            viewModel.startRecording()
        }
    }

    LaunchedEffect(state.isRecording, state.settings.vibrationDurationMs) {
        if (state.isRecording != previousRecordingState) {
            appHaptics.vibrate(state.settings.vibrationDurationMs)
            previousRecordingState = state.isRecording
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasRecordPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openSessionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importSessionFrom)
    }

    val saveSessionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(viewModel::exportSessionTo)
    }

    val openAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            viewModel.importAudioFromUri(
                uri = it,
                sourceLabel = "Picked on device",
                autoTranscribe = false,
            )
        }
    }

    val importSettingsLauncher = rememberLauncherForActivityResult(
        contract = OpenDocumentFromInitialUri(downloadsInitialUri()),
    ) { uri ->
        uri?.let(viewModel::importSettingsFrom)
    }

    val saveImportedAudioLauncher = rememberLauncherForActivityResult(
        contract = CreateDocumentFromInitialUri(downloadsInitialUri()),
    ) { uri ->
        uri?.let(viewModel::exportImportedAudioTo)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Snackbar -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val dismissJob = launch {
                        delay(2700)
                        snackbarHostState.currentSnackbarData?.dismiss()
                    }
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Indefinite,
                    )
                    dismissJob.cancel()
                }
            }
        }
    }

    val ensureRecordPermission = {
        if (hasRecordPermission) {
            viewModel.startRecording()
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val sharePolishedText = {
        val text = state.polishedTextValue.text.trim()
        if (text.isNotEmpty()) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share polished text"))
        }
    }

    val openSavedNotes: () -> Unit = {
        wideScreen = EditorScreen.NOTES
        uiScope.launch { pagerState.animateScrollToPage(1) }
        Unit
    }

    // Wait for DataStore before deciding welcome vs main — defaults have welcomeCompleted=false.
    if (!state.settingsReady) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        return
    }

    val showWelcomeOverlay = !state.settings.welcomeCompleted || showWelcome
    if (showWelcomeOverlay) {
        val isFirstRun = !state.settings.welcomeCompleted
        Box(modifier = Modifier.fillMaxSize()) {
            key(welcomeSessionId) {
                WelcomeSetupScreen(
                    sessionId = welcomeSessionId,
                    initialApiKey = state.settings.geminiApiKey,
                    initialListenMode = if (isFirstRun) null else state.settings.listenMode,
                    initialVolumeMode = if (isFirstRun) null else state.settings.volumeButtonMode,
                    isFirstRun = isFirstRun,
                    isTestingApiKey = state.isTestingApiKey,
                    isCompletingWelcome = state.isCompletingWelcome,
                    onQuit = { (context as? Activity)?.finish() },
                    onDismiss = { showWelcome = false },
                    onTestApiKey = viewModel::testGeminiApiKey,
                    onComplete = { apiKey, listenMode, volumeMode ->
                        viewModel.completeWelcomeSetup(apiKey, listenMode, volumeMode) { saved ->
                            if (saved) {
                                showWelcome = false
                            }
                        }
                    },
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Never let the soft keyboard resize the editor layout. Otherwise focusing the
        // raw field and then pressing Listen shoves the mic button under the finger.
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets.statusBars,
                    title = {
                        Column {
                            Text("PressScribe", maxLines = 1)
                            Text(
                                text = state.settings.transcriptionService.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                wideScreen = if (wideScreen == EditorScreen.NOTES) {
                                    EditorScreen.EDITOR
                                } else {
                                    EditorScreen.NOTES
                                }
                                uiScope.launch {
                                    pagerState.animateScrollToPage(if (pagerState.currentPage == 0) 1 else 0)
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Saved notes")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open Session") },
                                onClick = {
                                    menuExpanded = false
                                    openSessionLauncher.launch(arrayOf("application/json"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Save Session") },
                                onClick = {
                                    menuExpanded = false
                                    saveSessionLauncher.launch(viewModel.suggestedSessionFileName())
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open Audio") },
                                onClick = {
                                    menuExpanded = false
                                    openAudioLauncher.launch(arrayOf("audio/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showAbout = true
                                },
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val wideLayout = maxWidth >= 900.dp

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!state.isRecording) {
                            state.importedAudio?.let { importedAudio ->
                                ImportedAudioCard(
                                    importedAudio = importedAudio,
                                    expanded = importedExpanded,
                                    isBusy = state.isTranscribing || state.isImportingAudio,
                                    onExpandedChange = { importedExpanded = it },
                                    onTranscribe = viewModel::transcribeImportedAudio,
                                    onSave = {
                                        saveImportedAudioLauncher.launch(
                                            CreateDocumentRequest(
                                                mimeType = importedAudio.mimeType,
                                                displayName = importedAudio.displayName,
                                            ),
                                        )
                                    },
                                    onClear = viewModel::clearImportedAudio,
                                )
                            }
                        }

                        if (wideLayout) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { wideScreen = EditorScreen.EDITOR }) {
                                    Text("Editor")
                                }
                                Button(onClick = { wideScreen = EditorScreen.NOTES }) {
                                    Text("Saved Notes")
                                }
                            }
                            if (wideScreen == EditorScreen.EDITOR) {
                                EditorContent(
                                    modifier = Modifier.fillMaxSize(),
                                    wideLayout = true,
                                    state = state,
                                    viewModel = viewModel,
                                    hasRecordPermission = hasRecordPermission,
                                    onEnsurePermission = ensureRecordPermission,
                                    onRequestPermission = { recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    copyRawText = { clipboardManager.setText(AnnotatedString(state.rawTextValue.text)) },
                                    copyPolishedText = { clipboardManager.setText(AnnotatedString(state.polishedTextValue.text)) },
                                    sharePolishedText = sharePolishedText,
                                    onOpenNotes = openSavedNotes,
                                    onRequestTranslateLanguage = { showTranslateLanguagePicker = true },
                                )
                            } else {
                                SavedNotesScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    state = state,
                                    onOpenNote = viewModel::openSavedNote,
                                    onCloseNote = viewModel::closeSavedNote,
                                    onUpdateNote = viewModel::updatePolishedText,
                                    onCopyNote = { text -> clipboardManager.setText(AnnotatedString(text)) },
                                    onDeleteNote = { pendingDelete = DeleteRequest.One(it) },
                                    onToggleSelected = viewModel::toggleNoteSelection,
                                    onClearSelection = viewModel::clearNoteSelection,
                                    onDeleteSelected = { pendingDelete = DeleteRequest.Selected },
                                    onDeleteAll = { pendingDelete = DeleteRequest.All },
                                )
                            }
                        } else {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                if (page == 0) {
                                    EditorContent(
                                        modifier = Modifier.fillMaxSize(),
                                        wideLayout = false,
                                        state = state,
                                        viewModel = viewModel,
                                        hasRecordPermission = hasRecordPermission,
                                        onEnsurePermission = ensureRecordPermission,
                                        onRequestPermission = { recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                        copyRawText = { clipboardManager.setText(AnnotatedString(state.rawTextValue.text)) },
                                        copyPolishedText = { clipboardManager.setText(AnnotatedString(state.polishedTextValue.text)) },
                                        sharePolishedText = sharePolishedText,
                                        onOpenNotes = openSavedNotes,
                                        onRequestTranslateLanguage = { showTranslateLanguagePicker = true },
                                    )
                                } else {
                                    SavedNotesScreen(
                                        modifier = Modifier.fillMaxSize(),
                                        state = state,
                                        onOpenNote = viewModel::openSavedNote,
                                        onCloseNote = viewModel::closeSavedNote,
                                        onUpdateNote = viewModel::updatePolishedText,
                                        onCopyNote = { text -> clipboardManager.setText(AnnotatedString(text)) },
                                        onDeleteNote = { pendingDelete = DeleteRequest.One(it) },
                                        onToggleSelected = viewModel::toggleNoteSelection,
                                        onClearSelection = viewModel::clearNoteSelection,
                                        onDeleteSelected = { pendingDelete = DeleteRequest.Selected },
                                        onDeleteAll = { pendingDelete = DeleteRequest.All },
                                    )
                                }
                            }
                        }
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        ListeningGlowOverlay(
            active = state.isRecording,
            level = state.listeningLevel,
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onDismiss = { showSettings = false },
            onThemeChanged = viewModel::updateThemeMode,
            onFontSizeChanged = viewModel::updateFontSize,
            onListenModeChanged = viewModel::updateListenMode,
            onVolumeButtonModeChanged = viewModel::updateVolumeButtonMode,
            onTranscriptionServiceChanged = viewModel::updateTranscriptionService,
            onGeminiApiKeyChanged = viewModel::updateGeminiApiKey,
            onGeminiModelChanged = viewModel::updateGeminiModel,
            onPolishPromptChanged = viewModel::updatePolishPrompt,
            onTranslatePolishPromptChanged = viewModel::updateTranslatePolishPrompt,
            onTranslateLanguageChanged = viewModel::updateTranslateLanguageCode,
            onServerSchemeChanged = viewModel::updateServerScheme,
            onServerHostChanged = viewModel::updateServerHost,
            onServerPortChanged = viewModel::updateServerPort,
            onServerPathChanged = viewModel::updateServerPath,
            onServerTimeoutChanged = viewModel::updateServerTimeoutSeconds,
            onVibrationDurationChanged = viewModel::updateVibrationDurationMs,
            onAutoSaveNotesChanged = viewModel::updateAutoSaveNotes,
            onImportSettings = {
                showSettings = false
                importSettingsLauncher.launch(arrayOf("application/json"))
            },
            onShowWelcomeSetup = {
                showSettings = false
                welcomeSessionId += 1
                showWelcome = true
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text("Close")
                }
            },
            title = { Text("About") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Android edition of PressScribe with the same raw/polished workflow, " +
                            "Gemini polishing, Gemini or self-hosted transcription, and audio sharing from other apps.",
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)),
                            )
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Privacy Policy")
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_OF_SERVICE_URL)),
                            )
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Terms of Service")
                    }
                }
            },
        )
    }

    if (showTranslateLanguagePicker) {
        TranslateLanguagePickerDialog(
            selectedCode = state.settings.translateLanguageCode,
            onDismiss = { showTranslateLanguagePicker = false },
            onConfirm = { code ->
                viewModel.updateTranslateLanguageCode(code)
                showTranslateLanguagePicker = false
            },
        )
    }

    pendingDelete?.let { request ->
        ConfirmDeleteDialog(
            request = request,
            selectedCount = state.selectedNoteIds.size,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                when (request) {
                    DeleteRequest.All -> viewModel.deleteAllSavedNotes()
                    is DeleteRequest.One -> viewModel.deleteSavedNote(request.noteId)
                    DeleteRequest.Selected -> viewModel.deleteSelectedNotes()
                }
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun EditorContent(
    modifier: Modifier,
    wideLayout: Boolean,
    state: MainUiState,
    viewModel: MainViewModel,
    hasRecordPermission: Boolean,
    onEnsurePermission: () -> Unit,
    onRequestPermission: () -> Unit,
    copyRawText: () -> Unit,
    copyPolishedText: () -> Unit,
    sharePolishedText: () -> Unit,
    onOpenNotes: () -> Unit,
    onRequestTranslateLanguage: () -> Unit,
) {
    if (wideLayout) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RawEditorPanel(
                modifier = Modifier.weight(1f),
                state = state,
                viewModel = viewModel,
                hasRecordPermission = hasRecordPermission,
                onEnsurePermission = onEnsurePermission,
                onRequestPermission = onRequestPermission,
                clipboardText = copyRawText,
                onRequestTranslateLanguage = onRequestTranslateLanguage,
            )
            PolishedEditorPanel(
                modifier = Modifier.weight(1f),
                state = state,
                viewModel = viewModel,
                clipboardText = copyPolishedText,
                shareText = sharePolishedText,
                onOpenNotes = onOpenNotes,
            )
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RawEditorPanel(
                modifier = Modifier.weight(1f),
                state = state,
                viewModel = viewModel,
                hasRecordPermission = hasRecordPermission,
                onEnsurePermission = onEnsurePermission,
                onRequestPermission = onRequestPermission,
                clipboardText = copyRawText,
                onRequestTranslateLanguage = onRequestTranslateLanguage,
            )
            PolishedEditorPanel(
                modifier = Modifier.weight(1f),
                state = state,
                viewModel = viewModel,
                clipboardText = copyPolishedText,
                shareText = sharePolishedText,
                onOpenNotes = onOpenNotes,
            )
        }
    }
}

@Composable
private fun SavedNotesScreen(
    modifier: Modifier,
    state: MainUiState,
    onOpenNote: (String) -> Unit,
    onCloseNote: () -> Unit,
    onUpdateNote: (TextFieldValue) -> Unit,
    onCopyNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val openedNote = state.openedNoteId?.let { openedId ->
        state.savedNotes.firstOrNull { it.id == openedId }
    }

    if (openedNote != null) {
        SavedNoteDetail(
            modifier = modifier,
            note = openedNote,
            value = state.polishedTextValue,
            onValueChange = onUpdateNote,
            onBack = onCloseNote,
            onCopy = { onCopyNote(state.polishedTextValue.text) },
        )
    } else {
        SavedNotesList(
            modifier = modifier,
            notes = state.savedNotes,
            selectedNoteIds = state.selectedNoteIds,
            onOpenNote = onOpenNote,
            onCopyNote = onCopyNote,
            onDeleteNote = onDeleteNote,
            onToggleSelected = onToggleSelected,
            onClearSelection = onClearSelection,
            onDeleteSelected = onDeleteSelected,
            onDeleteAll = onDeleteAll,
        )
    }
}

@Composable
private fun SavedNotesList(
    modifier: Modifier,
    notes: List<SavedNote>,
    selectedNoteIds: Set<String>,
    onOpenNote: (String) -> Unit,
    onCopyNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Saved Notes",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (selectedNoteIds.isNotEmpty()) {
                    ActionIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Clear selection",
                        onClick = onClearSelection,
                    )
                    ActionIconButton(
                        icon = Icons.Filled.DeleteSweep,
                        contentDescription = "Delete selected notes",
                        onClick = onDeleteSelected,
                    )
                }
                ActionIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete all notes",
                    enabled = notes.isNotEmpty(),
                    onClick = onDeleteAll,
                )
            }

            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No saved notes",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        SavedNoteRow(
                            note = note,
                            selected = note.id in selectedNoteIds,
                            onOpen = { onOpenNote(note.id) },
                            onCopy = { onCopyNote(note.content) },
                            onDelete = { onDeleteNote(note.id) },
                            onToggleSelected = { onToggleSelected(note.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedNoteRow(
    note: SavedNote,
    selected: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIconButton(
                icon = if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = if (selected) "Deselect note" else "Select note",
                onClick = onToggleSelected,
            )
            Text(
                text = rememberSavedNotePreview(note),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ActionIconButton(
                icon = Icons.Filled.ContentCopy,
                contentDescription = "Copy note",
                onClick = onCopy,
            )
            ActionIconButton(
                icon = Icons.Filled.Delete,
                contentDescription = "Delete note",
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun rememberSavedNotePreview(note: SavedNote): AnnotatedString {
    val tag = savedNoteOriginLabel(note.origin)
    val tagColor = MaterialTheme.colorScheme.primary
    val content = note.content.trim()
    return remember(note.id, note.origin, note.content, tagColor) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    color = tagColor,
                ),
            ) {
                append("$tag: ")
            }
            append(content)
        }
    }
}

private fun savedNoteOriginLabel(origin: String): String =
    when (origin) {
        NotesRepository.ORIGIN_RAW_TEXT -> "Raw"
        else -> "Polished"
    }

@Composable
private fun SavedNoteDetail(
    modifier: Modifier,
    note: SavedNote,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        val editorTextStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 16.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to saved notes",
                    onClick = onBack,
                )
                Text(
                    text = savedNoteOriginLabel(note.origin),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ActionIconButton(
                    icon = Icons.Filled.ContentCopy,
                    contentDescription = "Copy note",
                    onClick = onCopy,
                )
            }

            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 260.dp),
                value = value,
                onValueChange = onValueChange,
                textStyle = editorTextStyle,
                singleLine = false,
            )
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    request: DeleteRequest,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (request) {
        DeleteRequest.All -> "Delete all notes?"
        is DeleteRequest.One -> "Delete note?"
        DeleteRequest.Selected -> "Delete selected notes?"
    }
    val message = when (request) {
        DeleteRequest.All -> "This removes every saved note from this device."
        is DeleteRequest.One -> "This removes the selected note from this device."
        DeleteRequest.Selected -> "This removes $selectedCount selected notes from this device."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text(title) },
        text = { Text(message) },
    )
}

private enum class EditorScreen {
    EDITOR,
    NOTES,
}

private sealed interface DeleteRequest {
    data class One(val noteId: String) : DeleteRequest
    data object Selected : DeleteRequest
    data object All : DeleteRequest
}

@Composable
private fun ListeningGlowOverlay(
    active: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val visibility by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "listening_glow_visibility",
    )
    // Keep infinite transitions unconditional so Compose remember order stays stable
    // when the overlay fades in/out.
    val transition = rememberInfiniteTransition(label = "listening_glow")
    val breathing by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "listening_glow_breathing",
    )
    val shimmer by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "listening_glow_shimmer",
    )

    if (visibility <= 0.001f) {
        return
    }

    Canvas(modifier = modifier) {
        // Keep a strong base so the rim stays visible even when MediaRecorder
        // reports flat amplitude (common on quiet input / some devices).
        val speechBoost = (0.55f + (level.coerceIn(0f, 1f) * 0.45f)) * visibility
        val outerStroke = 22.dp.toPx() * breathing
        val middleStroke = 14.dp.toPx() * shimmer
        val innerStroke = 9.dp.toPx()
        val cornerRadius = 24.dp.toPx()
        // Inset so strokes stay fully on-canvas (stroke is centered on the path).
        val inset = outerStroke / 2f
        val borderTopLeft = Offset(inset, inset)
        val borderSize = Size(
            (size.width - inset * 2f).coerceAtLeast(0f),
            (size.height - inset * 2f).coerceAtLeast(0f),
        )
        val sideGlowWidth = 22.dp.toPx() + (22.dp.toPx() * level)
        val topGlowHeight = 16.dp.toPx() + (12.dp.toPx() * level)
        val bottomGlowHeight = 22.dp.toPx() + (22.dp.toPx() * level)
        val borderBrush = Brush.sweepGradient(
            colors = ListeningGlowColors,
            center = center,
        )

        // SrcOver (default) so the glow stays visible on light and dark scaffolds.
        // Screen blend only composites within the Canvas layer and looks nearly
        // invisible against light Material backgrounds.
        drawRoundRect(
            brush = borderBrush,
            topLeft = borderTopLeft,
            size = borderSize,
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = outerStroke),
            alpha = 0.28f * speechBoost,
        )
        drawRoundRect(
            brush = borderBrush,
            topLeft = borderTopLeft,
            size = borderSize,
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = middleStroke),
            alpha = 0.42f * speechBoost,
        )
        drawRoundRect(
            brush = borderBrush,
            topLeft = borderTopLeft,
            size = borderSize,
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = innerStroke),
            alpha = 0.72f * speechBoost,
        )

        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF2BD6FF).copy(alpha = 0.45f * speechBoost),
                    Color.Transparent,
                ),
            ),
            topLeft = Offset.Zero,
            size = Size(sideGlowWidth, size.height),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF7A5CFF).copy(alpha = 0.40f * speechBoost),
                ),
            ),
            topLeft = Offset(size.width - sideGlowWidth, 0f),
            size = Size(sideGlowWidth, size.height),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF26D8FF).copy(alpha = 0.28f * speechBoost),
                    Color.Transparent,
                ),
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, topGlowHeight),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF00C084).copy(alpha = 0.22f * speechBoost),
                    Color(0xFF2BD6FF).copy(alpha = 0.48f * speechBoost),
                    Color(0xFFFF7A59).copy(alpha = 0.26f * speechBoost),
                ),
            ),
            topLeft = Offset(0f, size.height - bottomGlowHeight),
            size = Size(size.width, bottomGlowHeight),
        )
    }
}

@Composable
private fun RawEditorPanel(
    modifier: Modifier,
    state: MainUiState,
    viewModel: MainViewModel,
    hasRecordPermission: Boolean,
    onEnsurePermission: () -> Unit,
    onRequestPermission: () -> Unit,
    clipboardText: () -> Unit,
    onRequestTranslateLanguage: () -> Unit,
) {
    EditorPanel(
        modifier = modifier,
        title = "Raw Transcription",
        value = state.rawTextValue,
        onValueChange = viewModel::updateRawText,
        fontSizeSp = state.settings.fontSize.editorSp,
        controls = {
            ListenControls(
                state = state,
                hasRecordPermission = hasRecordPermission,
                onStartRecording = onEnsurePermission,
                onRequestPermission = onRequestPermission,
                onStopRecording = viewModel::stopRecording,
                onToggleRecording = {
                    if (state.isRecording) {
                        viewModel.stopRecording()
                    } else {
                        onEnsurePermission()
                    }
                },
            )
            ActionIconButton(
                icon = Icons.Filled.AutoFixHigh,
                contentDescription = "Polish text",
                isBusy = state.isPolishing,
                enabled = !state.isTranslating,
                onClick = viewModel::polishText,
            )
            TranslateActionButton(
                languageCode = state.settings.translateLanguageCode,
                isBusy = state.isTranslating,
                enabled = !state.isPolishing,
                onClick = {
                    if (!isConfiguredTranslateLanguage(state.settings.translateLanguageCode)) {
                        onRequestTranslateLanguage()
                    } else {
                        viewModel.polishAndTranslateText()
                    }
                },
            )
            ActionIconButton(
                icon = Icons.Filled.Save,
                contentDescription = "Save raw transcription as note",
                enabled = state.rawTextValue.text.trim().isNotBlank(),
                onClick = viewModel::manualSaveRawNote,
            )
            ActionIconButton(
                icon = Icons.Filled.ContentCopy,
                contentDescription = "Copy raw transcription",
                onClick = clipboardText,
            )
            ActionIconButton(
                icon = Icons.Filled.Delete,
                contentDescription = "Clear raw transcription",
                onClick = viewModel::clearRawText,
            )
        },
    )
}

@Composable
private fun PolishedEditorPanel(
    modifier: Modifier,
    state: MainUiState,
    viewModel: MainViewModel,
    clipboardText: () -> Unit,
    shareText: () -> Unit,
    onOpenNotes: () -> Unit,
) {
    EditorPanel(
        modifier = modifier,
        title = "Polished Text",
        value = state.polishedTextValue,
        onValueChange = viewModel::updatePolishedText,
        fontSizeSp = state.settings.fontSize.editorSp,
        controls = {
            ActionIconButton(
                icon = Icons.Filled.Share,
                contentDescription = "Share polished text",
                enabled = state.polishedTextValue.text.isNotBlank(),
                onClick = shareText,
            )
            ActionIconButton(
                icon = Icons.Filled.Save,
                contentDescription = "Save polished text as note",
                enabled = state.polishedTextValue.text.trim().isNotBlank(),
                onClick = viewModel::manualSaveCurrentNote,
            )
            ActionIconButton(
                icon = Icons.Filled.ContentCopy,
                contentDescription = "Copy polished text",
                onClick = clipboardText,
            )
            ActionIconButton(
                icon = Icons.Filled.Delete,
                contentDescription = "Clear polished text",
                onClick = viewModel::clearPolishedText,
            )
            ActionIconButton(
                icon = Icons.Filled.DeleteSweep,
                contentDescription = "Clear both editors",
                onClick = viewModel::clearAllText,
            )
        },
        trailingControls = {
            ActionIconButton(
                icon = Icons.Filled.KeyboardArrowRight,
                contentDescription = "Open saved notes",
                onClick = onOpenNotes,
            )
        },
    )
}

@Composable
private fun EditorPanel(
    modifier: Modifier = Modifier,
    title: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    fontSizeSp: Int,
    controls: @Composable () -> Unit,
    trailingControls: (@Composable () -> Unit)? = null,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        val editorTextStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = fontSizeSp.sp,
            lineHeight = fontSizeSp.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 220.dp),
                value = value,
                onValueChange = onValueChange,
                textStyle = editorTextStyle,
                singleLine = false,
            )

            if (trailingControls == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    controls()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        controls()
                    }
                    trailingControls()
                }
            }
        }
    }
}

@Composable
private fun ListenControls(
    state: MainUiState,
    hasRecordPermission: Boolean,
    onStartRecording: () -> Unit,
    onRequestPermission: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val canStartListen = !state.isImportingAudio && !state.isTranscribing
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    fun dismissEditorIme() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        // Also ask the platform window to hide IME immediately (Compose hide can lag a frame).
        val view = (context as? Activity)?.currentFocus
            ?: (context as? Activity)?.window?.decorView
        if (view != null) {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // Dismiss IME on the raw press signal before recording starts (not after recomposition).
    LaunchedEffect(interactionSource, state.settings.listenMode) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                dismissEditorIme()
            }
        }
    }

    LaunchedEffect(
        isPressed,
        state.settings.listenMode,
        hasRecordPermission,
        state.isImportingAudio,
        state.isTranscribing,
    ) {
        if (state.settings.listenMode == ListenMode.HOLD && hasRecordPermission && canStartListen) {
            if (isPressed) {
                dismissEditorIme()
                onStartRecording()
            } else {
                onStopRecording()
            }
        }
    }

    Button(
        onClick = {
            dismissEditorIme()
            when {
                state.settings.listenMode == ListenMode.TOGGLE -> onToggleRecording()
                !hasRecordPermission -> onRequestPermission()
            }
        },
        enabled = state.isRecording || canStartListen,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(44.dp),
    ) {
        when {
            state.isTranscribing || state.isImportingAudio -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }

            state.isRecording -> Icon(
                Icons.Filled.Stop,
                contentDescription = "Stop recording",
            )
            else -> Icon(
                Icons.Filled.Mic,
                contentDescription = "Listen",
            )
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isBusy: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isBusy,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(40.dp),
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
            )
        }
    }
}

@Composable
private fun TranslateActionButton(
    languageCode: String,
    isBusy: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val language = findTranslateLanguage(languageCode)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isBusy,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(40.dp),
    ) {
        when {
            isBusy -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            language == null -> {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = "Choose translation language",
                )
            }
            else -> {
                Text(
                    text = language.buttonCode(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TranslateLanguagePickerDialog(
    selectedCode: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pendingCode by rememberSaveable(selectedCode) {
        mutableStateOf(selectedCode.ifBlank { "en" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pendingCode) },
                enabled = pendingCode.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Translation language") },
        text = {
            TranslateLanguageDropdown(
                selectedCode = pendingCode,
                onSelected = { pendingCode = it },
            )
        },
    )
}

@Composable
private fun ImportedAudioCard(
    importedAudio: ImportedAudio,
    expanded: Boolean,
    isBusy: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTranscribe: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse imported audio" else "Expand imported audio",
                )
                Text(
                    text = "Imported",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = importedAudio.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                ActionIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Clear imported audio",
                    enabled = !isBusy,
                    onClick = onClear,
                )
            }

            if (expanded) {
                Text(
                    text = importedAudio.sourceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionIconButton(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = "Transcribe imported audio",
                        isBusy = isBusy,
                        onClick = onTranscribe,
                    )
                    ActionIconButton(
                        icon = Icons.Filled.Save,
                        contentDescription = "Save imported audio",
                        enabled = !isBusy,
                        onClick = onSave,
                    )
                }
            }
        }
    }
}

private val ListeningGlowColors = listOf(
    Color(0xFF0078D7),
    Color(0xFF26D8FF),
    Color(0xFF6CEAFF),
    Color(0xFF7A5CFF),
    Color(0xFF00C084),
    Color(0xFFFF7A59),
    Color(0xFF0078D7),
)
