package com.konashevich.pressscribe.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.konashevich.pressscribe.data.AppSettings
import com.konashevich.pressscribe.data.FontSizeOption
import com.konashevich.pressscribe.data.ListenMode
import com.konashevich.pressscribe.data.PRIVACY_POLICY_URL
import com.konashevich.pressscribe.data.ServerScheme
import com.konashevich.pressscribe.data.TERMS_OF_SERVICE_URL
import com.konashevich.pressscribe.data.TRANSLATE_LANGUAGE_PLACEHOLDER
import com.konashevich.pressscribe.data.TRANSLATE_LANGUAGES
import com.konashevich.pressscribe.data.ThemeMode
import com.konashevich.pressscribe.data.TranscriptionService
import com.konashevich.pressscribe.data.VolumeButtonMode
import com.konashevich.pressscribe.data.findTranslateLanguage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
    onFontSizeChanged: (FontSizeOption) -> Unit,
    onListenModeChanged: (ListenMode) -> Unit,
    onVolumeButtonModeChanged: (VolumeButtonMode) -> Unit,
    onTranscriptionServiceChanged: (TranscriptionService) -> Unit,
    onGeminiApiKeyChanged: (String) -> Unit,
    onGeminiModelChanged: (String) -> Unit,
    onPolishPromptChanged: (String) -> Unit,
    onTranslatePolishPromptChanged: (String) -> Unit,
    onTranslateLanguageChanged: (String) -> Unit,
    onServerSchemeChanged: (ServerScheme) -> Unit,
    onServerHostChanged: (String) -> Unit,
    onServerPortChanged: (String) -> Unit,
    onServerPathChanged: (String) -> Unit,
    onServerTimeoutChanged: (String) -> Unit,
    onVibrationDurationChanged: (String) -> Unit,
    onAutoSaveNotesChanged: (Boolean) -> Unit,
    onImportSettings: () -> Unit,
    onShowWelcomeSetup: () -> Unit,
) {
    var timeoutText by rememberSaveable(settings.serverTimeoutSeconds) {
        mutableStateOf(settings.serverTimeoutSeconds.toString())
    }
    var vibrationText by rememberSaveable(settings.vibrationDurationMs) {
        mutableStateOf(settings.vibrationDurationMs.toString())
    }
    var polishPromptText by rememberSaveable { mutableStateOf(settings.polishPrompt) }
    var translatePromptText by rememberSaveable { mutableStateOf(settings.translatePolishPrompt) }
    var geminiApiKeyText by rememberSaveable { mutableStateOf(settings.geminiApiKey) }
    var geminiModelText by rememberSaveable { mutableStateOf(settings.geminiModel) }
    var serverHostText by rememberSaveable { mutableStateOf(settings.serverHost) }
    var serverPortText by rememberSaveable { mutableStateOf(settings.serverPort) }
    var serverPathText by rememberSaveable { mutableStateOf(settings.serverPath) }
    var lastFlushedPolish by remember { mutableStateOf(settings.polishPrompt) }
    var lastFlushedTranslate by remember { mutableStateOf(settings.translatePolishPrompt) }
    var lastFlushedApiKey by remember { mutableStateOf(settings.geminiApiKey) }
    var lastFlushedModel by remember { mutableStateOf(settings.geminiModel) }
    var lastFlushedHost by remember { mutableStateOf(settings.serverHost) }
    var lastFlushedPort by remember { mutableStateOf(settings.serverPort) }
    var lastFlushedPath by remember { mutableStateOf(settings.serverPath) }
    val disposeGuard = remember { object { var skipFlush = false } }

    fun flushDeferredTextSettings() {
        if (polishPromptText != lastFlushedPolish) {
            onPolishPromptChanged(polishPromptText)
            lastFlushedPolish = polishPromptText
        }
        if (translatePromptText != lastFlushedTranslate) {
            onTranslatePolishPromptChanged(translatePromptText)
            lastFlushedTranslate = translatePromptText
        }
        if (geminiApiKeyText != lastFlushedApiKey) {
            onGeminiApiKeyChanged(geminiApiKeyText)
            lastFlushedApiKey = geminiApiKeyText
        }
        if (geminiModelText != lastFlushedModel) {
            onGeminiModelChanged(geminiModelText)
            lastFlushedModel = geminiModelText
        }
        if (serverHostText != lastFlushedHost) {
            onServerHostChanged(serverHostText)
            lastFlushedHost = serverHostText
        }
        if (serverPortText != lastFlushedPort) {
            onServerPortChanged(serverPortText)
            lastFlushedPort = serverPortText
        }
        if (serverPathText != lastFlushedPath) {
            onServerPathChanged(serverPathText)
            lastFlushedPath = serverPathText
        }
    }

    // Flush dirty drafts on leave, unless we already flushed for import/welcome/dismiss.
    DisposableEffect(Unit) {
        onDispose {
            if (!disposeGuard.skipFlush) {
                flushDeferredTextSettings()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            flushDeferredTextSettings()
            disposeGuard.skipFlush = true
            onDismiss()
        },
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsSection("Transcription Service") {
                ChoiceChips(
                    values = TranscriptionService.entries.toList(),
                    selected = settings.transcriptionService,
                    labelOf = { it.label },
                    onSelected = onTranscriptionServiceChanged,
                )
            }

            SettingsSection("AI Service") {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Gemini only on Android") },
                )
            }

            SettingsSection("Theme") {
                ChoiceChips(
                    values = ThemeMode.entries.toList(),
                    selected = settings.themeMode,
                    labelOf = { it.label },
                    onSelected = onThemeChanged,
                )
                OutlinedButton(
                    onClick = {
                        flushDeferredTextSettings()
                        disposeGuard.skipFlush = true
                        onShowWelcomeSetup()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Show welcome setup")
                }
            }

            SettingsSection("Font Size") {
                ChoiceChips(
                    values = FontSizeOption.entries.toList(),
                    selected = settings.fontSize,
                    labelOf = { it.label },
                    onSelected = onFontSizeChanged,
                )
            }

            SettingsSection("Listen Mode") {
                ChoiceChips(
                    values = ListenMode.entries.toList(),
                    selected = settings.listenMode,
                    labelOf = { it.label },
                    onSelected = onListenModeChanged,
                )
            }

            SettingsSection("Volume Buttons") {
                ChoiceChips(
                    values = VolumeButtonMode.entries.toList(),
                    selected = settings.volumeButtonMode,
                    labelOf = { it.label },
                    onSelected = onVolumeButtonModeChanged,
                )
            }

            SettingsSection("Saved Notes") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Auto-save polished text",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = settings.autoSaveNotes,
                        onCheckedChange = onAutoSaveNotesChanged,
                    )
                }
            }

            SettingsSection("Haptics") {
                Text(
                    text = "Vibration is used when recording starts and stops. Set 0 to disable it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = vibrationText,
                    onValueChange = { newValue ->
                        val filtered = newValue.filter(Char::isDigit)
                        vibrationText = filtered
                        if (filtered.isNotEmpty()) {
                            onVibrationDurationChanged(filtered)
                        }
                    },
                    label = { Text("Vibration (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            SettingsSection("Settings Import") {
                Text(
                    text = "Import supported values from a desktop-style settings JSON on the device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        flushDeferredTextSettings()
                        disposeGuard.skipFlush = true
                        onImportSettings()
                    },
                ) {
                    Text("Import Settings JSON")
                }
            }

            SettingsSection("Gemini") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = geminiApiKeyText,
                    onValueChange = { geminiApiKeyText = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = geminiModelText,
                    onValueChange = { geminiModelText = it },
                    label = { Text("Model") },
                )
            }

            SettingsSection("Self-hosted ASR") {
                ChoiceChips(
                    values = ServerScheme.entries.toList(),
                    selected = settings.serverScheme,
                    labelOf = { it.label },
                    onSelected = onServerSchemeChanged,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverHostText,
                    onValueChange = { serverHostText = it },
                    label = { Text("Host or IP") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = serverPortText,
                        onValueChange = { serverPortText = it },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = timeoutText,
                        onValueChange = { newValue ->
                            val filtered = newValue.filter(Char::isDigit)
                            timeoutText = filtered
                            if (filtered.isNotEmpty()) {
                                onServerTimeoutChanged(filtered)
                            }
                        },
                        label = { Text("Timeout (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverPathText,
                    onValueChange = { serverPathText = it },
                    label = { Text("Path") },
                )
                settings.selfHostedUrl()?.let { previewUrl ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = previewUrl,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsSection("Polish Prompt") {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    value = polishPromptText,
                    onValueChange = { polishPromptText = it },
                    label = { Text("System Prompt") },
                )
            }

            SettingsSection("Polish + Translate") {
                TranslateLanguageDropdown(
                    selectedCode = settings.translateLanguageCode,
                    onSelected = onTranslateLanguageChanged,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    value = translatePromptText,
                    onValueChange = { translatePromptText = it },
                    label = { Text("Translate System Prompt") },
                    supportingText = {
                        Text("Use $TRANSLATE_LANGUAGE_PLACEHOLDER for the target language name.")
                    },
                )
            }

            SettingsSection("Legal") {
                val context = LocalContext.current
                Text(
                    text = "Privacy Policy and Terms of Service for PressScribe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Privacy Policy")
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_OF_SERVICE_URL)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Terms of Service")
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateLanguageDropdown(
    selectedCode: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = remember(selectedCode) { findTranslateLanguage(selectedCode) }
    val displayText = selected?.let { "${it.label} (${it.buttonCode()})" }.orEmpty()
    val filteredLanguages = remember(query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            TRANSLATE_LANGUAGES
        } else {
            TRANSLATE_LANGUAGES.filter { language ->
                language.label.contains(needle, ignoreCase = true) ||
                    language.code.contains(needle, ignoreCase = true) ||
                    language.buttonCode().contains(needle, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = query,
            onValueChange = {
                query = it
                if (!expanded) {
                    expanded = true
                }
            },
            label = { Text("Search languages") },
            singleLine = true,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                value = displayText,
                onValueChange = {},
                readOnly = true,
                label = { Text("Target language") },
                placeholder = { Text("Select language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (filteredLanguages.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No matches") },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filteredLanguages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text("${language.label} (${language.buttonCode()})") },
                            onClick = {
                                onSelected(language.code)
                                query = ""
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    values: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = { Text(labelOf(option)) },
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}
